package com.carenexus.api.calls.service;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.calls.config.CallsProperties;
import com.carenexus.api.calls.config.LiveKitProperties;
import com.carenexus.api.calls.dto.CallParticipantResponse;
import com.carenexus.api.calls.dto.CallStatusResponse;
import com.carenexus.api.calls.dto.CallTokenResponse;
import com.carenexus.api.calls.model.CallJob;
import com.carenexus.api.calls.model.CallParticipant;
import com.carenexus.api.calls.model.CallSession;
import com.carenexus.api.calls.provider.CallProvider;
import com.carenexus.api.calls.provider.CallProviderException;
import com.carenexus.api.calls.repository.CallJobRepository;
import com.carenexus.api.calls.repository.CallParticipantRepository;
import com.carenexus.api.calls.repository.CallSessionRepository;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.core.model.Appointment;
import com.carenexus.api.core.repository.AppointmentRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The /api/appointments/{appointmentId}/call endpoints. Rules (CONTRACT 8.2):
 * - status: the appointment's doctor, its patient, a caregiver with JOIN_CALLS for the patient, ADMIN (read only);
 * - start/end/confirm-time/admit/deny/remove: only the appointment's doctor;
 * - join-request: the patient or a caregiver with JOIN_CALLS (re-checked); doctors start instead; admins never;
 * - token/leave: participants of this session; admins never.
 * Consent is re-checked when admitting and on every token. Leaving or ending never changes the appointment or
 * consultation status.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(noRollbackFor = {CallCommittedConflictException.class, CallCommittedAuthorizationException.class})
public class CallService {

    static final String RESOURCE_TYPE = "CALL";
    static final String MSG_NOT_ALLOWED = "You are not allowed to access this call";
    static final String MSG_DOCTOR_ONLY = "Only the appointment's doctor can manage this call";
    static final String MSG_NO_VIDEO_CALL = "This appointment has no video call";
    static final String MSG_PREPARING = "The call room is being prepared. Try again in a few seconds.";
    static final String MSG_CLOSED = "This call has closed";
    static final String MSG_ENDED = "This call has ended";
    static final String MSG_CANCELLED = "This appointment is cancelled";
    static final String MSG_NOT_LIVE = "This call is not live";
    static final String MSG_NOT_ADMITTED = "You have not been admitted to this call yet";
    static final String MSG_ACCESS_REMOVED = "Your access to this call has been removed";
    static final String MSG_UNAVAILABLE = "Video calls are temporarily unavailable. Please try again later.";
    static final String MSG_NOT_WAITING = "This join request is no longer waiting";
    static final String MSG_PARTICIPANT_NOT_FOUND = "Participant not found";

    private static final Set<String> REMOVAL_JOB_TYPES = Set.of(CallJob.REMOVE_PARTICIPANT, CallJob.DELETE_ROOM);
    private static final Set<String> UNCONFIRMED_JOB_STATUSES = Set.of(CallJob.PENDING, CallJob.RUNNING, CallJob.FAILED);

    private final AppointmentRepository appointmentRepository;
    private final CallSessionRepository callSessionRepository;
    private final CallParticipantRepository callParticipantRepository;
    private final CallJobRepository callJobRepository;
    private final UserRepository userRepository;
    private final CallSessionService callSessionService;
    private final CallJobService callJobService;
    private final CallProvider callProvider;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final CallsProperties callsProperties;
    private final LiveKitProperties liveKitProperties;
    private final ObjectMapper objectMapper;

    /** Caller resolved against one appointment. */
    record Caller(String systemId, String role, boolean appointmentDoctor, boolean appointmentPatient) {
        boolean isAdmin() {
            return AuthorizationService.ADMIN.equals(role);
        }

        boolean isCaregiver() {
            return AuthorizationService.CAREGIVER.equals(role);
        }
    }

    /** Appointment + session loaded (and locked when changing) for one request. */
    record Context(Appointment appointment, CallSession session, Caller caller) { }

    // ==================== READ ====================

    public CallStatusResponse getStatus(Integer appointmentId) {
        Context ctx = load(appointmentId, false);
        requireCanView(ctx);
        return buildStatus(ctx);
    }

    // ==================== DOCTOR ====================

    public CallStatusResponse start(Integer appointmentId) {
        Context ctx = load(appointmentId, true);
        requireDoctor(ctx);
        CallSession session = ctx.session();
        Appointment appointment = ctx.appointment();
        Instant now = Instant.now();

        switch (session.getStatus()) {
            case CallSession.NEEDS_TIME_CONFIRMATION ->
                    throw new ConflictException("Confirm the appointment time before starting the call");
            case CallSession.CANCELLED -> throw new ConflictException(MSG_CANCELLED);
            case CallSession.ENDED -> throw new ConflictException(MSG_ENDED);
            default -> {
                // scheduled statuses continue below
            }
        }
        if ("CANCELLED".equals(appointment.getStatus())) {
            throw new ConflictException(MSG_CANCELLED);
        }
        if (!CallSessionService.ACTIVE_APPOINTMENT_STATUSES.contains(appointment.getStatus())) {
            throw new ConflictException(MSG_CLOSED);
        }
        requireWindowOpen(session, now);

        if (CallSession.PREPARING.equals(session.getStatus()) || CallSession.FAILED.equals(session.getStatus())) {
            session.setStatus(CallSession.PREPARING);
            session.setLastError(null);
            callSessionRepository.save(session);
            callJobService.enqueueCreateRoom(session, now);
            throw new CallCommittedConflictException(MSG_PREPARING);
        }

        if (CallSession.READY.equals(session.getStatus())) {
            session.setStatus(CallSession.LIVE);
            session.setStartedAt(now);
            session.setStartedBySystemId(ctx.caller().systemId());
            callSessionRepository.save(session);
        }
        // READY -> LIVE, or LIVE: doctor (re)joins with an ADMITTED row.
        CallParticipant open = openRow(session, ctx.caller().systemId());
        if (open == null) {
            User doctor = userRepository.findBySystemId(ctx.caller().systemId()).orElse(null);
            callParticipantRepository.save(CallParticipant.builder()
                    .callSessionId(session.getCallSessionId())
                    .systemId(ctx.caller().systemId())
                    .participantRole(AuthorizationService.DOCTOR)
                    .actingForPatient(false)
                    .status(CallParticipant.ADMITTED)
                    .providerIdentity(CallSessionService.newProviderIdentity())
                    .displayName(limit(CallLabels.doctor(doctor)))
                    .requestedAt(now)
                    .decidedAt(now)
                    .decidedBySystemId(ctx.caller().systemId())
                    .admittedAt(now)
                    .removalPending(false)
                    .build());
        }
        audit("START", ctx);
        return buildStatus(ctx);
    }

    public CallStatusResponse end(Integer appointmentId) {
        Context ctx = load(appointmentId, true);
        requireDoctor(ctx);
        CallSession session = ctx.session();
        if (CallSession.FINAL_STATUSES.contains(session.getStatus())) {
            return buildStatus(ctx);
        }
        if (!CallSession.LIVE.equals(session.getStatus())) {
            throw new ConflictException("The call has not started");
        }
        callSessionService.terminate(session, CallSession.ENDED, CallSession.END_DOCTOR_ENDED,
                CallParticipant.REASON_CALL_ENDED, ctx.caller().systemId());
        audit("END", ctx);
        return buildStatus(ctx);
    }

    public CallStatusResponse confirmTime(Integer appointmentId, LocalDateTime confirmedScheduledAt) {
        Context ctx = load(appointmentId, true);
        requireDoctor(ctx);
        CallSession session = ctx.session();
        if (!CallSession.NEEDS_TIME_CONFIRMATION.equals(session.getStatus())) {
            throw new ConflictException("This call's time is already confirmed");
        }
        LocalDateTime stored = ctx.appointment().getScheduledAt();
        if (confirmedScheduledAt == null || stored == null
                || !confirmedScheduledAt.truncatedTo(ChronoUnit.MINUTES).equals(stored.truncatedTo(ChronoUnit.MINUTES))) {
            throw new ConflictException("The appointment time has changed. Reload and confirm again.");
        }
        callSessionService.confirmTime(session, ctx.appointment(), ctx.caller().systemId());
        audit("UPDATE", ctx);
        return buildStatus(ctx);
    }

    public CallStatusResponse admit(Integer appointmentId, Long participantId) {
        Context ctx = load(appointmentId, true);
        requireDoctor(ctx);
        CallParticipant participant = findParticipant(ctx, participantId);
        if (CallParticipant.ADMITTED.equals(participant.getStatus())) {
            return buildStatus(ctx);
        }
        if (!CallParticipant.REQUESTED.equals(participant.getStatus())) {
            throw new ConflictException(MSG_NOT_WAITING);
        }
        Instant now = Instant.now();
        participant.setDecidedAt(now);
        participant.setDecidedBySystemId(ctx.caller().systemId());
        if (!stillAllowedToJoin(participant, ctx.appointment())) {
            participant.setStatus(CallParticipant.DENIED);
            callParticipantRepository.save(participant);
            audit("DENY", ctx);
            throw new CallCommittedConflictException("This person no longer has permission to join this call");
        }
        participant.setStatus(CallParticipant.ADMITTED);
        participant.setAdmittedAt(now);
        callParticipantRepository.save(participant);
        audit("ADMIT", ctx);
        return buildStatus(ctx);
    }

    public CallStatusResponse deny(Integer appointmentId, Long participantId) {
        Context ctx = load(appointmentId, true);
        requireDoctor(ctx);
        CallParticipant participant = findParticipant(ctx, participantId);
        if (!CallParticipant.REQUESTED.equals(participant.getStatus())) {
            throw new ConflictException(MSG_NOT_WAITING);
        }
        participant.setStatus(CallParticipant.DENIED);
        participant.setDecidedAt(Instant.now());
        participant.setDecidedBySystemId(ctx.caller().systemId());
        callParticipantRepository.save(participant);
        audit("DENY", ctx);
        return buildStatus(ctx);
    }

    public CallStatusResponse remove(Integer appointmentId, Long participantId) {
        Context ctx = load(appointmentId, true);
        requireDoctor(ctx);
        CallParticipant participant = findParticipant(ctx, participantId);
        if (ctx.caller().systemId().equals(participant.getSystemId())) {
            throw new RuntimeException("You cannot remove yourself");
        }
        if (!CallParticipant.ADMITTED.equals(participant.getStatus())) {
            throw new ConflictException("This person is not in the call");
        }
        callSessionService.removeParticipant(ctx.session(), participant, CallParticipant.REASON_REMOVED_BY_DOCTOR);
        audit("REMOVE", ctx);
        return buildStatus(ctx);
    }

    // ==================== PATIENT / CAREGIVER ====================

    public CallStatusResponse requestJoin(Integer appointmentId, Boolean actingForPatient) {
        Context ctx = load(appointmentId, true);
        Caller caller = ctx.caller();
        if (caller.isAdmin()) {
            throw deny(ctx, "Admin cannot join calls", MSG_NOT_ALLOWED);
        }
        if (AuthorizationService.DOCTOR.equals(caller.role())) {
            if (!caller.appointmentDoctor()) {
                throw deny(ctx, "Doctor is not the appointment's doctor", MSG_NOT_ALLOWED);
            }
            throw new RuntimeException("Doctors join by starting the call");
        }
        requirePatientOrJoinCaregiver(ctx);

        CallSession session = ctx.session();
        if (CallSession.CANCELLED.equals(session.getStatus())) {
            throw new ConflictException(MSG_CANCELLED);
        }
        if (CallSession.ENDED.equals(session.getStatus())) {
            throw new ConflictException(MSG_ENDED);
        }
        boolean acting = caller.isCaregiver() && Boolean.TRUE.equals(actingForPatient);

        CallParticipant open = openRow(session, caller.systemId());
        if (open != null) {
            if (CallParticipant.REQUESTED.equals(open.getStatus()) && caller.isCaregiver()
                    && open.getActingForPatient() != acting) {
                open.setActingForPatient(acting);
                open.setDisplayName(limit(labelFor(caller, ctx.appointment(), acting)));
                callParticipantRepository.save(open);
            }
            return buildStatus(ctx);
        }

        Instant now = Instant.now();
        if (CallSession.NEEDS_TIME_CONFIRMATION.equals(session.getStatus())) {
            throw new ConflictException("The doctor has not started the call yet");
        }
        requireWindowOpen(session, now);
        if (!CallSession.LIVE.equals(session.getStatus())) {
            throw new ConflictException("The doctor has not started the call yet");
        }
        callParticipantRepository.save(CallParticipant.builder()
                .callSessionId(session.getCallSessionId())
                .systemId(caller.systemId())
                .participantRole(caller.role())
                .actingForPatient(acting)
                .status(CallParticipant.REQUESTED)
                .providerIdentity(CallSessionService.newProviderIdentity())
                .displayName(limit(labelFor(caller, ctx.appointment(), acting)))
                .requestedAt(now)
                .removalPending(false)
                .build());
        return buildStatus(ctx);
    }

    /** Short-lived provider token for an ADMITTED participant. Never logged. */
    public CallTokenResponse issueToken(Integer appointmentId) {
        Context ctx = load(appointmentId, true);
        Caller caller = ctx.caller();
        if (caller.isAdmin()) {
            throw deny(ctx, "Admin cannot join calls", MSG_NOT_ALLOWED);
        }
        requireParticipantRole(ctx);
        CallSession session = ctx.session();
        Instant now = Instant.now();

        if (!CallSession.LIVE.equals(session.getStatus())) {
            throw new ConflictException(MSG_NOT_LIVE);
        }
        requireWindowOpen(session, now);
        CallParticipant open = openRow(session, caller.systemId());
        if (open == null) {
            CallParticipant last = callParticipantRepository
                    .findFirstByCallSessionIdAndSystemIdOrderByCallParticipantIdDesc(session.getCallSessionId(),
                            caller.systemId())
                    .orElse(null);
            if (last != null && (CallParticipant.REMOVED.equals(last.getStatus())
                    || CallParticipant.DENIED.equals(last.getStatus()))) {
                throw new ConflictException(MSG_ACCESS_REMOVED);
            }
        }
        if (open == null || !CallParticipant.ADMITTED.equals(open.getStatus())) {
            throw new ConflictException(MSG_NOT_ADMITTED);
        }
        if (open.getRevokedAt() != null) {
            throw new ConflictException(MSG_ACCESS_REMOVED);
        }
        // Consent re-check at token time (caregiver JOIN_CALLS now; patient/doctor still on the appointment).
        if (!stillAllowedToJoin(open, ctx.appointment())) {
            callSessionService.removeParticipant(session, open, CallParticipant.REASON_ACCESS_REVOKED);
            auditDenied(ctx, "Consent re-check failed at token issuance");
            throw new CallCommittedAuthorizationException(MSG_NOT_ALLOWED, RESOURCE_TYPE, appointmentId);
        }
        if (!callProvider.isConfigured()) {
            throw new ConflictException(MSG_UNAVAILABLE);
        }

        Instant expiresAt = now.plusSeconds(Math.max(60, liveKitProperties.getTokenTtlSeconds()));
        CallProvider.IssuedToken token;
        try {
            token = callProvider.issueToken(new CallProvider.TokenRequest(session.getRoomName(),
                    open.getProviderIdentity(), open.getDisplayName(), metadata(open), expiresAt));
        } catch (CallProviderException e) {
            log.warn("Token issuance failed for call session {}: {}", session.getCallSessionId(), e.getMessage());
            throw new ConflictException(MSG_UNAVAILABLE);
        }
        open.setLastTokenIssuedAt(now);
        callParticipantRepository.save(open);
        auditService.logClinicalAccess("CREATE", "CALL_TOKEN", appointmentId, ctx.appointment().getPatientSystemId());
        return CallTokenResponse.builder()
                .serverUrl(callProvider.clientUrl())
                .token(token.token())
                .expiresAt(token.expiresAt())
                .identity(open.getProviderIdentity())
                .build();
    }

    /** Leave (anyone with an open row). Never ends the call and never changes appointment/consultation status. */
    public CallStatusResponse leave(Integer appointmentId) {
        Context ctx = load(appointmentId, true);
        Caller caller = ctx.caller();
        if (caller.isAdmin()) {
            throw deny(ctx, "Admin cannot join calls", MSG_NOT_ALLOWED);
        }
        CallParticipant open = openRow(ctx.session(), caller.systemId());
        if (open == null) {
            requireCanView(ctx);
            return buildStatus(ctx);
        }
        Instant now = Instant.now();
        open.setStatus(CallParticipant.LEFT);
        open.setLeftAt(now);
        open.setRevokedAt(now);
        CallParticipant saved = callParticipantRepository.save(open);
        if (saved.getProviderJoinedAt() != null || saved.getLastTokenIssuedAt() != null) {
            // Clean up a possibly stale connection.
            callJobService.enqueueRemoveParticipant(ctx.session(), saved);
        }
        return buildStatus(ctx);
    }

    // ==================== LOADING / ACCESS ====================

    Context load(Integer appointmentId, boolean forUpdate) {
        if (appointmentId == null) {
            throw new RuntimeException("Appointment ID is required");
        }
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found"));
        String systemId = authorizationService.currentSystemId();
        String role = authorizationService.currentRole();
        Caller caller = new Caller(systemId, role,
                AuthorizationService.DOCTOR.equals(role) && systemId.equals(appointment.getDoctorSystemId()),
                AuthorizationService.PATIENT.equals(role) && systemId.equals(appointment.getPatientSystemId()));
        if (!CallSessionService.VIDEO.equals(appointment.getType())) {
            requireCanViewAppointmentParty(caller, appointment);
            throw new ResourceNotFoundException(MSG_NO_VIDEO_CALL);
        }
        // Lock first (before anything loads the row into the persistence context) so the state read is current.
        CallSession session = forUpdate
                ? callSessionRepository.findForUpdateByAppointmentId(appointmentId).orElse(null)
                : callSessionRepository.findByAppointmentId(appointmentId).orElse(null);
        if (session == null) {
            session = callSessionService.getOrCreateForRead(appointment);
        }
        if (session == null) {
            requireCanViewAppointmentParty(caller, appointment);
            throw new ResourceNotFoundException(MSG_NO_VIDEO_CALL);
        }
        return new Context(appointment, session, caller);
    }

    /** Before revealing "no video call", make sure the caller may see the appointment at all. */
    private void requireCanViewAppointmentParty(Caller caller, Appointment appointment) {
        if (caller.isAdmin() || caller.appointmentDoctor() || caller.appointmentPatient()) {
            return;
        }
        if (caller.isCaregiver() && authorizationService.hasCaregiverPermission(caller.systemId(),
                appointment.getPatientSystemId(), PatientPermission.JOIN_CALLS)) {
            return;
        }
        throw new AuthorizationException(MSG_NOT_ALLOWED, RESOURCE_TYPE, appointment.getAppointmentId());
    }

    private void requireCanView(Context ctx) {
        Caller caller = ctx.caller();
        if (caller.isAdmin() || caller.appointmentDoctor() || caller.appointmentPatient()) {
            return;
        }
        if (caller.isCaregiver() && authorizationService.hasCaregiverPermission(caller.systemId(),
                ctx.appointment().getPatientSystemId(), PatientPermission.JOIN_CALLS)) {
            return;
        }
        throw deny(ctx, "Caller is not allowed to access this call", MSG_NOT_ALLOWED);
    }

    private void requireDoctor(Context ctx) {
        if (!ctx.caller().appointmentDoctor()) {
            throw deny(ctx, "Only the appointment's doctor can manage this call", MSG_DOCTOR_ONLY);
        }
    }

    private void requirePatientOrJoinCaregiver(Context ctx) {
        Caller caller = ctx.caller();
        if (caller.appointmentPatient()) {
            return;
        }
        if (caller.isCaregiver() && authorizationService.hasCaregiverPermission(caller.systemId(),
                ctx.appointment().getPatientSystemId(), PatientPermission.JOIN_CALLS)) {
            return;
        }
        throw deny(ctx, "Caller may not join this call", MSG_NOT_ALLOWED);
    }

    /** Token: the appointment's doctor or patient, or a caregiver (JOIN_CALLS is re-checked on the row). */
    private void requireParticipantRole(Context ctx) {
        Caller caller = ctx.caller();
        if (caller.appointmentDoctor() || caller.appointmentPatient()) {
            return;
        }
        // A caregiver is a participant only with a row in this session (e.g. removed after revocation, so the
        // token endpoint can say "access removed") or with JOIN_CALLS for the patient now. Any other caregiver
        // gets 403, so a guessed appointment id never reveals the call's state.
        if (caller.isCaregiver() && (openRow(ctx.session(), caller.systemId()) != null
                || callParticipantRepository.findFirstByCallSessionIdAndSystemIdOrderByCallParticipantIdDesc(
                        ctx.session().getCallSessionId(), caller.systemId()).isPresent()
                || authorizationService.hasCaregiverPermission(caller.systemId(),
                        ctx.appointment().getPatientSystemId(), PatientPermission.JOIN_CALLS))) {
            return;
        }
        throw deny(ctx, "Caller is not a participant of this call", MSG_NOT_ALLOWED);
    }

    /** Consent re-check for an admission or a token. */
    boolean stillAllowedToJoin(CallParticipant participant, Appointment appointment) {
        return switch (participant.getParticipantRole()) {
            case AuthorizationService.DOCTOR -> participant.getSystemId().equals(appointment.getDoctorSystemId());
            case AuthorizationService.PATIENT -> participant.getSystemId().equals(appointment.getPatientSystemId());
            case AuthorizationService.CAREGIVER -> authorizationService.hasCaregiverPermission(
                    participant.getSystemId(), appointment.getPatientSystemId(), PatientPermission.JOIN_CALLS);
            default -> false;
        };
    }

    private void requireWindowOpen(CallSession session, Instant now) {
        if (session.getJoinOpensAt() != null && now.isBefore(session.getJoinOpensAt())) {
            throw new ConflictException("This call opens " + callsProperties.getJoinOpensMinutesBefore()
                    + " minutes before the appointment");
        }
        if (session.getJoinClosesAt() == null || now.isAfter(session.getJoinClosesAt())) {
            throw new ConflictException(MSG_CLOSED);
        }
    }

    private CallParticipant findParticipant(Context ctx, Long participantId) {
        if (participantId == null) {
            throw new ResourceNotFoundException(MSG_PARTICIPANT_NOT_FOUND);
        }
        return callParticipantRepository
                .findByCallParticipantIdAndCallSessionId(participantId, ctx.session().getCallSessionId())
                .orElseThrow(() -> new ResourceNotFoundException(MSG_PARTICIPANT_NOT_FOUND));
    }

    private CallParticipant openRow(CallSession session, String systemId) {
        return callParticipantRepository.findFirstByCallSessionIdAndSystemIdAndStatusIn(session.getCallSessionId(),
                systemId, CallParticipant.OPEN_STATUSES).orElse(null);
    }

    private AuthorizationException deny(Context ctx, String auditReason, String message) {
        auditDenied(ctx, auditReason);
        return new AuthorizationException(message, RESOURCE_TYPE, ctx.appointment().getAppointmentId());
    }

    private void auditDenied(Context ctx, String reason) {
        try {
            auditService.logAccessDenied(ctx.caller().systemId(), RESOURCE_TYPE, ctx.appointment().getAppointmentId(),
                    ctx.appointment().getPatientSystemId(), null, reason);
        } catch (RuntimeException ignored) {
            // The audit write must never mask the denial.
        }
    }

    private void audit(String action, Context ctx) {
        auditService.logClinicalAccess(auditAction(action), RESOURCE_TYPE, ctx.appointment().getAppointmentId(),
                ctx.appointment().getPatientSystemId());
    }

    /**
     * AuditService accepts READ/CREATE/UPDATE/DELETE clinical actions; START/END/ADMIT/DENY/REMOVE are recorded as
     * UPDATE (START as CREATE) on resource type CALL.
     */
    private static String auditAction(String action) {
        return switch (action) {
            case "START" -> "CREATE";
            case "READ", "CREATE", "UPDATE", "DELETE" -> action;
            default -> "UPDATE";
        };
    }

    // ==================== RESPONSE ====================

    private String labelFor(Caller caller, Appointment appointment, boolean acting) {
        User me = userRepository.findBySystemId(caller.systemId()).orElse(null);
        if (caller.isCaregiver()) {
            User patient = userRepository.findBySystemId(appointment.getPatientSystemId()).orElse(null);
            return CallLabels.caregiver(me, patient, acting);
        }
        return CallLabels.patient(me);
    }

    private String metadata(CallParticipant participant) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("role", participant.getParticipantRole());
        meta.put("actingForPatient", Boolean.TRUE.equals(participant.getActingForPatient()));
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    CallStatusResponse buildStatus(Context ctx) {
        Appointment appointment = ctx.appointment();
        CallSession session = ctx.session();
        Caller caller = ctx.caller();
        Instant now = Instant.now();
        boolean manager = caller.appointmentDoctor() || caller.isAdmin();
        boolean windowOpen = CallSessionService.isWindowOpen(session, now);
        boolean appointmentActive = CallSessionService.ACTIVE_APPOINTMENT_STATUSES.contains(appointment.getStatus());
        boolean hasWindow = !CallSession.NEEDS_TIME_CONFIRMATION.equals(session.getStatus());

        List<CallParticipant> rows = callParticipantRepository.findByCallSessionIdOrderByCallParticipantIdAsc(
                session.getCallSessionId());
        CallParticipant mine = rows.stream()
                .filter(p -> caller.systemId().equals(p.getSystemId()) && p.isOpen())
                .findFirst()
                .orElseGet(() -> rows.stream()
                        .filter(p -> caller.systemId().equals(p.getSystemId()))
                        .max(Comparator.comparing(CallParticipant::getCallParticipantId))
                        .orElse(null));

        List<CallParticipantResponse> visible = new ArrayList<>();
        for (CallParticipant p : rows) {
            boolean show = manager
                    ? p.isOpen() || (CallParticipant.REMOVED.equals(p.getStatus()) && Boolean.TRUE.equals(p.getRemovalPending()))
                    : CallParticipant.ADMITTED.equals(p.getStatus());
            if (show) {
                visible.add(toResponse(p, caller));
            }
        }

        boolean removalPending = false;
        if (manager) {
            removalPending = rows.stream().anyMatch(p -> Boolean.TRUE.equals(p.getRemovalPending()))
                    || callJobRepository.existsByCallSessionIdAndJobTypeInAndStatusIn(session.getCallSessionId(),
                    REMOVAL_JOB_TYPES, UNCONFIRMED_JOB_STATUSES);
        }

        boolean live = CallSession.LIVE.equals(session.getStatus());
        boolean joiner = caller.appointmentPatient() || caller.isCaregiver();
        User patient = userRepository.findBySystemId(appointment.getPatientSystemId()).orElse(null);
        User doctor = userRepository.findBySystemId(appointment.getDoctorSystemId()).orElse(null);

        return CallStatusResponse.builder()
                .appointmentId(appointment.getAppointmentId())
                .callStatus(session.getStatus())
                .scheduledStartUtc(hasWindow ? session.getScheduledStartUtc() : null)
                .joinWindowOpensAt(hasWindow ? session.getJoinOpensAt() : null)
                .joinWindowClosesAt(hasWindow ? session.getJoinClosesAt() : null)
                .serverTime(now.truncatedTo(ChronoUnit.SECONDS))
                .joinWindowOpen(windowOpen)
                .scheduledAtLocal(appointment.getScheduledAt())
                .patientName(CallLabels.fullName(patient))
                .doctorName(CallLabels.doctor(doctor))
                .myRole(caller.role())
                .myParticipant(mine == null ? null : toResponse(mine, caller))
                .participants(visible)
                .canStart(caller.appointmentDoctor() && windowOpen && appointmentActive
                        && (CallSession.READY.equals(session.getStatus()) || live))
                .canEnd(caller.appointmentDoctor() && live)
                .canRequestJoin(joiner && live && windowOpen && (mine == null || !mine.isOpen()))
                .canConfirmTime(caller.appointmentDoctor()
                        && CallSession.NEEDS_TIME_CONFIRMATION.equals(session.getStatus()))
                .removalPending(removalPending)
                .build();
    }

    private static CallParticipantResponse toResponse(CallParticipant p, Caller caller) {
        return CallParticipantResponse.builder()
                .participantId(p.getCallParticipantId())
                .role(p.getParticipantRole())
                .label(p.getDisplayName())
                .actingForPatient(Boolean.TRUE.equals(p.getActingForPatient()))
                .status(p.getStatus())
                .requestedAt(p.getRequestedAt())
                .admittedAt(p.getAdmittedAt())
                .connected(p.isConnected())
                .removalPending(Boolean.TRUE.equals(p.getRemovalPending()))
                .me(caller.systemId().equals(p.getSystemId()))
                .build();
    }

    private static String limit(String label) {
        return label.length() <= 200 ? label : label.substring(0, 200);
    }

    /** Visible for tests. */
    static Duration tokenTtl(LiveKitProperties properties) {
        return Duration.ofSeconds(Math.max(60, properties.getTokenTtlSeconds()));
    }
}
