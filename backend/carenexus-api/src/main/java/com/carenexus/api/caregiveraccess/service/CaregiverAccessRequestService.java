package com.carenexus.api.caregiveraccess.service;

import com.carenexus.api.caregiveraccess.dto.request.ApproveAccessRequestRequest;
import com.carenexus.api.caregiveraccess.dto.request.CreateAccessRequestRequest;
import com.carenexus.api.caregiveraccess.dto.request.DeclineAccessRequestRequest;
import com.carenexus.api.caregiveraccess.dto.response.CaregiverAccessRequestResponse;
import com.carenexus.api.caregiveraccess.model.CaregiverAccessRequest;
import com.carenexus.api.caregiveraccess.repository.AccessGrantRepository;
import com.carenexus.api.caregiveraccess.repository.AccessPeopleRepository;
import com.carenexus.api.caregiveraccess.repository.AccessPeopleRepository.Person;
import com.carenexus.api.caregiveraccess.repository.CaregiverAccessRequestRepository;
import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.core.dto.request.GrantCaregiverAccessRequest;
import com.carenexus.api.core.dto.response.CaregiverDependentResponse;
import com.carenexus.api.core.model.DependentAccess;
import com.carenexus.api.core.service.CaregiverService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Caregiver access requests (US-14): a caregiver asks an existing patient for access by email, the patient approves
 * (deciding the final permissions) or declines, the caregiver may cancel while it is pending, and the daily job
 * expires what nobody answered within {@value CaregiverAccessRequest#PENDING_DAYS} days.
 *
 * State machine (the stored status is the truth; every status other than PENDING is terminal):
 * <pre>
 * PENDING -> APPROVED   patient approves (grant created, or a revoked grant reused, by CaregiverService)
 * PENDING -> DECLINED   patient declines
 * PENDING -> CANCELLED  caregiver cancels (or account clean-up, status-only native UPDATE)
 * PENDING -> EXPIRED    daily job
 * same status again     no-op answering the current state; any other move out of a terminal status: 409
 * </pre>
 *
 * Privacy: creating a request always answers the same way whether or not the email belongs to a patient, a request
 * for an unknown email is stored like any other, and the caregiver's view never shows the patient's system id or
 * name before the request is APPROVED. The single email-dependent answer is the 409 for a caregiver who already
 * holds an unrevoked grant from that patient, which reveals nothing new.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CaregiverAccessRequestService {

    static final String RESOURCE = "ACCESS_REQUEST";

    static final String NOT_A_CAREGIVER_MESSAGE = "Only proxy/caregiver accounts can request access";
    static final String ALREADY_LINKED_MESSAGE =
            "You are already linked to this patient. Ask them to change or extend your access.";
    static final String NOT_FOUND_MESSAGE = "Access request not found";
    static final String NOT_YOURS_MESSAGE = "Not authorized to act on this access request";
    static final String ONLY_PENDING_CANCEL_MESSAGE = "Only a pending request can be cancelled";
    static final String ALREADY_DECLINED_MESSAGE = "This request has already been declined";
    static final String CANCELLED_MESSAGE = "This request was cancelled by the caregiver";
    static final String EXPIRED_MESSAGE = "This request has expired";
    static final String ALREADY_APPROVED_MESSAGE =
            "This request has already been approved. Remove the caregiver's access instead.";
    static final String CAREGIVER_INACTIVE_MESSAGE =
            "This caregiver's account is no longer active. Decline the request instead.";
    static final String INVALID_STATUS_MESSAGE =
            "Invalid status. Must be PENDING, APPROVED, DECLINED, CANCELLED or EXPIRED";

    static final String FULL_ACCESS = "FULL_ACCESS";
    static final String VIEW_ONLY = "VIEW_ONLY";
    private static final Set<String> STATUSES = Set.of(CaregiverAccessRequest.STATUS_PENDING,
            CaregiverAccessRequest.STATUS_APPROVED, CaregiverAccessRequest.STATUS_DECLINED,
            CaregiverAccessRequest.STATUS_CANCELLED, CaregiverAccessRequest.STATUS_EXPIRED);

    private final CaregiverAccessRequestRepository requestRepository;
    private final AccessPeopleRepository peopleRepository;
    private final AccessGrantRepository accessGrantRepository;
    private final AccessPeople people;
    private final AccessNotifications notifications;
    private final CaregiverService caregiverService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final Clock clock;

    // ==================== CAREGIVER SIDE ====================

    /**
     * The caller (CAREGIVER with a caregiver profile) asks the patient with this email for access. Answers nothing:
     * the controller always returns the same 202 body.
     * <ul>
     *   <li>matching patient (active PATIENT account with that email and a patient profile) and the caller already
     *       holds an unrevoked grant from them: 409;</li>
     *   <li>a pending request of the caller for that patient or that email exists: nothing is stored or notified
     *       (the first request wins), except that a pending request stored for an email that had no account yet is
     *       now addressed to the patient who has it, and that patient is notified;</li>
     *   <li>a pending request for that email that is addressed to another patient (the email has since moved to a
     *       different account) is cancelled, and a new request is stored for the patient who now has the email;</li>
     *   <li>otherwise a PENDING row is stored (with or without a patient), audited CREATE ACCESS_REQUEST, and a
     *       matching patient is notified.</li>
     * </ul>
     * The caller's caregivers row is locked for the transaction, so two concurrent submissions cannot both insert.
     */
    @Transactional
    public void requestAccess(CreateAccessRequestRequest body) {
        authorizationService.requireRole(AuthorizationService.CAREGIVER);
        String caregiverSystemId = authorizationService.currentSystemId();
        if (peopleRepository.lockCaregiverProfile(caregiverSystemId).isEmpty()) {
            throw new AuthorizationException(NOT_A_CAREGIVER_MESSAGE, RESOURCE, null);
        }
        String email = body.getPatientEmail().trim().toLowerCase(Locale.ROOT);
        String patientSystemId = peopleRepository.findActivePatientIdsByEmail(email).stream().findFirst().orElse(null);

        if (patientSystemId != null) {
            boolean linked = accessGrantRepository
                    .findFirstByCaregiverSystemIdAndPatientSystemId(caregiverSystemId, patientSystemId)
                    .filter(CaregiverAccessRequestService::isUnrevoked)
                    .isPresent();
            if (linked) {
                throw new ConflictException(ALREADY_LINKED_MESSAGE);
            }
            if (requestRepository.findPendingForPatient(caregiverSystemId, patientSystemId).isPresent()) {
                return;
            }
        }
        Optional<CaregiverAccessRequest> pendingForEmail = requestRepository.findPendingForEmail(caregiverSystemId, email);
        if (pendingForEmail.isPresent()) {
            CaregiverAccessRequest pending = pendingForEmail.get();
            boolean addressedToSomeoneElse = patientSystemId != null && pending.getPatientSystemId() != null
                    && !patientSystemId.equals(pending.getPatientSystemId());
            if (!addressedToSomeoneElse) {
                if (patientSystemId != null && pending.getPatientSystemId() == null) {
                    // Stored before the account existed: the patient who now has this email gets to answer it.
                    pending.setPatientSystemId(patientSystemId);
                    requestRepository.save(pending);
                    notifications.requestReceived(pending);
                }
                return;
            }
            // The email has moved to another patient since the request was stored: the old patient must not keep
            // answering a request the caregiver addressed to this email. It is cancelled (status only, nobody is
            // notified) and a new request for the patient who now holds the email is stored below. Flushed now, so
            // the one-pending-per-email index sees the cancellation before the insert.
            pending.setStatus(CaregiverAccessRequest.STATUS_CANCELLED);
            pending.setRespondedAt(LocalDateTime.now(clock));
            requestRepository.saveAndFlush(pending);
            auditService.logClinicalAccess("UPDATE", RESOURCE, pending.getRequestId(), pending.getPatientSystemId());
        }

        CaregiverAccessRequest request = CaregiverAccessRequest.builder()
                .caregiverSystemId(caregiverSystemId)
                .patientSystemId(patientSystemId)
                .patientEmail(email)
                .relationship(body.getRelationship().trim())
                .accessLevel(body.getAccessLevel() == null ? VIEW_ONLY : body.getAccessLevel())
                .canViewRecords(isTrue(body.getCanViewRecords()))
                .canViewAppointments(isTrue(body.getCanViewAppointments()))
                .canViewMedications(isTrue(body.getCanViewMedications()))
                .canViewLabResults(isTrue(body.getCanViewLabResults()))
                .canViewImaging(isTrue(body.getCanViewImaging()))
                .canViewConsultations(isTrue(body.getCanViewConsultations()))
                .canViewBilling(isTrue(body.getCanViewBilling()))
                .canScheduleAppointments(isTrue(body.getCanScheduleAppointments()))
                .canManageCarePlan(isTrue(body.getCanManageCarePlan()))
                .canManageBilling(isTrue(body.getCanManageBilling()))
                .canMessageCareTeam(isTrue(body.getCanMessageCareTeam()))
                .canJoinCalls(isTrue(body.getCanJoinCalls()))
                .message(body.getMessage())
                .status(CaregiverAccessRequest.STATUS_PENDING)
                .createdAt(LocalDateTime.now(clock))
                .build();
        normalizeFlags(request);
        CaregiverAccessRequest saved = requestRepository.save(request);
        auditService.logClinicalAccess("CREATE", RESOURCE, saved.getRequestId(), patientSystemId);
        if (patientSystemId != null) {
            notifications.requestReceived(saved);
        }
    }

    /**
     * The caller's own requests in every status (or one), newest first. Patient id and name only once APPROVED.
     * Audited as one READ ACCESS_REQUEST row without a patient when the page is not empty.
     */
    public PageResponse<CaregiverAccessRequestResponse> listForCaregiver(String status, Integer page, Integer size) {
        authorizationService.requireRole(AuthorizationService.CAREGIVER);
        String caregiverSystemId = authorizationService.currentSystemId();
        Page<CaregiverAccessRequest> rows = requestRepository.findForCaregiver(caregiverSystemId, parseStatus(status),
                PageParams.of(page, size, Sort.unsorted()));
        Map<String, Person> patients = people.byId(rows.getContent().stream()
                .filter(r -> CaregiverAccessRequest.STATUS_APPROVED.equals(r.getStatus()))
                .map(CaregiverAccessRequest::getPatientSystemId)
                .toList());
        List<CaregiverAccessRequestResponse> content = rows.getContent().stream()
                .map(r -> caregiverView(r, patients.get(r.getPatientSystemId())))
                .toList();
        if (!content.isEmpty()) {
            auditService.logClinicalAccess("READ", RESOURCE, null, null);
        }
        return PageResponse.of(rows, content);
    }

    /**
     * The caller cancels their own PENDING request (the patient is not notified). CANCELLED again is a no-op;
     * APPROVED, DECLINED or EXPIRED gives 409. Audited DELETE ACCESS_REQUEST on the first cancel only.
     */
    @Transactional
    public void cancel(Integer requestId) {
        authorizationService.requireRole(AuthorizationService.CAREGIVER);
        String caregiverSystemId = authorizationService.currentSystemId();
        CaregiverAccessRequest request = requestRepository.findForUpdate(requestId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_MESSAGE));
        if (!caregiverSystemId.equals(request.getCaregiverSystemId())) {
            throw new AuthorizationException(NOT_YOURS_MESSAGE, RESOURCE, requestId);
        }
        switch (request.getStatus()) {
            case CaregiverAccessRequest.STATUS_CANCELLED -> {
                return;
            }
            case CaregiverAccessRequest.STATUS_PENDING -> {
                request.setStatus(CaregiverAccessRequest.STATUS_CANCELLED);
                request.setRespondedAt(LocalDateTime.now(clock));
                requestRepository.save(request);
                auditService.logClinicalAccess("DELETE", RESOURCE, requestId, request.getPatientSystemId());
            }
            default -> throw new ConflictException(ONLY_PENDING_CANCEL_MESSAGE);
        }
    }

    // ==================== PATIENT SIDE ====================

    /**
     * The requests addressed to the caller (PATIENT only), every status or one, newest first. Audited as one READ
     * ACCESS_REQUEST row about the caller when the page is not empty.
     */
    public PageResponse<CaregiverAccessRequestResponse> listForPatient(String status, Integer page, Integer size) {
        authorizationService.requireRole(AuthorizationService.PATIENT);
        String patientSystemId = authorizationService.currentSystemId();
        Page<CaregiverAccessRequest> rows = requestRepository.findForPatient(patientSystemId, parseStatus(status),
                PageParams.of(page, size, Sort.unsorted()));
        Map<String, Person> caregivers = people.byId(rows.getContent().stream()
                .map(CaregiverAccessRequest::getCaregiverSystemId).toList());
        List<CaregiverAccessRequestResponse> content = rows.getContent().stream()
                .map(r -> patientView(r, caregivers.get(r.getCaregiverSystemId())))
                .toList();
        if (!content.isEmpty()) {
            auditService.logClinicalAccess("READ", RESOURCE, null, patientSystemId);
        }
        return PageResponse.of(rows, content);
    }

    /**
     * The patient approves a request addressed to them with the permissions in the body (authoritative; only the
     * relationship comes from the request). The grant is created, or a revoked grant reused, by
     * {@link CaregiverService#grantCaregiverAccessAsPatient} in this transaction; its consent-ledger row is written
     * by the listener, which skips the ACCESS_CHANGE "Access granted" notification for this grant because the
     * caregiver gets the "Access request approved" notification instead. APPROVED again answers the current state.
     *
     * Checked in this order: 404, 403 (another patient's request), 409 for DECLINED / CANCELLED / EXPIRED, 409 for
     * an inactive caregiver account, 400 for a bad expiry, 409 for an existing unrevoked grant. After any error the
     * request is unchanged.
     */
    @Transactional
    public CaregiverAccessRequestResponse approve(Integer requestId, ApproveAccessRequestRequest body) {
        authorizationService.requireRole(AuthorizationService.PATIENT);
        String patientSystemId = authorizationService.currentSystemId();
        CaregiverAccessRequest request = loadForPatient(patientSystemId, requestId);
        switch (request.getStatus()) {
            case CaregiverAccessRequest.STATUS_APPROVED -> {
                return patientView(request);
            }
            case CaregiverAccessRequest.STATUS_DECLINED -> throw new ConflictException(ALREADY_DECLINED_MESSAGE);
            case CaregiverAccessRequest.STATUS_CANCELLED -> throw new ConflictException(CANCELLED_MESSAGE);
            case CaregiverAccessRequest.STATUS_EXPIRED -> throw new ConflictException(EXPIRED_MESSAGE);
            default -> {
                // PENDING: decided below
            }
        }

        String caregiverSystemId = request.getCaregiverSystemId();
        Person caregiver = people.byId(List.of(caregiverSystemId)).get(caregiverSystemId);
        if (caregiver == null || caregiver.getEmail() == null || caregiver.getEmail().isBlank()
                || !peopleRepository.isActiveCaregiver(caregiverSystemId)) {
            throw new ConflictException(CAREGIVER_INACTIVE_MESSAGE);
        }

        GrantCaregiverAccessRequest grant = GrantCaregiverAccessRequest.builder()
                .caregiverEmail(caregiver.getEmail().trim().toLowerCase(Locale.ROOT))
                .relationship(request.getRelationship())
                .accessLevel(body.getAccessLevel())
                .expiresAt(body.getExpiresAt())
                .canViewRecords(isTrue(body.getCanViewRecords()))
                .canViewAppointments(isTrue(body.getCanViewAppointments()))
                .canViewMedications(isTrue(body.getCanViewMedications()))
                .canViewLabResults(isTrue(body.getCanViewLabResults()))
                .canViewImaging(isTrue(body.getCanViewImaging()))
                .canViewConsultations(isTrue(body.getCanViewConsultations()))
                .canViewBilling(isTrue(body.getCanViewBilling()))
                .canScheduleAppointments(isTrue(body.getCanScheduleAppointments()))
                .canManageCarePlan(isTrue(body.getCanManageCarePlan()))
                .canManageBilling(isTrue(body.getCanManageBilling()))
                .canMessageCareTeam(isTrue(body.getCanMessageCareTeam()))
                .canJoinCalls(isTrue(body.getCanJoinCalls()))
                .build();
        CaregiverDependentResponse granted = ApprovalInProgress.during(caregiverSystemId, patientSystemId,
                () -> caregiverService.grantCaregiverAccessAsPatient(patientSystemId, grant));

        request.setStatus(CaregiverAccessRequest.STATUS_APPROVED);
        request.setAccessId(granted.getAccessId());
        request.setRespondedAt(LocalDateTime.now(clock));
        CaregiverAccessRequest saved = requestRepository.save(request);
        auditService.logClinicalAccess("UPDATE", RESOURCE, requestId, patientSystemId);
        notifications.requestApproved(saved);
        return patientView(saved, caregiver);
    }

    /**
     * The patient declines a request addressed to them, with an optional reason shown to the caregiver.
     * DECLINED again answers the current state (the first reason is kept); APPROVED, CANCELLED or EXPIRED give 409.
     * Audited UPDATE ACCESS_REQUEST; the caregiver is notified.
     */
    @Transactional
    public CaregiverAccessRequestResponse decline(Integer requestId, DeclineAccessRequestRequest body) {
        authorizationService.requireRole(AuthorizationService.PATIENT);
        String patientSystemId = authorizationService.currentSystemId();
        CaregiverAccessRequest request = loadForPatient(patientSystemId, requestId);
        switch (request.getStatus()) {
            case CaregiverAccessRequest.STATUS_DECLINED -> {
                return patientView(request);
            }
            case CaregiverAccessRequest.STATUS_APPROVED -> throw new ConflictException(ALREADY_APPROVED_MESSAGE);
            case CaregiverAccessRequest.STATUS_CANCELLED -> throw new ConflictException(CANCELLED_MESSAGE);
            case CaregiverAccessRequest.STATUS_EXPIRED -> throw new ConflictException(EXPIRED_MESSAGE);
            default -> {
                // PENDING: decided below
            }
        }
        String reason = body == null || body.getReason() == null || body.getReason().isBlank()
                ? null : body.getReason().trim();
        request.setStatus(CaregiverAccessRequest.STATUS_DECLINED);
        request.setDeclineReason(reason);
        request.setRespondedAt(LocalDateTime.now(clock));
        CaregiverAccessRequest saved = requestRepository.save(request);
        auditService.logClinicalAccess("UPDATE", RESOURCE, requestId, patientSystemId);
        notifications.requestDeclined(saved);
        return patientView(saved);
    }

    // ==================== HELPERS ====================

    /** 404 for an unknown id, 403 for a request addressed to someone else (or to no account). Row-locked. */
    private CaregiverAccessRequest loadForPatient(String patientSystemId, Integer requestId) {
        CaregiverAccessRequest request = requestRepository.findForUpdate(requestId)
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_MESSAGE));
        if (!patientSystemId.equals(request.getPatientSystemId())) {
            throw new AuthorizationException(NOT_YOURS_MESSAGE, RESOURCE, requestId);
        }
        return request;
    }

    /** Status filter: blank = every status; otherwise trimmed, upper-cased and one of the five values. */
    static String parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String status = raw.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) {
            throw new BadRequestException(INVALID_STATUS_MESSAGE);
        }
        return status;
    }

    /** A grant that still binds the pair: not revoked (active or expired). */
    static boolean isUnrevoked(DependentAccess grant) {
        return grant.getRevokedAt() == null && Boolean.TRUE.equals(grant.getIsActive());
    }

    /**
     * The same rule as the grant: FULL_ACCESS stores all ten data flags true, VIEW_ONLY stores the three write flags
     * false; EDIT_ONLY and CUSTOM keep them as sent; the communication flags are never touched.
     */
    static void normalizeFlags(CaregiverAccessRequest request) {
        if (FULL_ACCESS.equals(request.getAccessLevel())) {
            request.setCanViewRecords(true);
            request.setCanViewAppointments(true);
            request.setCanViewMedications(true);
            request.setCanViewLabResults(true);
            request.setCanViewImaging(true);
            request.setCanViewConsultations(true);
            request.setCanViewBilling(true);
            request.setCanScheduleAppointments(true);
            request.setCanManageCarePlan(true);
            request.setCanManageBilling(true);
        } else if (VIEW_ONLY.equals(request.getAccessLevel())) {
            request.setCanScheduleAppointments(false);
            request.setCanManageCarePlan(false);
            request.setCanManageBilling(false);
        }
    }

    private static boolean isTrue(Boolean value) {
        return Boolean.TRUE.equals(value);
    }

    private CaregiverAccessRequestResponse patientView(CaregiverAccessRequest request) {
        return patientView(request, people.byId(List.of(request.getCaregiverSystemId())).get(request.getCaregiverSystemId()));
    }

    /** Patient view: the caregiver's name and email; the patient's own name is not repeated. */
    private static CaregiverAccessRequestResponse patientView(CaregiverAccessRequest request, Person caregiver) {
        return base(request)
                .caregiverName(AccessPeople.fullName(caregiver))
                .caregiverEmail(caregiver == null ? null : caregiver.getEmail())
                .patientSystemId(request.getPatientSystemId())
                .patientName(null)
                .build();
    }

    /** Caregiver view: no caregiver name or email; the patient's id and name only once APPROVED. */
    private static CaregiverAccessRequestResponse caregiverView(CaregiverAccessRequest request, Person patient) {
        boolean approved = CaregiverAccessRequest.STATUS_APPROVED.equals(request.getStatus());
        return base(request)
                .caregiverName(null)
                .caregiverEmail(null)
                .patientSystemId(approved ? request.getPatientSystemId() : null)
                .patientName(approved ? AccessPeople.fullName(patient) : null)
                .build();
    }

    private static CaregiverAccessRequestResponse.CaregiverAccessRequestResponseBuilder base(CaregiverAccessRequest r) {
        return CaregiverAccessRequestResponse.builder()
                .requestId(r.getRequestId())
                .status(r.getStatus())
                .caregiverSystemId(r.getCaregiverSystemId())
                .patientEmail(r.getPatientEmail())
                .relationship(r.getRelationship())
                .accessLevel(r.getAccessLevel())
                .canViewRecords(r.getCanViewRecords())
                .canViewAppointments(r.getCanViewAppointments())
                .canViewMedications(r.getCanViewMedications())
                .canViewLabResults(r.getCanViewLabResults())
                .canViewImaging(r.getCanViewImaging())
                .canViewConsultations(r.getCanViewConsultations())
                .canViewBilling(r.getCanViewBilling())
                .canScheduleAppointments(r.getCanScheduleAppointments())
                .canManageCarePlan(r.getCanManageCarePlan())
                .canManageBilling(r.getCanManageBilling())
                .canMessageCareTeam(r.getCanMessageCareTeam())
                .canJoinCalls(r.getCanJoinCalls())
                .message(r.getMessage())
                .declineReason(r.getDeclineReason())
                .accessId(CaregiverAccessRequest.STATUS_APPROVED.equals(r.getStatus()) ? r.getAccessId() : null)
                .createdAt(r.getCreatedAt())
                .requestExpiresAt(r.requestExpiresAt())
                .respondedAt(r.getRespondedAt());
    }
}
