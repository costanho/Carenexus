package com.carenexus.api.core.service;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.event.ConsultationCompletedEvent;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.core.dto.request.CreateConsultationRequest;
import com.carenexus.api.core.dto.request.UpdateConsultationRequest;
import com.carenexus.api.core.dto.response.ConsultationResponse;
import com.carenexus.api.core.dto.response.ConsultationStatsResponse;
import com.carenexus.api.core.model.Appointment;
import com.carenexus.api.core.model.Consultation;
import com.carenexus.api.core.model.Doctor;
import com.carenexus.api.core.repository.AppointmentRepository;
import com.carenexus.api.core.repository.ConsultationRepository;
import com.carenexus.api.core.repository.DoctorRepository;
import com.carenexus.api.core.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Consultations. Every public method enforces access for the current caller (see AuthorizationService) and
 * writes a clinical audit row after it succeeds.
 *
 * This class also holds the shared clinical-write checks ({@link #requireTreatingDoctor},
 * {@link #requireAuthorDoctor}, {@link #requireRelatedDoctor}) used by the record, prescription, lab and
 * imaging services, so the "who may author/change clinical data" rule lives in one place.
 *
 * Lifecycle (transition table, section 3 of the consultations contract):
 * <pre>
 *   create       -> DRAFT (default) | IN_PROGRESS
 *   DRAFT        -> IN_PROGRESS | COMPLETED | CANCELLED
 *   IN_PROGRESS  -> COMPLETED | CANCELLED            (back to DRAFT: 409)
 *   COMPLETED, CANCELLED: terminal (409 for any change, content edits and deletes included)
 *   same status  -> no-op (nothing written, no event)
 * </pre>
 * Completing requires a chief complaint and a diagnosis, sets {@code completedAt} once and publishes exactly one
 * {@link ConsultationCompletedEvent} after the row is saved, inside the transaction: its listeners (appointment
 * completion, medical record, invoice) are plain {@code @EventListener}s, so a failing listener rolls the
 * completion back. Times are written with the clinic clock.
 *
 * DRAFT and IN_PROGRESS consultations are the doctor's unfinished working notes: only doctors and admins see them.
 * Patients and caregivers get finalised ones (COMPLETED, CANCELLED); an unfinished one looked up by id answers 404
 * for them.
 *
 * Concurrency: every write locks the appointment row first and then the consultation row (the same order as an
 * appointment change whose listener cancels the consultation), so two creates for one appointment are serialised
 * (the loser gets the 409 of the service check) and concurrent transitions cannot both apply. The V35 partial unique
 * index is the database safety net; its violation is also translated to 409.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ConsultationService {

    static final String RESOURCE = "CONSULTATION";
    static final String DRAFT = Consultation.STATUS_DRAFT;
    static final String IN_PROGRESS = Consultation.STATUS_IN_PROGRESS;
    static final String COMPLETED = Consultation.STATUS_COMPLETED;
    static final String CANCELLED = Consultation.STATUS_CANCELLED;

    /** Hard cap and order of the legacy array lists (rule 0.8). */
    public static final int LEGACY_LIST_CAP = 200;
    /** Hard cap of GET /api/consultations/appointment/{appointmentId}. */
    public static final int APPOINTMENT_LIST_CAP = 50;

    /** Name of the V35 partial unique index (one non-cancelled consultation per appointment). */
    static final String ACTIVE_PER_APPOINTMENT_INDEX = "ux_consultations_appointment_active";

    static final String MSG_NOT_FOUND = "Consultation not found";
    static final String MSG_ALREADY_EXISTS = "A consultation already exists for this appointment";
    static final String MSG_COMPLETED_LOCKED = "A completed consultation cannot be changed";
    static final String MSG_CANCELLED_LOCKED = "A cancelled consultation cannot be changed";
    static final String MSG_NO_BACK_TO_DRAFT = "A consultation that is in progress cannot go back to draft";
    static final String MSG_INVALID_STATUS =
            "Invalid consultation status. Must be DRAFT, IN_PROGRESS, COMPLETED, or CANCELLED";

    /** What patients and caregivers may see. */
    private static final List<String> FINAL_STATUSES = List.of(COMPLETED, CANCELLED);
    /** Statuses that count as "the" consultation of an appointment (V35 index: status <> CANCELLED). */
    private static final List<String> LIVE_STATUSES = List.of(DRAFT, IN_PROGRESS, COMPLETED);
    private static final List<String> OPEN_STATUSES = List.of(DRAFT, IN_PROGRESS);
    /** Display order of the doctor's own active list (legacy GET /api/doctors/consultations). */
    private static final List<String> ACTIVE_DISPLAY_ORDER = List.of(IN_PROGRESS, DRAFT, COMPLETED);
    private static final Set<String> APPOINTMENT_STATUSES_ALLOWING_CREATE = Set.of("SCHEDULED", "RESCHEDULED", "COMPLETED");

    private final ConsultationRepository consultationRepository;
    private final PatientRepository patientRepository;
    private final DoctorRepository doctorRepository;
    private final AppointmentRepository appointmentRepository;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    /** The application clock, in the clinic zone (ClockConfig). */
    private final Clock clock;

    // ==================== SHARED CLINICAL WRITE CHECKS ====================

    /**
     * Preflight for creating a clinical artifact (consultation, record, prescription, lab, imaging):
     * the caller must be an active DOCTOR, the patient must exist, the doctor must have a care relationship
     * with the patient, and an optional consultationId must belong to that patient.
     *
     * @return the caller's (author doctor's) system ID, to be stored as doctorSystemId
     */
    public String requireTreatingDoctor(String patientSystemId, Integer consultationId, PatientPermission permission) {
        authorizationService.requireRole(AuthorizationService.DOCTOR);
        String doctorSystemId = authorizationService.currentSystemId();

        Doctor doctor = doctorRepository.findBySystemId(doctorSystemId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor profile not found"));
        if (!Boolean.TRUE.equals(doctor.getIsActive())) {
            throw new AuthorizationException("Your clinician account is not active");
        }

        if (patientSystemId == null || patientSystemId.isBlank()) {
            throw new BadRequestException("Patient ID is required");
        }
        patientRepository.findBySystemId(patientSystemId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found"));

        authorizationService.requirePatientAccess(patientSystemId, permission);

        if (consultationId != null) {
            Consultation consultation = consultationRepository.findById(consultationId)
                    .orElseThrow(() -> new ResourceNotFoundException(MSG_NOT_FOUND));
            if (!patientSystemId.equals(consultation.getPatientSystemId())) {
                throw new BadRequestException("Consultation does not belong to this patient");
            }
        }
        return doctorSystemId;
    }

    /**
     * The caller must be the DOCTOR who authored the row (or an ADMIN when {@code allowAdmin}).
     * Denials are audited as ACCESS_DENIED.
     */
    public void requireAuthorDoctor(String authorDoctorSystemId, String patientSystemId, String resourceType,
                                    Integer resourceId, boolean allowAdmin) {
        if (allowAdmin && authorizationService.isAdmin()) {
            return;
        }
        String caller = authorizationService.currentSystemId();
        String role = authorizationService.currentRole();
        if (AuthorizationService.DOCTOR.equals(role) && caller.equals(authorDoctorSystemId)) {
            return;
        }
        try {
            auditService.logAccessDenied(caller, resourceType, resourceId, patientSystemId, null,
                    "Caller is not the authoring doctor");
        } catch (RuntimeException auditFailure) {
            log.error("Failed to write ACCESS_DENIED audit row: {}", auditFailure.getClass().getName());
        }
        throw new AuthorizationException("Only the doctor who created this item can change it", resourceType, resourceId);
    }

    /**
     * The caller must be a DOCTOR with a care relationship to the patient.
     */
    public void requireRelatedDoctor(String patientSystemId, PatientPermission permission, Integer resourceId) {
        authorizationService.requireRole(AuthorizationService.DOCTOR);
        authorizationService.requirePatientAccess(patientSystemId, permission, resourceId);
    }

    /**
     * Audit one READ row per distinct patient in a list result.
     * Kept for existing callers; new code calls {@link AuditService#logListRead} directly.
     */
    public <T> void auditListRead(String resourceType, Collection<T> rows, Function<T, String> patientOf) {
        auditService.logListRead(resourceType, rows, patientOf);
    }

    // ==================== CREATE ====================

    /**
     * Opens a consultation for one of the caller's appointments (POST /api/consultations). The appointment must be
     * SCHEDULED, RESCHEDULED or COMPLETED and have started (clinic clock); one non-cancelled consultation per
     * appointment.
     */
    public ConsultationResponse createConsultation(CreateConsultationRequest request) {
        if (request == null) {
            throw new BadRequestException("Malformed request body");
        }
        authorizationService.requireRole(AuthorizationService.DOCTOR);
        if (request.getAppointmentId() == null) {
            throw validation("appointmentId", "is required");
        }

        // Serialise creates for this appointment: a concurrent create waits here and then sees our row.
        consultationRepository.lockAppointment(request.getAppointmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found"));
        Appointment appointment = appointmentRepository.findById(request.getAppointmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found"));

        // The appointment is the source of truth for who the consultation is between.
        String requestedPatient = trimToNull(request.getPatientSystemId());
        if (requestedPatient != null && !requestedPatient.equals(appointment.getPatientSystemId())) {
            throw new BadRequestException("Appointment does not belong to this patient");
        }
        String patientSystemId = appointment.getPatientSystemId();

        if (!authorizationService.currentSystemId().equals(appointment.getDoctorSystemId())) {
            throw new AuthorizationException("You can only document consultations for your own appointments",
                    RESOURCE, null);
        }
        requireAppointmentAllowsConsultation(appointment);
        String doctorSystemId = requireTreatingDoctor(patientSystemId, null, PatientPermission.CONSULTATIONS);

        boolean followUpRequired = Boolean.TRUE.equals(request.getFollowUpRequired());
        LocalDate followUpDate = null;
        if (followUpRequired) {
            followUpDate = request.getFollowUpDate();
            requireFollowUpDatePresent(followUpDate);
            requireFollowUpDateNotPast(followUpDate);
        }

        if (consultationRepository.existsByAppointmentIdAndStatusIn(appointment.getAppointmentId(), LIVE_STATUSES)) {
            throw new ConflictException(MSG_ALREADY_EXISTS);
        }

        String status = request.getStatus() == null ? DRAFT : normalizeStatus(request.getStatus());
        if (!DRAFT.equals(status) && !IN_PROGRESS.equals(status)) {
            throw validation("status", "must be DRAFT or IN_PROGRESS");
        }

        LocalDateTime now = now();
        Consultation consultation = Consultation.builder()
                .appointmentId(appointment.getAppointmentId())
                .patientSystemId(patientSystemId)
                .doctorSystemId(doctorSystemId)
                .chiefComplaint(cleanText(request.getChiefComplaint()))
                .historyOfPresentIllness(cleanText(request.getHistoryOfPresentIllness()))
                .physicalExamination(cleanText(request.getPhysicalExamination()))
                .clinicalNotes(cleanText(request.getClinicalNotes()))
                .diagnosis(cleanText(request.getDiagnosis()))
                .treatmentPlan(cleanText(request.getTreatmentPlan()))
                .followUpInstructions(cleanText(request.getFollowUpInstructions()))
                .followUpRequired(followUpRequired)
                .followUpDate(followUpDate)
                .durationMinutes(request.getDurationMinutes())
                .status(status)
                .startedAt(IN_PROGRESS.equals(status) ? now : null)
                .createdAt(now)
                .build();

        Consultation saved;
        try {
            saved = consultationRepository.saveAndFlush(consultation);
        } catch (DataIntegrityViolationException e) {
            if (isActivePerAppointmentViolation(e)) {
                throw new ConflictException(MSG_ALREADY_EXISTS);
            }
            throw e;
        }
        auditService.logClinicalAccess("CREATE", RESOURCE, saved.getConsultationId(), patientSystemId);
        return toResponse(saved);
    }

    // ==================== READS ====================

    public ConsultationResponse getConsultationById(Integer consultationId) {
        Consultation consultation = load(consultationId);
        authorizationService.requirePatientAccess(consultation.getPatientSystemId(), PatientPermission.CONSULTATIONS, consultationId);
        if (isUnfinished(consultation) && !callerSeesDrafts()) {
            throw new ResourceNotFoundException(MSG_NOT_FOUND);
        }
        auditService.logClinicalAccess("READ", RESOURCE, consultationId, consultation.getPatientSystemId());
        return toResponse(consultation);
    }

    /**
     * The canonical paged list (GET /api/consultations). Who sees what:
     * <ul>
     *   <li>PATIENT: own consultations (patientSystemId defaults to the caller; another patient is 403), COMPLETED
     *       and CANCELLED only;</li>
     *   <li>CAREGIVER: patientSystemId required, CONSULTATIONS permission on that patient, COMPLETED and CANCELLED
     *       only;</li>
     *   <li>DOCTOR without patientSystemId: own consultations (doctorSystemId defaults to the caller; another
     *       doctor is 403); with patientSystemId and doctorSystemId = caller: own consultations with that patient;
     *       with patientSystemId otherwise: care relationship required, that patient's consultations by any
     *       doctor;</li>
     *   <li>ADMIN: everything that matches the filters.</li>
     * </ul>
     * Ordered by createdAt then id, newest first; filtering and paging in the database.
     */
    public PageResponse<ConsultationResponse> listConsultations(String patientSystemId, String doctorSystemId,
                                                                String status, Integer page, Integer size) {
        String statusFilter = parseStatusFilter(status);
        String patient = trimToNull(patientSystemId);
        String doctor = trimToNull(doctorSystemId);
        String caller = authorizationService.currentSystemId();
        String role = authorizationService.currentRole();

        List<String> visible = null; // null: every status
        switch (role) {
            case AuthorizationService.PATIENT -> {
                patient = patient == null ? caller : patient;
                authorizationService.requirePatientAccess(patient, PatientPermission.CONSULTATIONS);
                visible = FINAL_STATUSES;
            }
            case AuthorizationService.CAREGIVER -> {
                if (patient == null) {
                    throw validation("patientSystemId", "is required");
                }
                authorizationService.requirePatientAccess(patient, PatientPermission.CONSULTATIONS);
                visible = FINAL_STATUSES;
            }
            case AuthorizationService.DOCTOR -> {
                if (patient == null) {
                    doctor = doctor == null ? caller : doctor;
                    if (!caller.equals(doctor)) {
                        authorizationService.requireDoctorSelf(doctor);
                    }
                } else if (!caller.equals(doctor)) {
                    authorizationService.requirePatientAccess(patient, PatientPermission.CONSULTATIONS);
                }
            }
            case AuthorizationService.ADMIN -> {
                // every row, filters as given
            }
            default -> throw new AuthorizationException("You don't have permission to access this resource");
        }

        Pageable pageable = PageParams.of(page, size, ConsultationRepository.NEWEST_FIRST);
        List<String> statuses = visible;
        if (statusFilter != null) {
            if (visible != null && !visible.contains(statusFilter)) {
                return PageResponse.empty(pageable.getPageNumber(), pageable.getPageSize());
            }
            statuses = List.of(statusFilter);
        }
        Specification<Consultation> spec = Specification.where(ConsultationRepository.patient(patient))
                .and(ConsultationRepository.doctor(doctor))
                .and(ConsultationRepository.statusIn(statuses));
        Page<Consultation> result = consultationRepository.findAll(spec, pageable);
        auditService.logListRead(RESOURCE, result.getContent(), Consultation::getPatientSystemId);
        return PageResponse.of(result, toResponses(result.getContent()));
    }

    /**
     * Legacy array list of one patient's consultations (deprecated route; newest 200). Patients and caregivers get
     * COMPLETED and CANCELLED rows only. Audited with one READ row for the patient, even when empty.
     */
    public List<ConsultationResponse> getConsultationsByPatientSystemId(String patientSystemId) {
        authorizationService.requirePatientAccess(patientSystemId, PatientPermission.CONSULTATIONS);
        List<Consultation> rows = callerSeesDrafts()
                ? consultationRepository.findByPatientSystemId(patientSystemId, legacyCap())
                : consultationRepository.findByPatientSystemIdAndStatusIn(patientSystemId, FINAL_STATUSES, legacyCap());
        auditService.logClinicalAccess("READ", RESOURCE, null, patientSystemId);
        return toResponses(rows);
    }

    /**
     * Internal use only (CaregiverService builds the dependent activity feed after checking each
     * dependent_access flag itself). Not exposed by any controller. Caregivers never see unfinished notes.
     * Newest 200 across the given patients.
     */
    public List<ConsultationResponse> getConsultationsForPatients(List<String> patientSystemIds) {
        if (patientSystemIds == null || patientSystemIds.isEmpty()) return List.of();
        List<Consultation> rows = consultationRepository.findByPatientSystemIdInAndStatusIn(
                patientSystemIds, FINAL_STATUSES, legacyCap());
        auditListRead(RESOURCE, rows, Consultation::getPatientSystemId);
        return toResponses(rows);
    }

    /** Legacy array list: every consultation the doctor authored (doctor self or admin; newest 200). */
    public List<ConsultationResponse> getConsultationsByDoctorSystemId(String doctorSystemId) {
        authorizationService.requireDoctorSelf(doctorSystemId);
        List<Consultation> rows = consultationRepository.findByDoctorSystemId(doctorSystemId, legacyCap());
        auditListRead(RESOURCE, rows, Consultation::getPatientSystemId);
        return toResponses(rows);
    }

    /**
     * Legacy array list: consultations between this doctor and one patient, all statuses (doctor self or admin;
     * newest 200).
     */
    public List<ConsultationResponse> getDoctorPatientConsultations(String doctorSystemId, String patientSystemId) {
        authorizationService.requireDoctorSelf(doctorSystemId);
        List<Consultation> rows = consultationRepository.findByDoctorSystemIdAndPatientSystemId(
                doctorSystemId, patientSystemId, legacyCap());
        auditListRead(RESOURCE, rows, Consultation::getPatientSystemId);
        return toResponses(rows);
    }

    /**
     * Legacy array list (GET /api/doctors/consultations): the doctor's non-cancelled consultations, the newest 200,
     * then ordered IN_PROGRESS, DRAFT, COMPLETED (newest first inside each group). An admin gets an empty list.
     */
    public List<ConsultationResponse> getActiveForDoctor(String doctorSystemId) {
        authorizationService.requireDoctorSelf(doctorSystemId);
        List<Consultation> rows = inActiveDisplayOrder(consultationRepository.findByDoctorSystemIdAndStatusIn(
                doctorSystemId, ACTIVE_DISPLAY_ORDER, legacyCap()));
        auditListRead(RESOURCE, rows, Consultation::getPatientSystemId);
        return toResponses(rows);
    }

    /** As {@link #getActiveForDoctor(String)}, limited to one patient. */
    public List<ConsultationResponse> getActiveForDoctorAndPatient(String doctorSystemId, String patientSystemId) {
        authorizationService.requireDoctorSelf(doctorSystemId);
        List<Consultation> rows = inActiveDisplayOrder(consultationRepository.findByDoctorSystemIdAndPatientSystemIdAndStatusIn(
                doctorSystemId, patientSystemId, ACTIVE_DISPLAY_ORDER, legacyCap()));
        auditListRead(RESOURCE, rows, Consultation::getPatientSystemId);
        return toResponses(rows);
    }

    /**
     * Consultations of one appointment (at most one non-cancelled row plus earlier cancelled ones), newest first,
     * capped at 50. Patients and caregivers do not receive DRAFT or IN_PROGRESS rows.
     */
    public List<ConsultationResponse> getConsultationsByAppointmentId(Integer appointmentId) {
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found"));
        String patientSystemId = appointment.getPatientSystemId();
        authorizationService.requirePatientAccess(patientSystemId, PatientPermission.CONSULTATIONS);
        Pageable cap = PageRequest.of(0, APPOINTMENT_LIST_CAP, ConsultationRepository.NEWEST_FIRST);
        List<Consultation> rows = (callerSeesDrafts()
                ? consultationRepository.findByAppointmentId(appointmentId, cap)
                : consultationRepository.findByAppointmentIdAndStatusIn(appointmentId, FINAL_STATUSES, cap)).stream()
                .filter(c -> patientSystemId.equals(c.getPatientSystemId()))
                .toList();
        auditService.logClinicalAccess("READ", RESOURCE, null, patientSystemId);
        return toResponses(rows);
    }

    /**
     * Counts per status of the consultations the doctor authored (doctor self or admin), one GROUP BY query.
     * Not audited (no patient data).
     */
    public ConsultationStatsResponse getStatsForDoctor(String doctorSystemId) {
        authorizationService.requireDoctorSelf(doctorSystemId);
        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : consultationRepository.countByStatusForDoctor(doctorSystemId)) {
            counts.put((String) row[0], ((Number) row[1]).longValue());
        }
        return ConsultationStatsResponse.of(counts.getOrDefault(DRAFT, 0L), counts.getOrDefault(IN_PROGRESS, 0L),
                counts.getOrDefault(COMPLETED, 0L), counts.getOrDefault(CANCELLED, 0L));
    }

    public List<ConsultationResponse> getCompletedForUser(String userSystemId, String userRole) {
        return getByStatusForUser(userSystemId, userRole, COMPLETED);
    }

    public List<ConsultationResponse> getInProgressForUser(String userSystemId, String userRole) {
        return getByStatusForUser(userSystemId, userRole, IN_PROGRESS);
    }

    public List<ConsultationResponse> getCancelledForUser(String userSystemId, String userRole) {
        return getByStatusForUser(userSystemId, userRole, CANCELLED);
    }

    public List<ConsultationResponse> getDraftForUser(String userSystemId, String userRole) {
        return getByStatusForUser(userSystemId, userRole, DRAFT);
    }

    private List<ConsultationResponse> getByStatusForUser(String userSystemId, String userRole, String status) {
        List<Consultation> rows;
        if (AuthorizationService.PATIENT.equals(userRole)) {
            authorizationService.requirePatientAccess(userSystemId, PatientPermission.CONSULTATIONS);
            rows = OPEN_STATUSES.contains(status) ? List.of()
                    : consultationRepository.findByPatientSystemIdAndStatus(userSystemId, status, legacyCap());
        } else if (AuthorizationService.DOCTOR.equals(userRole)) {
            authorizationService.requireDoctorSelf(userSystemId);
            rows = consultationRepository.findByDoctorSystemIdAndStatus(userSystemId, status, legacyCap());
        } else {
            throw new BadRequestException("Unsupported user role");
        }
        auditListRead(RESOURCE, rows, Consultation::getPatientSystemId);
        return toResponses(rows);
    }

    // ==================== WRITES ====================

    /**
     * Partial update of the notes (PUT /api/consultations/{id}) by the authoring doctor, optionally followed by a
     * status transition in the same transaction. Refused with 409 once the consultation is COMPLETED or CANCELLED.
     */
    public ConsultationResponse updateConsultation(Integer consultationId, UpdateConsultationRequest request) {
        if (request == null) {
            throw new BadRequestException("Malformed request body");
        }
        Consultation consultation = loadForUpdate(consultationId);
        requireAuthorDoctor(consultation.getDoctorSystemId(), consultation.getPatientSystemId(), RESOURCE, consultationId, false);
        requireEditable(consultation);

        // appointmentId / patientSystemId / doctorSystemId are fixed at creation and never changed here.
        applyText(request.getChiefComplaint(), consultation::setChiefComplaint);
        applyText(request.getHistoryOfPresentIllness(), consultation::setHistoryOfPresentIllness);
        applyText(request.getPhysicalExamination(), consultation::setPhysicalExamination);
        applyText(request.getClinicalNotes(), consultation::setClinicalNotes);
        applyText(request.getDiagnosis(), consultation::setDiagnosis);
        applyText(request.getTreatmentPlan(), consultation::setTreatmentPlan);
        applyText(request.getFollowUpInstructions(), consultation::setFollowUpInstructions);
        applyFollowUp(consultation, request.getFollowUpRequired(), request.getFollowUpDate());
        if (request.getDurationMinutes() != null) {
            int minutes = request.getDurationMinutes();
            if (minutes < 1 || minutes > 480) {
                throw validation("durationMinutes", CreateConsultationRequest.DURATION_RANGE);
            }
            consultation.setDurationMinutes(minutes);
        }

        boolean completed = false;
        if (request.getStatus() != null) {
            String target = normalizeStatus(request.getStatus());
            if (!Consultation.STATUSES.contains(target)) {
                throw validation("status", "must be one of DRAFT, IN_PROGRESS, COMPLETED, CANCELLED");
            }
            completed = applyTransition(consultation, target) && COMPLETED.equals(target);
        }

        Consultation saved = consultationRepository.saveAndFlush(consultation);
        if (completed) {
            publishCompleted(saved);
        }
        auditService.logClinicalAccess("UPDATE", RESOURCE, consultationId, saved.getPatientSystemId());
        return toResponse(saved);
    }

    /**
     * Earlier signature of {@link #updateConsultation(Integer, UpdateConsultationRequest)} (the PUT body used to be
     * the create DTO). The fixed fields of the create body ({@code appointmentId}, {@code patientSystemId},
     * {@code doctorSystemId}) are ignored, as before.
     *
     * @deprecated call {@link #updateConsultation(Integer, UpdateConsultationRequest)}
     */
    @Deprecated
    public ConsultationResponse updateConsultation(Integer consultationId, CreateConsultationRequest request) {
        if (request == null) {
            throw new BadRequestException("Malformed request body");
        }
        return updateConsultation(consultationId, UpdateConsultationRequest.builder()
                .chiefComplaint(request.getChiefComplaint())
                .historyOfPresentIllness(request.getHistoryOfPresentIllness())
                .physicalExamination(request.getPhysicalExamination())
                .clinicalNotes(request.getClinicalNotes())
                .diagnosis(request.getDiagnosis())
                .treatmentPlan(request.getTreatmentPlan())
                .followUpInstructions(request.getFollowUpInstructions())
                .followUpRequired(request.getFollowUpRequired())
                .followUpDate(request.getFollowUpDate())
                .durationMinutes(request.getDurationMinutes())
                .status(request.getStatus())
                .build());
    }

    /**
     * Status transition by the authoring doctor (PATCH .../status and the mark-* routes). Requesting the current
     * status is a no-op: nothing is written, no event is published.
     */
    public ConsultationResponse updateConsultationStatus(Integer consultationId, String status) {
        Consultation consultation = loadForUpdate(consultationId);
        requireAuthorDoctor(consultation.getDoctorSystemId(), consultation.getPatientSystemId(), RESOURCE, consultationId, false);

        String target = status == null ? null : normalizeStatus(status);
        if (target == null || !Consultation.STATUSES.contains(target)) {
            throw new BadRequestException(MSG_INVALID_STATUS);
        }
        if (!applyTransition(consultation, target)) {
            return toResponse(consultation);
        }

        Consultation saved = consultationRepository.saveAndFlush(consultation);
        if (COMPLETED.equals(target)) {
            publishCompleted(saved);
        }
        auditService.logClinicalAccess("UPDATE", RESOURCE, consultationId, saved.getPatientSystemId());
        return toResponse(saved);
    }

    /**
     * Physically deletes a DRAFT (it was never visible to the patient). Only the authoring doctor or an admin.
     * Rows linked through ON DELETE SET NULL foreign keys are unlinked by the database; any other reference makes
     * the delete fail with 409.
     */
    public void deleteConsultation(Integer consultationId) {
        Consultation consultation = loadForUpdate(consultationId);
        requireAuthorDoctor(consultation.getDoctorSystemId(), consultation.getPatientSystemId(), RESOURCE, consultationId, true);
        if (!DRAFT.equals(consultation.getStatus())) {
            throw new ConflictException("Only a draft consultation can be deleted");
        }
        try {
            consultationRepository.delete(consultation);
            consultationRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("This consultation is referenced by other records and cannot be deleted");
        }
        auditService.logClinicalAccess("DELETE", RESOURCE, consultationId, consultation.getPatientSystemId());
    }

    /**
     * System cancellation (ConsultationAppointmentListener): every DRAFT or IN_PROGRESS consultation of the
     * appointment becomes CANCELLED, inside the caller's transaction. COMPLETED and CANCELLED rows are untouched, so
     * a repeated event changes nothing. No caller authorisation: the appointment change was authorised by its own
     * service. Each cancellation is audited as UPDATE.
     *
     * @return the number of consultations cancelled
     */
    public int cancelOpenConsultationsForAppointment(Integer appointmentId) {
        if (appointmentId == null) {
            return 0;
        }
        consultationRepository.lockAppointment(appointmentId);
        List<Consultation> open = consultationRepository.findByAppointmentIdAndStatusInForUpdate(appointmentId, OPEN_STATUSES);
        for (Consultation consultation : open) {
            consultation.setStatus(CANCELLED);
            consultationRepository.save(consultation);
            auditService.logClinicalAccess("UPDATE", RESOURCE, consultation.getConsultationId(),
                    consultation.getPatientSystemId());
        }
        if (!open.isEmpty()) {
            consultationRepository.flush();
            log.info("Cancelled {} open consultation(s) of appointment {}", open.size(), appointmentId);
        }
        return open.size();
    }

    // ==================== RULES ====================

    /**
     * Applies the transition table. Returns false for the same status (no-op).
     *
     * @throws ConflictException   from a terminal state, or IN_PROGRESS back to DRAFT
     * @throws BadRequestException (VALIDATION_FAILED) when completing without a chief complaint or a diagnosis
     */
    private boolean applyTransition(Consultation consultation, String target) {
        String current = consultation.getStatus();
        if (target.equals(current)) {
            return false;
        }
        if (COMPLETED.equals(current)) {
            throw new ConflictException(MSG_COMPLETED_LOCKED);
        }
        if (CANCELLED.equals(current)) {
            throw new ConflictException(MSG_CANCELLED_LOCKED);
        }
        if (IN_PROGRESS.equals(current) && DRAFT.equals(target)) {
            throw new ConflictException(MSG_NO_BACK_TO_DRAFT);
        }
        if (COMPLETED.equals(target)) {
            if (isBlank(consultation.getChiefComplaint())) {
                throw validation("chiefComplaint", "is required to complete a consultation");
            }
            if (isBlank(consultation.getDiagnosis())) {
                throw validation("diagnosis", "is required to complete a consultation");
            }
        }

        consultation.setStatus(target);
        LocalDateTime now = now();
        if (IN_PROGRESS.equals(target) && consultation.getStartedAt() == null) {
            consultation.setStartedAt(now);
        }
        if (COMPLETED.equals(target) && consultation.getCompletedAt() == null) {
            consultation.setCompletedAt(now);
        }
        return true;
    }

    private void publishCompleted(Consultation consultation) {
        eventPublisher.publishEvent(new ConsultationCompletedEvent(
                consultation.getConsultationId(), consultation.getAppointmentId(), consultation.getPatientSystemId(),
                consultation.getDoctorSystemId(), Boolean.TRUE.equals(consultation.getFollowUpRequired()),
                consultation.getFollowUpDate()));
        // Listeners may have changed other rows through JPA: write them before the response reads them natively.
        consultationRepository.flush();
    }

    private static void requireEditable(Consultation consultation) {
        if (COMPLETED.equals(consultation.getStatus())) {
            throw new ConflictException(MSG_COMPLETED_LOCKED);
        }
        if (CANCELLED.equals(consultation.getStatus())) {
            throw new ConflictException(MSG_CANCELLED_LOCKED);
        }
    }

    private void requireAppointmentAllowsConsultation(Appointment appointment) {
        String status = appointment.getStatus();
        if ("CANCELLED".equals(status)) {
            throw new ConflictException("Cannot create a consultation for a cancelled appointment");
        }
        if ("NO_SHOW".equals(status)) {
            throw new ConflictException("Cannot create a consultation for a missed appointment");
        }
        if (!APPOINTMENT_STATUSES_ALLOWING_CREATE.contains(status)) {
            throw new ConflictException("A consultation can only be opened for a scheduled or completed appointment");
        }
        if (appointment.getScheduledAt() == null || appointment.getScheduledAt().isAfter(now())) {
            throw new ConflictException("A consultation can only be opened once the appointment has started");
        }
    }

    /**
     * Follow-up rule on the merged state: effective required = body value, else stored value. Not required: the
     * date is cleared and a date in the body is ignored. Required: the effective date must exist and a date sent in
     * the body must not be before clinic today (a stored date that has since passed is not re-checked).
     */
    private void applyFollowUp(Consultation consultation, Boolean requiredInBody, LocalDate dateInBody) {
        boolean required = requiredInBody != null ? requiredInBody : Boolean.TRUE.equals(consultation.getFollowUpRequired());
        if (!required) {
            consultation.setFollowUpRequired(false);
            consultation.setFollowUpDate(null);
            return;
        }
        LocalDate effective = dateInBody != null ? dateInBody : consultation.getFollowUpDate();
        requireFollowUpDatePresent(effective);
        if (dateInBody != null) {
            requireFollowUpDateNotPast(dateInBody);
        }
        consultation.setFollowUpRequired(true);
        consultation.setFollowUpDate(effective);
    }

    private static void requireFollowUpDatePresent(LocalDate date) {
        if (date == null) {
            throw validation("followUpDate", "is required when a follow-up is required");
        }
    }

    private void requireFollowUpDateNotPast(LocalDate date) {
        if (date.isBefore(LocalDate.now(clock))) {
            throw validation("followUpDate", "must not be before today");
        }
    }

    private static boolean isActivePerAppointmentViolation(DataIntegrityViolationException e) {
        Throwable current = e;
        int depth = 0;
        while (current != null && depth++ < 10) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException cve
                    && cve.getConstraintName() != null) {
                return cve.getConstraintName().toLowerCase(Locale.ROOT).contains(ACTIVE_PER_APPOINTMENT_INDEX);
            }
            String message = current.getMessage();
            if (message != null && message.contains(ACTIVE_PER_APPOINTMENT_INDEX)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    // ==================== HELPERS ====================

    private Consultation load(Integer consultationId) {
        return consultationRepository.findById(consultationId)
                .orElseThrow(() -> new ResourceNotFoundException(MSG_NOT_FOUND));
    }

    /** Locks the appointment row, then the consultation row (fresh read), in that order. */
    private Consultation loadForUpdate(Integer consultationId) {
        Integer appointmentId = consultationRepository.findAppointmentIdOf(consultationId)
                .orElseThrow(() -> new ResourceNotFoundException(MSG_NOT_FOUND));
        consultationRepository.lockAppointment(appointmentId);
        return consultationRepository.findByIdForUpdate(consultationId)
                .orElseThrow(() -> new ResourceNotFoundException(MSG_NOT_FOUND));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static Pageable legacyCap() {
        return PageRequest.of(0, LEGACY_LIST_CAP, ConsultationRepository.NEWEST_FIRST);
    }

    private static List<Consultation> inActiveDisplayOrder(List<Consultation> rows) {
        // Stable sort: rows arrive newest first, so each group stays newest first.
        return rows.stream()
                .sorted(Comparator.comparingInt(c -> ACTIVE_DISPLAY_ORDER.indexOf(c.getStatus())))
                .toList();
    }

    private static boolean isUnfinished(Consultation consultation) {
        return OPEN_STATUSES.contains(consultation.getStatus());
    }

    /** Doctors and admins see unfinished notes; patients and caregivers do not. */
    private boolean callerSeesDrafts() {
        String role = authorizationService.currentRole();
        return AuthorizationService.DOCTOR.equals(role) || AuthorizationService.ADMIN.equals(role);
    }

    private static String parseStatusFilter(String status) {
        String value = trimToNull(status);
        if (value == null) {
            return null;
        }
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!Consultation.STATUSES.contains(normalized)) {
            throw validation("status", "must be one of DRAFT, IN_PROGRESS, COMPLETED, CANCELLED");
        }
        return normalized;
    }

    private static String normalizeStatus(String status) {
        return status.trim().toUpperCase(Locale.ROOT);
    }

    private static BadRequestException validation(String field, String text) {
        return new BadRequestException(ErrorResponse.VALIDATION_FAILED, field + ": " + text);
    }

    /** Text as stored: trimmed, blank becomes null. */
    private static String cleanText(String value) {
        return trimToNull(value);
    }

    /** Partial-update text: null leaves the field unchanged, blank clears it. */
    private static void applyText(String value, Consumer<String> setter) {
        if (value != null) {
            setter.accept(cleanText(value));
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // ==================== MAPPING ====================

    private ConsultationResponse toResponse(Consultation consultation) {
        return toResponses(List.of(consultation)).get(0);
    }

    /**
     * Batch mapping: one query for the linked appointments (type and status) and one for the CONSULT medical
     * records, whatever the number of rows. Both are native, so they see changes made by completion listeners.
     */
    private List<ConsultationResponse> toResponses(List<Consultation> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Integer> appointmentIds = rows.stream().map(Consultation::getAppointmentId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Integer, String> types = new HashMap<>();
        Map<Integer, String> appointmentStatuses = new HashMap<>();
        if (!appointmentIds.isEmpty()) {
            for (Object[] row : consultationRepository.findAppointmentTypeAndStatus(appointmentIds)) {
                Integer id = ((Number) row[0]).intValue();
                types.put(id, (String) row[1]);
                appointmentStatuses.put(id, (String) row[2]);
            }
        }
        List<Integer> consultationIds = rows.stream().map(Consultation::getConsultationId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Integer, Integer> recordIds = new HashMap<>();
        if (!consultationIds.isEmpty()) {
            for (Object[] row : consultationRepository.findConsultRecordIds(consultationIds)) {
                recordIds.put(((Number) row[0]).intValue(), ((Number) row[1]).intValue());
            }
        }
        List<ConsultationResponse> responses = new ArrayList<>(rows.size());
        for (Consultation c : rows) {
            responses.add(mapToResponse(c, types.get(c.getAppointmentId()), appointmentStatuses.get(c.getAppointmentId()),
                    recordIds.get(c.getConsultationId())));
        }
        return responses;
    }

    private static ConsultationResponse mapToResponse(Consultation consultation, String appointmentType,
                                                      String appointmentStatus, Integer medicalRecordId) {
        return ConsultationResponse.builder()
                .consultationId(consultation.getConsultationId())
                .appointmentId(consultation.getAppointmentId())
                .appointmentType(appointmentType)
                .appointmentStatus(appointmentStatus)
                .patientSystemId(consultation.getPatientSystemId())
                .patientName(null)
                .doctorSystemId(consultation.getDoctorSystemId())
                .chiefComplaint(consultation.getChiefComplaint())
                .historyOfPresentIllness(consultation.getHistoryOfPresentIllness())
                .physicalExamination(consultation.getPhysicalExamination())
                .clinicalNotes(consultation.getClinicalNotes())
                .diagnosis(consultation.getDiagnosis())
                .treatmentPlan(consultation.getTreatmentPlan())
                .followUpInstructions(consultation.getFollowUpInstructions())
                .followUpRequired(Boolean.TRUE.equals(consultation.getFollowUpRequired()))
                .followUpDate(consultation.getFollowUpDate())
                .durationMinutes(consultation.getDurationMinutes())
                .status(consultation.getStatus())
                .startedAt(consultation.getStartedAt())
                .completedAt(consultation.getCompletedAt())
                .createdAt(consultation.getCreatedAt())
                .medicalRecordId(medicalRecordId)
                .build();
    }
}
