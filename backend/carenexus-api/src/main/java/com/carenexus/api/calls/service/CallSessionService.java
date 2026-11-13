package com.carenexus.api.calls.service;

import com.carenexus.api.calls.config.CallsProperties;
import com.carenexus.api.calls.event.AppointmentCallRelevantChangeEvent;
import com.carenexus.api.calls.model.CallParticipant;
import com.carenexus.api.calls.model.CallSession;
import com.carenexus.api.calls.repository.CallParticipantRepository;
import com.carenexus.api.calls.repository.CallSessionRepository;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.core.model.Appointment;
import com.carenexus.api.core.repository.AppointmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Call session lifecycle (one session per VIDEO appointment): scheduling, appointment sync, ending, and
 * participant removal with the revoked_at cutoff. Provider work is always enqueued as a job, never called here.
 * Call status is separate from appointment/consultation status: nothing here changes an appointment.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CallSessionService {

    public static final String VIDEO = "VIDEO";
    public static final Set<String> ACTIVE_APPOINTMENT_STATUSES = Set.of("SCHEDULED", "RESCHEDULED");
    static final Duration CREATE_ROOM_LEAD = Duration.ofMinutes(5);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final CallSessionRepository callSessionRepository;
    private final CallParticipantRepository callParticipantRepository;
    private final AppointmentRepository appointmentRepository;
    private final CallJobService callJobService;
    private final AuthorizationService authorizationService;
    private final CallsProperties callsProperties;

    @Value("${carenexus.scheduling.time-zone:Africa/Harare}")
    private String schedulingTimeZone;

    // ==================== OPAQUE VALUES ====================

    /** "cn_" + 32 random hex. Never contains ids. */
    public static String newRoomName() {
        return "cn_" + randomHex();
    }

    /** "p_" + 32 random hex. A new identity for every join request. */
    public static String newProviderIdentity() {
        return "p_" + randomHex();
    }

    private static String randomHex() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    // ==================== SCHEDULE ====================

    /** UTC instant of a clinic wall-clock time. */
    public Instant toInstant(LocalDateTime clinicWallClock) {
        return clinicWallClock.atZone(clinicZone()).toInstant();
    }

    public ZoneId clinicZone() {
        try {
            return ZoneId.of(schedulingTimeZone);
        } catch (RuntimeException e) {
            return ZoneId.systemDefault();
        }
    }

    /** Join window: [start - opensBefore, start + duration + closesAfter]. */
    public void applySchedule(CallSession session, Appointment appointment) {
        Instant start = toInstant(appointment.getScheduledAt());
        int duration = appointment.getDurationMinutes() != null ? appointment.getDurationMinutes() : 30;
        Instant end = start.plus(Duration.ofMinutes(duration));
        session.setScheduledStartUtc(start);
        session.setScheduledEndUtc(end);
        session.setJoinOpensAt(start.minus(Duration.ofMinutes(callsProperties.getJoinOpensMinutesBefore())));
        session.setJoinClosesAt(end.plus(Duration.ofMinutes(callsProperties.getJoinClosesMinutesAfterEnd())));
    }

    /** When the room for this schedule should be created: max(now, opens - 5 min). */
    static Instant createRoomDueAt(CallSession session, Instant now) {
        Instant due = session.getJoinOpensAt() == null ? now : session.getJoinOpensAt().minus(CREATE_ROOM_LEAD);
        return due.isAfter(now) ? due : now;
    }

    public static boolean isWindowOpen(CallSession session, Instant now) {
        return session.getJoinOpensAt() != null && session.getJoinClosesAt() != null
                && !now.isBefore(session.getJoinOpensAt()) && !now.isAfter(session.getJoinClosesAt());
    }

    // ==================== APPOINTMENT SYNC ====================

    /**
     * Apply an appointment change to its call session. Runs inside the appointment transaction (BEFORE_COMMIT).
     */
    public void syncWithAppointment(Integer appointmentId, AppointmentCallRelevantChangeEvent.Kind kind) {
        Appointment appointment = appointmentRepository.findById(appointmentId).orElse(null);
        if (appointment == null || kind == null) {
            return;
        }
        CallSession session = callSessionRepository.findForUpdateByAppointmentId(appointmentId).orElse(null);
        boolean video = VIDEO.equals(appointment.getType());
        boolean cancelled = "CANCELLED".equals(appointment.getStatus());

        switch (kind) {
            case CREATED_VIDEO -> {
                if (video && !cancelled && (session == null || isFinal(session))) {
                    prepare(session, appointment);
                }
            }
            case CANCELLED -> {
                if (session != null && !isFinal(session)) {
                    terminate(session, CallSession.CANCELLED, CallSession.END_APPOINTMENT_CANCELLED,
                            CallParticipant.REASON_APPOINTMENT_CANCELLED, null);
                }
            }
            case RESCHEDULED -> {
                if (!video || cancelled) {
                    return;
                }
                if (session == null) {
                    prepare(null, appointment);
                } else {
                    reschedule(session, appointment);
                }
            }
            case DURATION_CHANGED -> {
                // Same start: keep room and participants, only move the end of the join window.
                if (video && !cancelled && session != null && !isFinal(session)
                        && !CallSession.NEEDS_TIME_CONFIRMATION.equals(session.getStatus())) {
                    applySchedule(session, appointment);
                    callSessionRepository.saveAndFlush(session);
                }
            }
            case TYPE_CHANGED -> {
                if (video && !cancelled) {
                    if (session == null || isFinal(session)) {
                        prepare(session, appointment);
                    } else {
                        reschedule(session, appointment);
                    }
                } else if (session != null && !isFinal(session)) {
                    terminate(session, CallSession.CANCELLED, CallSession.END_TYPE_CHANGED,
                            CallParticipant.REASON_APPOINTMENT_CANCELLED, null);
                }
            }
            case ENDED -> {
                // COMPLETED or NO_SHOW: a call that never went LIVE is closed now (room deleted by a job) instead of
                // waiting for the expiry sweep. A LIVE call is left to the doctor (end) or the expiry sweep.
                if (session != null && !isFinal(session) && !CallSession.LIVE.equals(session.getStatus())) {
                    terminate(session, CallSession.ENDED, CallSession.END_APPOINTMENT_ENDED,
                            CallParticipant.REASON_CALL_ENDED, null);
                }
            }
        }
    }

    /**
     * Active VIDEO appointment without a session (data inserted outside the app): create it as
     * NEEDS_TIME_CONFIRMATION. Returns the existing session otherwise.
     */
    public CallSession getOrCreateForRead(Appointment appointment) {
        CallSession existing = callSessionRepository.findByAppointmentId(appointment.getAppointmentId()).orElse(null);
        if (existing != null || !VIDEO.equals(appointment.getType())
                || !ACTIVE_APPOINTMENT_STATUSES.contains(appointment.getStatus())) {
            return existing;
        }
        CallSession session = CallSession.builder()
                .appointmentId(appointment.getAppointmentId())
                .status(CallSession.NEEDS_TIME_CONFIRMATION)
                .roomName(newRoomName())
                .build();
        return callSessionRepository.saveAndFlush(session);
    }

    /** New session, or reset of a CANCELLED/ENDED one: new room, schedule, PREPARING, CREATE_ROOM job. */
    CallSession prepare(CallSession session, Appointment appointment) {
        CallSession target = session != null ? session : CallSession.builder()
                .appointmentId(appointment.getAppointmentId())
                .build();
        if (session != null && session.roomMayExist() && !isFinal(session)) {
            callJobService.enqueueDeleteRoom(session, session.getRoomName());
        }
        resetForNewRoom(target);
        applySchedule(target, appointment);
        target.setStatus(CallSession.PREPARING);
        CallSession saved = callSessionRepository.saveAndFlush(target);
        callJobService.enqueueCreateRoom(saved, createRoomDueAt(saved, Instant.now()));
        log.info("Call session {} prepared for appointment {}", saved.getCallSessionId(), appointment.getAppointmentId());
        return saved;
    }

    /** Time changed: remove everyone, drop the old room, new room at the new window. */
    void reschedule(CallSession session, Appointment appointment) {
        removeOpenParticipants(session, CallParticipant.REASON_RESCHEDULED);
        callJobService.cancelPendingCreateRoom(session.getCallSessionId());
        if (session.roomMayExist()) {
            callJobService.enqueueDeleteRoom(session, session.getRoomName());
        }
        resetForNewRoom(session);
        applySchedule(session, appointment);
        session.setStatus(CallSession.PREPARING);
        CallSession saved = callSessionRepository.saveAndFlush(session);
        callJobService.enqueueCreateRoom(saved, createRoomDueAt(saved, Instant.now()));
        log.info("Call session {} rescheduled", saved.getCallSessionId());
    }

    /** Doctor confirmed the stored wall-clock time of a legacy appointment. */
    public void confirmTime(CallSession session, Appointment appointment, String doctorSystemId) {
        applySchedule(session, appointment);
        session.setTimeConfirmedAt(Instant.now());
        session.setTimeConfirmedBySystemId(doctorSystemId);
        session.setStatus(CallSession.PREPARING);
        session.setLastError(null);
        CallSession saved = callSessionRepository.saveAndFlush(session);
        callJobService.enqueueCreateRoom(saved, createRoomDueAt(saved, Instant.now()));
    }

    private void resetForNewRoom(CallSession session) {
        session.setRoomName(newRoomName());
        session.setRoomCreatedAt(null);
        session.setStartedAt(null);
        session.setStartedBySystemId(null);
        session.setEndedAt(null);
        session.setEndedBySystemId(null);
        session.setEndReason(null);
        session.setLastError(null);
        session.setTimeConfirmedAt(null);
        session.setTimeConfirmedBySystemId(null);
    }

    // ==================== ENDING ====================

    /**
     * End a session for good (ENDED or CANCELLED): every open participant REMOVED with {@code participantReason},
     * pending CREATE_ROOM cancelled, DELETE_ROOM when a room may exist. Appointment status is untouched.
     */
    public void terminate(CallSession session, String finalStatus, String endReason, String participantReason,
                          String endedBySystemId) {
        removeOpenParticipants(session, participantReason);
        callJobService.cancelPendingCreateRoom(session.getCallSessionId());
        if (session.roomMayExist()) {
            callJobService.enqueueDeleteRoom(session, session.getRoomName());
        }
        session.setStatus(finalStatus);
        session.setEndReason(endReason);
        session.setEndedAt(Instant.now());
        session.setEndedBySystemId(endedBySystemId);
        callSessionRepository.save(session);
        log.info("Call session {} -> {} ({})", session.getCallSessionId(), finalStatus, endReason);
    }

    /** Sweeper: windows that closed end as EXPIRED. Returns true when the session was expired. */
    public boolean expireIfClosed(Long sessionId, Instant now) {
        CallSession session = callSessionRepository.findForUpdateById(sessionId).orElse(null);
        if (session == null || !CallSession.SCHEDULED_STATUSES.contains(session.getStatus())
                || session.getJoinClosesAt() == null || !session.getJoinClosesAt().isBefore(now)) {
            return false;
        }
        terminate(session, CallSession.ENDED, CallSession.END_EXPIRED, CallParticipant.REASON_EXPIRED, null);
        return true;
    }

    /** Sweeper: re-validate ADMITTED caregivers of a LIVE session (covers grant expiry). Returns removals. */
    public int revalidateCaregivers(Long sessionId) {
        CallSession session = callSessionRepository.findForUpdateById(sessionId).orElse(null);
        if (session == null || !CallSession.LIVE.equals(session.getStatus())) {
            return 0;
        }
        Appointment appointment = appointmentRepository.findById(session.getAppointmentId()).orElse(null);
        if (appointment == null) {
            return 0;
        }
        int removed = 0;
        for (CallParticipant p : callParticipantRepository.findByCallSessionIdAndStatusIn(sessionId,
                CallParticipant.OPEN_STATUSES)) {
            if (AuthorizationService.CAREGIVER.equals(p.getParticipantRole())
                    && !authorizationService.hasCaregiverPermission(p.getSystemId(), appointment.getPatientSystemId(),
                    PatientPermission.JOIN_CALLS)) {
                removeParticipant(session, p, CallParticipant.REASON_ACCESS_REVOKED);
                removed++;
            }
        }
        return removed;
    }

    // ==================== PARTICIPANT REMOVAL ====================

    public void removeOpenParticipants(CallSession session, String reason) {
        List<CallParticipant> open = callParticipantRepository.findByCallSessionIdAndStatusIn(
                session.getCallSessionId(), CallParticipant.OPEN_STATUSES);
        for (CallParticipant p : open) {
            removeParticipant(session, p, reason);
        }
    }

    /**
     * REMOVED with removed_at = revoked_at = now. If it was ADMITTED (or the provider saw it connected):
     * removal_pending = true and a REMOVE_PARTICIPANT job; the job's success clears removal_pending.
     */
    public void removeParticipant(CallSession session, CallParticipant participant, String reason) {
        if (!participant.isOpen()) {
            return;
        }
        boolean wasAdmitted = CallParticipant.ADMITTED.equals(participant.getStatus());
        Instant now = Instant.now();
        participant.setStatus(CallParticipant.REMOVED);
        participant.setRemovedAt(now);
        participant.setRevokedAt(now);
        participant.setRemovalReason(reason);
        boolean mayBeConnected = wasAdmitted || participant.getProviderJoinedAt() != null
                || participant.getLastTokenIssuedAt() != null;
        if (mayBeConnected) {
            participant.setRemovalPending(true);
        }
        CallParticipant saved = callParticipantRepository.save(participant);
        if (mayBeConnected) {
            callJobService.enqueueRemoveParticipant(session, saved);
        }
    }

    /**
     * Removes the user's open rows (all their calls, or only calls of one patient when patientSystemId != null).
     * Locks each session first (same order as the request paths), then reads the participant fresh.
     */
    public int removeUserFromOpenCalls(String systemId, String patientSystemId, String reason) {
        List<Object[]> rows = patientSystemId == null
                ? callParticipantRepository.findIdsForUser(systemId, CallParticipant.OPEN_STATUSES)
                : callParticipantRepository.findIdsForUserInPatientCalls(systemId, patientSystemId,
                CallParticipant.OPEN_STATUSES);
        int removed = 0;
        for (Object[] row : rows) {
            Long participantId = (Long) row[0];
            Long sessionId = (Long) row[1];
            CallSession session = callSessionRepository.findForUpdateById(sessionId).orElse(null);
            CallParticipant current = session == null ? null
                    : callParticipantRepository.findById(participantId).orElse(null);
            if (current != null && current.isOpen()) {
                removeParticipant(session, current, reason);
                removed++;
            }
        }
        return removed;
    }

    private static boolean isFinal(CallSession session) {
        return CallSession.FINAL_STATUSES.contains(session.getStatus());
    }
}
