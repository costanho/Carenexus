package com.carenexus.api.referrals.service;

import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.dto.ValidationPatterns;
import com.carenexus.api.common.event.ReferralCreatedEvent;
import com.carenexus.api.common.event.ReferralStatusChangedEvent;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.communications.notify.NotificationCategory;
import com.carenexus.api.communications.notify.NotificationCommand;
import com.carenexus.api.communications.notify.NotificationPriority;
import com.carenexus.api.communications.notify.NotificationPublisher;
import com.carenexus.api.communications.notify.NotificationTarget;
import com.carenexus.api.communications.notify.NotificationType;
import com.carenexus.api.core.service.ConsultationService;
import com.carenexus.api.referrals.dto.request.AssignReferralSpecialistRequest;
import com.carenexus.api.referrals.dto.request.CreateReferralRequest;
import com.carenexus.api.referrals.dto.request.DeclineReferralRequest;
import com.carenexus.api.referrals.dto.request.ReferralOutcomeRequest;
import com.carenexus.api.referrals.dto.response.ReferralResponse;
import com.carenexus.api.referrals.model.Referral;
import com.carenexus.api.referrals.repository.ReferralLookupRepository;
import com.carenexus.api.referrals.repository.ReferralRepository;
import com.carenexus.api.referrals.service.ReferralRules.Responder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Referrals of patients to specialists (F10, US-13).
 *
 * <ul>
 *   <li>Create: a treating doctor of the patient ({@link ConsultationService#requireTreatingDoctor}). The specialist
 *       is optional: a platform doctor (active, approved, not the caller), a specialist outside the platform
 *       (name, organisation, contact), or chosen later through {@link #assignSpecialist}.</li>
 *   <li>Read: the referring doctor, the named platform specialist, or anyone passing
 *       {@code requirePatientAccess(patient, REFERRALS)} (patient, related doctor, admin, caregiver with
 *       can_view_records or FULL_ACCESS).</li>
 *   <li>Workflow ({@link ReferralRules}): PENDING to ACCEPTED, DECLINED or CANCELLED; ACCEPTED to COMPLETED; a request
 *       for the current status is a no-op; anything else is 409. A referral without a platform specialist is
 *       decided by its referring doctor on the specialist's behalf (note required), so it never stays stuck.</li>
 *   <li>Acceptance with a platform specialist gives that specialist an active SPECIALIST care_team row, so they
 *       pass {@code requirePatientAccess} for the patient from then on.</li>
 * </ul>
 * Every change is audited (resource REFERRAL), publishes {@link ReferralCreatedEvent} or
 * {@link ReferralStatusChangedEvent}, and notifies the people concerned (type and category REFERRAL).
 * Timestamps are clinic wall-clock times from the injected {@link Clock}.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ReferralService {

    public static final String RESOURCE = "REFERRAL";

    /** Fixed list order: newest first, ties by id. */
    static final Sort LIST_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("referralId"));

    static final String NOT_FOUND = "Referral not found";
    static final String NOTE_REQUIRED = "A note is required when you record the outcome on the specialist's behalf";
    static final String CHOOSE_ONE = "Choose a platform specialist or enter an external specialist's name";
    static final String NOT_BOTH = "Choose a platform specialist or an external specialist, not both";
    static final String NAME_REQUIRED = "externalSpecialistName: is required when an organisation or contact is given";
    static final String SAME_DOCTOR = "The specialist must be a different doctor from the referring doctor";
    static final String SPECIALIST_NOT_FOUND = "Specialist not found";
    static final String SPECIALIST_UNAVAILABLE = "This specialist is not available for referrals";
    static final String PRIORITY_INVALID = "priority: must be one of ROUTINE, URGENT, EMERGENCY";
    static final String STATUS_INVALID = "status: must be one of PENDING, ACCEPTED, DECLINED, COMPLETED, CANCELLED";
    static final String PATIENT_ID_INVALID = "patientSystemId: must be a valid system id";
    static final String ONLY_REFERRING_CANCEL = "Only the referring doctor can cancel this referral";
    static final String ONLY_REFERRING_ASSIGN = "Only the referring doctor can change the specialist";
    static final String ASSIGN_NOT_PENDING = "The specialist can only be changed while the referral is pending";

    private static final Pattern SYSTEM_ID = Pattern.compile(ValidationPatterns.SYSTEM_ID);
    private static final Pattern DOCTOR_TITLE = Pattern.compile("^dr\\b\\.?", Pattern.CASE_INSENSITIVE);

    private final ReferralRepository referralRepository;
    private final ReferralLookupRepository lookupRepository;
    private final ConsultationService consultationService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final NotificationPublisher notificationPublisher;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    // ==================== CREATE ====================

    /**
     * Creates a PENDING referral with the caller as referring doctor. Audited (CREATE), publishes
     * {@link ReferralCreatedEvent}, notifies the patient circle and a named platform specialist.
     */
    public ReferralResponse create(CreateReferralRequest request) {
        String patientSystemId = request.patientSystemId();
        String doctorSystemId = consultationService.requireTreatingDoctor(
                patientSystemId, request.consultationId(), PatientPermission.REFERRALS);

        String priority = parsePriority(request.priority());
        String specialistSystemId = request.specialistSystemId();
        validateSpecialistChoice(specialistSystemId, request.externalSpecialistName(),
                request.externalSpecialistOrganisation(), request.externalSpecialistContact(), false);
        if (specialistSystemId != null) {
            if (specialistSystemId.equals(doctorSystemId)) {
                throw new BadRequestException(SAME_DOCTOR);
            }
            requireAvailableSpecialist(specialistSystemId);
        }

        LocalDateTime now = now();
        Referral referral = referralRepository.save(Referral.builder()
                .patientSystemId(patientSystemId)
                .referringDoctorSystemId(doctorSystemId)
                .specialistSystemId(specialistSystemId)
                .externalSpecialistName(request.externalSpecialistName())
                .externalSpecialistOrganisation(request.externalSpecialistName() != null
                        ? request.externalSpecialistOrganisation() : null)
                .externalSpecialistContact(request.externalSpecialistName() != null
                        ? request.externalSpecialistContact() : null)
                .reason(request.reason())
                .clinicalSummary(request.clinicalSummary())
                .priority(priority)
                .status(Referral.STATUS_PENDING)
                .consultationId(request.consultationId())
                .referralDate(now.toLocalDate())
                .createdAt(now)
                .updatedAt(now)
                .build());

        auditService.logClinicalAccess("CREATE", RESOURCE, referral.getReferralId(), patientSystemId);
        eventPublisher.publishEvent(new ReferralCreatedEvent(referral.getReferralId(), patientSystemId,
                doctorSystemId, specialistSystemId, priority));

        People people = loadPeople(List.of(referral));
        notifyCreated(referral, people, doctorSystemId);
        if (specialistSystemId != null) {
            notifySpecialistAssigned(referral, people, doctorSystemId);
        }
        return toResponse(referral, people, AuthorizationService.DOCTOR, doctorSystemId);
    }

    // ==================== READ ====================

    /** One referral, for the referring doctor, the named platform specialist or anyone with patient access. */
    @Transactional(readOnly = true)
    public ReferralResponse get(Integer referralId) {
        Referral referral = referralRepository.findById(referralId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
        String caller = authorizationService.currentSystemId();
        String role = authorizationService.currentRole();
        boolean namedDoctor = AuthorizationService.DOCTOR.equals(role)
                && (caller.equals(referral.getReferringDoctorSystemId())
                || caller.equals(referral.getSpecialistSystemId()));
        if (!namedDoctor) {
            authorizationService.requirePatientAccess(referral.getPatientSystemId(), PatientPermission.REFERRALS,
                    referralId);
        }
        auditService.logClinicalAccess("READ", RESOURCE, referralId, referral.getPatientSystemId());
        return toResponse(referral, loadPeople(List.of(referral)), role, caller);
    }

    /** Referrals the calling doctor issued, newest first. */
    @Transactional(readOnly = true)
    public PageResponse<ReferralResponse> listOutgoing(String status, String patientSystemId, int page, int size) {
        authorizationService.requireRole(AuthorizationService.DOCTOR);
        String caller = authorizationService.currentSystemId();
        Page<Referral> rows = referralRepository.findOutgoing(caller, statusFilter(status),
                patientFilter(patientSystemId), pageable(page, size));
        return doctorPage(rows, caller);
    }

    /** Referrals addressed to the calling doctor as platform specialist (every status), newest first. */
    @Transactional(readOnly = true)
    public PageResponse<ReferralResponse> listIncoming(String status, String patientSystemId, int page, int size) {
        authorizationService.requireRole(AuthorizationService.DOCTOR);
        String caller = authorizationService.currentSystemId();
        Page<Referral> rows = referralRepository.findIncoming(caller, statusFilter(status),
                patientFilter(patientSystemId), pageable(page, size));
        return doctorPage(rows, caller);
    }

    /** A patient's referrals (every status), newest first, for anyone with patient access REFERRALS. */
    @Transactional(readOnly = true)
    public PageResponse<ReferralResponse> listForPatient(String patientSystemId, int page, int size) {
        authorizationService.requirePatientAccess(patientSystemId, PatientPermission.REFERRALS);
        String caller = authorizationService.currentSystemId();
        String role = authorizationService.currentRole();
        Page<Referral> rows = referralRepository.findByPatientSystemId(patientSystemId, pageable(page, size));
        if (!rows.isEmpty()) {
            auditService.logClinicalAccess("READ", RESOURCE, null, patientSystemId);
        }
        return PageResponse.of(rows, toResponses(rows.getContent(), role, caller));
    }

    // ==================== SPECIALIST ====================

    /**
     * Assigns or replaces the specialist of a PENDING referral (referring doctor or ADMIN). A newly assigned
     * platform specialist is notified; the previous one is not. Assigning the current platform specialist again is
     * a no-op. No event (the status does not change).
     */
    public ReferralResponse assignSpecialist(Integer referralId, AssignReferralSpecialistRequest request) {
        Referral referral = lockOrThrow(referralId);
        String caller = authorizationService.currentSystemId();
        String role = authorizationService.currentRole();
        if (!AuthorizationService.DOCTOR.equals(role) && !AuthorizationService.ADMIN.equals(role)) {
            deny(caller, referral, ReferralRules.ROLE_DENIED);
        }
        if (AuthorizationService.DOCTOR.equals(role) && !caller.equals(referral.getReferringDoctorSystemId())) {
            deny(caller, referral, ONLY_REFERRING_ASSIGN);
        }
        if (!Referral.STATUS_PENDING.equals(referral.getStatus())) {
            throw new ConflictException(ASSIGN_NOT_PENDING);
        }

        String specialistSystemId = request.specialistSystemId();
        validateSpecialistChoice(specialistSystemId, request.externalSpecialistName(),
                request.externalSpecialistOrganisation(), request.externalSpecialistContact(), true);
        if (specialistSystemId != null) {
            if (specialistSystemId.equals(referral.getReferringDoctorSystemId())) {
                throw new BadRequestException(SAME_DOCTOR);
            }
            requireAvailableSpecialist(specialistSystemId);
            if (specialistSystemId.equals(referral.getSpecialistSystemId())) {
                return toResponse(referral, loadPeople(List.of(referral)), role, caller);
            }
            referral.setSpecialistSystemId(specialistSystemId);
            referral.setExternalSpecialistName(null);
            referral.setExternalSpecialistOrganisation(null);
            referral.setExternalSpecialistContact(null);
        } else {
            referral.setSpecialistSystemId(null);
            referral.setExternalSpecialistName(request.externalSpecialistName());
            referral.setExternalSpecialistOrganisation(request.externalSpecialistOrganisation());
            referral.setExternalSpecialistContact(request.externalSpecialistContact());
        }
        referral.setUpdatedAt(now());
        referral = referralRepository.save(referral);
        auditService.logClinicalAccess("UPDATE", RESOURCE, referralId, referral.getPatientSystemId());

        People people = loadPeople(List.of(referral));
        if (specialistSystemId != null) {
            notifySpecialistAssigned(referral, people, caller);
        }
        return toResponse(referral, people, role, caller);
    }

    // ==================== WORKFLOW ====================

    /** PENDING to ACCEPTED (responder rule). A platform specialist gains a SPECIALIST care-team row. */
    public ReferralResponse accept(Integer referralId, ReferralOutcomeRequest request) {
        String note = request == null ? null : request.note();
        return decide(referralId, Referral.STATUS_ACCEPTED, "accepted", (referral, onBehalf, now, caller) -> {
            if (onBehalf && note == null) {
                throw new BadRequestException(NOTE_REQUIRED);
            }
            referral.setRespondedAt(now);
            if (note != null) {
                referral.setOutcomeNote(note);
            }
            referral.setOutcomeRecordedBySystemId(onBehalf ? caller : null);
        });
    }

    /** PENDING to DECLINED (responder rule). The reason is stored as the decline reason only. */
    public ReferralResponse decline(Integer referralId, DeclineReferralRequest request) {
        String reason = request.reason();
        return decide(referralId, Referral.STATUS_DECLINED, "declined", (referral, onBehalf, now, caller) -> {
            referral.setRespondedAt(now);
            referral.setDeclineReason(reason);
            referral.setOutcomeRecordedBySystemId(onBehalf ? caller : null);
        });
    }

    /** ACCEPTED to COMPLETED (responder rule). A note replaces the acceptance note. */
    public ReferralResponse complete(Integer referralId, ReferralOutcomeRequest request) {
        String note = request == null ? null : request.note();
        return decide(referralId, Referral.STATUS_COMPLETED, "completed", (referral, onBehalf, now, caller) -> {
            if (onBehalf && note == null) {
                throw new BadRequestException(NOTE_REQUIRED);
            }
            referral.setCompletedAt(now);
            if (note != null) {
                referral.setOutcomeNote(note);
            }
            referral.setOutcomeRecordedBySystemId(onBehalf ? caller : null);
        });
    }

    /** PENDING to CANCELLED, by the referring doctor only (not an administrator). */
    public ReferralResponse cancel(Integer referralId) {
        Referral referral = lockOrThrow(referralId);
        String caller = authorizationService.currentSystemId();
        String role = authorizationService.currentRole();
        if (!AuthorizationService.DOCTOR.equals(role)) {
            deny(caller, referral, ReferralRules.ROLE_DENIED);
        }
        if (!caller.equals(referral.getReferringDoctorSystemId())) {
            deny(caller, referral, ONLY_REFERRING_CANCEL);
        }
        return transition(referral, Referral.STATUS_CANCELLED, "cancelled", false, caller, role, null);
    }

    @FunctionalInterface
    private interface Decision {
        /** Applies the endpoint's fields after the generic checks; may throw the endpoint's own 400. */
        void apply(Referral referral, boolean onBehalf, LocalDateTime now, String caller);
    }

    /** Accept, decline and complete: responder rule, then the shared transition steps. */
    private ReferralResponse decide(Integer referralId, String target, String verb, Decision decision) {
        Referral referral = lockOrThrow(referralId);
        String caller = authorizationService.currentSystemId();
        String role = authorizationService.currentRole();
        Responder responder = ReferralRules.responder(role, caller, referral);
        if (!responder.allowed()) {
            deny(caller, referral, responder.denialMessage());
        }
        return transition(referral, target, verb, responder == Responder.ON_BEHALF, caller, role, decision);
    }

    /**
     * Processing order after the actor rule: same status is a no-op (nothing written, no event, no notification, no
     * audit); an illegal transition is 409; the endpoint's own checks; then apply, stamp, audit, event, notify.
     */
    private ReferralResponse transition(Referral referral, String target, String verb, boolean onBehalf,
                                        String caller, String role, Decision decision) {
        String previous = referral.getStatus();
        if (target.equals(previous)) {
            return toResponse(referral, loadPeople(List.of(referral)), role, caller);
        }
        if (!ReferralRules.canTransition(previous, target)) {
            throw new ConflictException(ReferralRules.illegalTransitionMessage(previous, verb));
        }
        LocalDateTime now = now();
        if (decision != null) {
            decision.apply(referral, onBehalf, now, caller);
        }
        referral.setStatus(target);
        referral.setUpdatedAt(now);
        referral = referralRepository.save(referral);

        if (Referral.STATUS_ACCEPTED.equals(target) && referral.getSpecialistSystemId() != null) {
            int inserted = lookupRepository.insertSpecialistCareTeamRow(referral.getPatientSystemId(),
                    referral.getSpecialistSystemId(), now.toLocalDate(), now);
            log.info("Referral {} accepted: specialist care-team row {}", referral.getReferralId(),
                    inserted > 0 ? "added" : "already present");
        }

        auditService.logClinicalAccess("UPDATE", RESOURCE, referral.getReferralId(), referral.getPatientSystemId());
        eventPublisher.publishEvent(new ReferralStatusChangedEvent(referral.getReferralId(),
                referral.getPatientSystemId(), referral.getReferringDoctorSystemId(),
                referral.getSpecialistSystemId(), previous, target));

        People people = loadPeople(List.of(referral));
        notifyStatusChanged(referral, people, caller);
        return toResponse(referral, people, role, caller);
    }

    // ==================== NOTIFICATIONS ====================

    private void notifyCreated(Referral referral, People people, String actor) {
        NotificationCommand template = base(referral, actor)
                .priority(priorityOf(referral))
                .title("New referral")
                .message(people.doctorName(referral.getReferringDoctorSystemId()) + " made a referral for "
                        + people.personName(referral.getPatientSystemId()) + ".")
                .dedupeKey("REFERRAL_CREATED:" + referral.getReferralId())
                .build();
        notificationPublisher.publishToPatientCircle(referral.getPatientSystemId(), PatientPermission.REFERRALS,
                template);
    }

    private void notifySpecialistAssigned(Referral referral, People people, String actor) {
        String specialist = referral.getSpecialistSystemId();
        String title = switch (referral.getPriority()) {
            case Referral.PRIORITY_EMERGENCY -> "Emergency referral received";
            case Referral.PRIORITY_URGENT -> "Urgent referral received";
            default -> "New referral received";
        };
        notificationPublisher.publish(base(referral, actor)
                .recipientSystemId(specialist)
                .priority(priorityOf(referral))
                .title(title)
                .message(people.doctorName(referral.getReferringDoctorSystemId()) + " referred "
                        + people.personName(referral.getPatientSystemId()) + " to you.")
                .dedupeKey("REFERRAL_ASSIGNED:" + referral.getReferralId() + ":" + specialist)
                .build());
    }

    /**
     * Decisions go to the referring doctor and the patient (not caregivers); a cancellation to the patient and the
     * platform specialist. The publisher skips the actor, so an outcome recorded by the referring doctor reaches
     * the patient only.
     */
    private void notifyStatusChanged(Referral referral, People people, String actor) {
        String status = referral.getStatus();
        String word = status.toLowerCase(Locale.ROOT);
        String title = "Referral " + word;
        String message = "The referral for " + people.personName(referral.getPatientSystemId()) + " to "
                + specialistLabel(referral, people) + " was " + word + ".";
        String dedupeKey = "REFERRAL_" + status + ":" + referral.getReferralId();

        Set<String> recipients = new LinkedHashSet<>();
        if (Referral.STATUS_CANCELLED.equals(status)) {
            recipients.add(referral.getPatientSystemId());
            recipients.add(referral.getSpecialistSystemId());
        } else {
            recipients.add(referral.getReferringDoctorSystemId());
            recipients.add(referral.getPatientSystemId());
        }
        recipients.remove(null);
        for (String recipient : recipients) {
            notificationPublisher.publish(base(referral, actor)
                    .recipientSystemId(recipient)
                    .title(title)
                    .message(message)
                    .dedupeKey(dedupeKey)
                    .build());
        }
    }

    private static NotificationCommand.Builder base(Referral referral, String actor) {
        return NotificationCommand.builder()
                .type(NotificationType.REFERRAL)
                .category(NotificationCategory.REFERRAL)
                .target(NotificationTarget.REFERRALS)
                .resourceType(RESOURCE)
                .resourceId(referral.getReferralId())
                .patientSystemId(referral.getPatientSystemId())
                .actorSystemId(actor);
    }

    private static NotificationPriority priorityOf(Referral referral) {
        return Referral.PRIORITY_EMERGENCY.equals(referral.getPriority())
                ? NotificationPriority.HIGH : NotificationPriority.NORMAL;
    }

    private static String specialistLabel(Referral referral, People people) {
        return switch (ReferralRules.specialistType(referral)) {
            case ReferralRules.TYPE_PLATFORM -> people.doctorName(referral.getSpecialistSystemId());
            case ReferralRules.TYPE_EXTERNAL -> referral.getExternalSpecialistName();
            default -> "a specialist";
        };
    }

    // ==================== CHECKS ====================

    private Referral lockOrThrow(Integer referralId) {
        return referralRepository.findByIdForUpdate(referralId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND));
    }

    /** Audits an ACCESS_DENIED row (own transaction) and throws 403. */
    private void deny(String caller, Referral referral, String message) {
        try {
            auditService.logAccessDenied(caller, RESOURCE, referral.getReferralId(), referral.getPatientSystemId(),
                    null, message);
        } catch (RuntimeException auditFailure) {
            log.error("Failed to write ACCESS_DENIED audit row: {}", auditFailure.getClass().getName());
        }
        throw new AuthorizationException(message, RESOURCE, referral.getReferralId());
    }

    /**
     * The specialist fields of a create or assign body. {@code requireOne}: one of the two kinds must be given
     * (assign); a create may leave the referral unassigned.
     */
    private static void validateSpecialistChoice(String specialistSystemId, String externalName,
                                                 String externalOrganisation, String externalContact,
                                                 boolean requireOne) {
        if (requireOne && specialistSystemId == null && externalName == null) {
            throw new BadRequestException(CHOOSE_ONE);
        }
        if (specialistSystemId != null && externalName != null) {
            throw new BadRequestException(NOT_BOTH);
        }
        if (externalName == null && (externalOrganisation != null || externalContact != null)) {
            throw new BadRequestException(NAME_REQUIRED);
        }
    }

    /** 404 when the id is not a doctor; 400 when that doctor is inactive or not APPROVED. */
    private void requireAvailableSpecialist(String specialistSystemId) {
        List<Object[]> rows = lookupRepository.findSpecialistCandidate(specialistSystemId);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException(SPECIALIST_NOT_FOUND);
        }
        Object[] row = rows.get(0);
        String role = (String) row[0];
        Boolean userActive = (Boolean) row[1];
        Boolean doctorActive = (Boolean) row[2];
        String verification = (String) row[3];
        if (!AuthorizationService.DOCTOR.equalsIgnoreCase(role) || doctorActive == null) {
            throw new ResourceNotFoundException(SPECIALIST_NOT_FOUND);
        }
        if (!Boolean.TRUE.equals(userActive) || !Boolean.TRUE.equals(doctorActive)
                || !"APPROVED".equals(verification)) {
            throw new BadRequestException(SPECIALIST_UNAVAILABLE);
        }
    }

    private static String parsePriority(String priority) {
        if (priority == null || priority.isBlank()) {
            return Referral.PRIORITY_ROUTINE;
        }
        String value = priority.trim().toUpperCase(Locale.ROOT);
        if (!Referral.PRIORITIES.contains(value)) {
            throw new BadRequestException(PRIORITY_INVALID);
        }
        return value;
    }

    private static String statusFilter(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String value = status.trim().toUpperCase(Locale.ROOT);
        if (!Referral.STATUSES.contains(value)) {
            throw new BadRequestException(STATUS_INVALID);
        }
        return value;
    }

    private static String patientFilter(String patientSystemId) {
        if (patientSystemId == null || patientSystemId.isBlank()) {
            return null;
        }
        String value = patientSystemId.trim();
        if (!SYSTEM_ID.matcher(value).matches()) {
            throw new BadRequestException(PATIENT_ID_INVALID);
        }
        return value;
    }

    private static Pageable pageable(int page, int size) {
        return PageParams.of(page, size, LIST_SORT);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    // ==================== MAPPING ====================

    private PageResponse<ReferralResponse> doctorPage(Page<Referral> rows, String caller) {
        auditService.logListRead(RESOURCE, rows.getContent(), Referral::getPatientSystemId);
        return PageResponse.of(rows, toResponses(rows.getContent(), AuthorizationService.DOCTOR, caller));
    }

    private List<ReferralResponse> toResponses(List<Referral> rows, String role, String caller) {
        if (rows.isEmpty()) {
            return List.of();
        }
        People people = loadPeople(rows);
        return rows.stream().map(row -> toResponse(row, people, role, caller)).toList();
    }

    private ReferralResponse toResponse(Referral referral, People people, String role, String caller) {
        String type = ReferralRules.specialistType(referral);
        boolean platform = ReferralRules.TYPE_PLATFORM.equals(type);
        String recordedBy = referral.getOutcomeRecordedBySystemId();
        return ReferralResponse.builder()
                .referralId(referral.getReferralId())
                .patientSystemId(referral.getPatientSystemId())
                .patientName(people.personName(referral.getPatientSystemId()))
                .referringDoctorSystemId(referral.getReferringDoctorSystemId())
                .referringDoctorName(people.doctorName(referral.getReferringDoctorSystemId()))
                .specialistType(type)
                .specialistSystemId(referral.getSpecialistSystemId())
                .specialistName(platform ? people.doctorName(referral.getSpecialistSystemId()) : null)
                .specialistSpecialization(platform ? people.specialization(referral.getSpecialistSystemId()) : null)
                .externalSpecialistName(referral.getExternalSpecialistName())
                .externalSpecialistOrganisation(referral.getExternalSpecialistOrganisation())
                .externalSpecialistContact(referral.getExternalSpecialistContact())
                .reason(referral.getReason())
                .clinicalSummary(referral.getClinicalSummary())
                .priority(referral.getPriority())
                .status(referral.getStatus())
                .consultationId(referral.getConsultationId())
                .referralDate(referral.getReferralDate())
                .declineReason(referral.getDeclineReason())
                .outcomeNote(referral.getOutcomeNote())
                .outcomeRecordedBySystemId(recordedBy)
                .outcomeRecordedByName(recordedBy == null ? null : people.displayName(recordedBy))
                .respondedAt(referral.getRespondedAt())
                .completedAt(referral.getCompletedAt())
                .createdAt(referral.getCreatedAt())
                .updatedAt(referral.getUpdatedAt())
                .allowedActions(ReferralRules.allowedActions(role, caller, referral))
                .outcomeNoteRequired(ReferralRules.outcomeNoteRequired(role, caller, referral))
                .build();
    }

    /** One batched lookup of every person named on the given referrals. */
    private People loadPeople(List<Referral> rows) {
        Set<String> ids = new LinkedHashSet<>();
        for (Referral row : rows) {
            ids.add(row.getPatientSystemId());
            ids.add(row.getReferringDoctorSystemId());
            ids.add(row.getSpecialistSystemId());
            ids.add(row.getOutcomeRecordedBySystemId());
        }
        ids.remove(null);
        Map<String, Person> people = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Object[] row : lookupRepository.findPeople(ids)) {
                Person person = new Person((String) row[0], (String) row[1], (String) row[2], (String) row[3],
                        (String) row[4]);
                people.put(person.systemId(), person);
            }
        }
        return new People(people);
    }

    private record Person(String systemId, String firstName, String lastName, String role, String specialization) {

        String name() {
            String first = firstName == null ? "" : firstName.trim();
            String last = lastName == null ? "" : lastName.trim();
            String full = (first + " " + last).trim();
            return full.isEmpty() ? systemId : full;
        }

        boolean isDoctor() {
            return AuthorizationService.DOCTOR.equalsIgnoreCase(role);
        }
    }

    /** Display names of the people on a set of referrals; falls back to the system id for an unknown account. */
    private record People(Map<String, Person> bySystemId) {

        /** "First Last". */
        String personName(String systemId) {
            Person person = bySystemId.get(systemId);
            return person == null ? systemId : person.name();
        }

        /** "Dr. First Last", without doubling a title already stored in the first name. */
        String doctorName(String systemId) {
            Person person = bySystemId.get(systemId);
            return person == null ? systemId : withDoctorTitle(person.name());
        }

        /** "Dr. First Last" for a doctor, "First Last" for anyone else. */
        String displayName(String systemId) {
            Person person = bySystemId.get(systemId);
            if (person == null) {
                return systemId;
            }
            return person.isDoctor() ? withDoctorTitle(person.name()) : person.name();
        }

        String specialization(String systemId) {
            Person person = bySystemId.get(systemId);
            return person == null ? null : person.specialization();
        }
    }

    static String withDoctorTitle(String name) {
        Objects.requireNonNull(name);
        return DOCTOR_TITLE.matcher(name).lookingAt() ? name : "Dr. " + name;
    }
}
