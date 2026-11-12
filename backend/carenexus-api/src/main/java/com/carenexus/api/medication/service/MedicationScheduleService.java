package com.carenexus.api.medication.service;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.event.MedicationDoseMissedEvent;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.medication.dto.response.MedicationAdherenceResponse;
import com.carenexus.api.medication.dto.response.MedicationDoseResponse;
import com.carenexus.api.medication.model.MedicationSchedule;
import com.carenexus.api.medication.repository.MedicationScheduleJdbc;
import com.carenexus.api.medication.repository.MedicationScheduleJdbc.StatusCounts;
import com.carenexus.api.medication.repository.MedicationScheduleRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Patients' dose lists, adherence counts and the taken / missed / undo marks.
 *
 * Read access is {@code requirePatientAccess(patient, MEDICATIONS)} (patient self, related doctor, caregiver whose
 * grant gives MEDICATIONS, admin). Marking is narrower: role PATIENT or CAREGIVER only; a patient marks their own
 * doses; a caregiver needs both MEDICATIONS and MANAGE_CARE_PLAN. Only doses of the clinic's today or yesterday can
 * be marked. Same-status marks are no-ops.
 */
@Service
@Transactional
public class MedicationScheduleService {

    public static final String RESOURCE = "MEDICATION_SCHEDULE";

    static final int MIN_DAYS = 1;
    static final int MAX_DAYS = 90;
    /** Doses examined when counting consecutive misses (this dose plus at most 49 before it). */
    static final int MISS_RUN_LIMIT = 50;

    static final String DOSE_NOT_FOUND = "Medication dose not found";
    static final String OUTSIDE_WINDOW = "Only doses scheduled for today or yesterday can be updated";
    static final String DAYS_RANGE = "days: must be between 1 and 90";

    private final MedicationScheduleRepository scheduleRepository;
    private final MedicationScheduleJdbc jdbc;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public MedicationScheduleService(MedicationScheduleRepository scheduleRepository, MedicationScheduleJdbc jdbc,
                                     AuthorizationService authorizationService, AuditService auditService,
                                     ApplicationEventPublisher eventPublisher, Clock clock) {
        this.scheduleRepository = scheduleRepository;
        this.jdbc = jdbc;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    // ==================== READS ====================

    /** The calling patient's doses of one clinic day (default: today). */
    @Transactional(readOnly = true)
    public List<MedicationDoseResponse> myDay(LocalDate date) {
        authorizationService.requireRole(AuthorizationService.PATIENT);
        return day(authorizationService.currentSystemId(), date);
    }

    /** One patient's doses of one clinic day (default: today). Read access MEDICATIONS. */
    @Transactional(readOnly = true)
    public List<MedicationDoseResponse> patientDay(String patientSystemId, LocalDate date) {
        authorizationService.requirePatientAccess(patientSystemId, PatientPermission.MEDICATIONS);
        return day(patientSystemId, date);
    }

    /** Taken, missed and pending counts over the last {@code days} clinic days, today included. */
    @Transactional(readOnly = true)
    public MedicationAdherenceResponse adherence(String patientSystemId, int days) {
        if (days < MIN_DAYS || days > MAX_DAYS) {
            throw new BadRequestException(ErrorResponse.VALIDATION_FAILED, DAYS_RANGE);
        }
        authorizationService.requirePatientAccess(patientSystemId, PatientPermission.MEDICATIONS);
        LocalDate to = today();
        LocalDate from = to.minusDays(days - 1L);
        StatusCounts counts = jdbc.countByStatus(patientSystemId, from, to);
        long total = counts.taken() + counts.missed() + counts.pending();
        long decided = counts.taken() + counts.missed();
        Integer rate = decided == 0 ? null : BigDecimal.valueOf(100L * counts.taken())
                .divide(BigDecimal.valueOf(decided), 0, RoundingMode.HALF_UP)
                .intValueExact();
        auditService.logClinicalAccess("READ", RESOURCE, null, patientSystemId);
        return new MedicationAdherenceResponse(patientSystemId, days, from, to, counts.taken(), counts.missed(),
                counts.pending(), total, rate);
    }

    // ==================== MARKS ====================

    /** PENDING or MISSED to TAKEN (taken now, by the caller). Already TAKEN: no-op. */
    public MedicationDoseResponse markTaken(Integer scheduleId) {
        MedicationSchedule dose = loadForMark(scheduleId);
        if (MedicationSchedule.TAKEN.equals(dose.status())) {
            return MedicationDoseResponse.from(dose, today());
        }
        dose.setTaken(true);
        dose.setMissed(false);
        dose.setTakenAt(now());
        dose.setMarkedBySystemId(authorizationService.currentSystemId());
        return saveMarked(dose);
    }

    /** PENDING or TAKEN to MISSED (by the caller); publishes MedicationDoseMissedEvent. Already MISSED: no-op. */
    public MedicationDoseResponse markMissed(Integer scheduleId) {
        MedicationSchedule dose = loadForMark(scheduleId);
        if (MedicationSchedule.MISSED.equals(dose.status())) {
            return MedicationDoseResponse.from(dose, today());
        }
        dose.setTaken(false);
        dose.setMissed(true);
        dose.setTakenAt(null);
        dose.setMarkedBySystemId(authorizationService.currentSystemId());
        MedicationDoseResponse response = saveMarked(dose);
        int run = jdbc.missedRunBefore(dose.getPrescriptionId(), dose.getScheduleDate(), dose.getScheduledTime(),
                MISS_RUN_LIMIT - 1);
        eventPublisher.publishEvent(new MedicationDoseMissedEvent(dose.getScheduleId(), dose.getPrescriptionId(),
                dose.getPatientSystemId(), dose.getScheduleDate(), dose.getScheduledTime(), run + 1));
        return response;
    }

    /** TAKEN or MISSED back to PENDING. Already PENDING: no-op. */
    public MedicationDoseResponse undo(Integer scheduleId) {
        MedicationSchedule dose = loadForMark(scheduleId);
        if (MedicationSchedule.PENDING.equals(dose.status())) {
            return MedicationDoseResponse.from(dose, today());
        }
        dose.setTaken(false);
        dose.setMissed(false);
        dose.setTakenAt(null);
        dose.setMarkedBySystemId(null);
        return saveMarked(dose);
    }

    // ==================== HELPERS ====================

    private List<MedicationDoseResponse> day(String patientSystemId, LocalDate date) {
        LocalDate today = today();
        LocalDate day = date != null ? date : today;
        List<MedicationDoseResponse> doses = scheduleRepository
                .findTop200ByPatientSystemIdAndScheduleDateOrderByScheduledTimeAscScheduleIdAsc(patientSystemId, day)
                .stream()
                .map(dose -> MedicationDoseResponse.from(dose, today))
                .toList();
        auditService.logClinicalAccess("READ", RESOURCE, null, patientSystemId);
        return doses;
    }

    /** Checks in contract order: dose exists, caller may mark, date window. The row stays locked. */
    private MedicationSchedule loadForMark(Integer scheduleId) {
        MedicationSchedule dose = scheduleRepository.findForUpdate(scheduleId)
                .orElseThrow(() -> new ResourceNotFoundException(DOSE_NOT_FOUND));
        requireMarkAccess(dose);
        LocalDate today = today();
        LocalDate day = dose.getScheduleDate();
        if (!day.equals(today) && !day.equals(today.minusDays(1))) {
            throw new ConflictException(OUTSIDE_WINDOW);
        }
        return dose;
    }

    /**
     * PATIENT (own doses) or CAREGIVER with MEDICATIONS and MANAGE_CARE_PLAN; doctors and admins never mark.
     * MEDICATIONS is checked first so a caregiver never sees a medication they may not view.
     */
    private void requireMarkAccess(MedicationSchedule dose) {
        authorizationService.requireRole(AuthorizationService.PATIENT, AuthorizationService.CAREGIVER);
        authorizationService.requirePatientAccess(dose.getPatientSystemId(), PatientPermission.MEDICATIONS,
                dose.getScheduleId());
        authorizationService.requirePatientAccess(dose.getPatientSystemId(), PatientPermission.MANAGE_CARE_PLAN,
                dose.getScheduleId());
    }

    private MedicationDoseResponse saveMarked(MedicationSchedule dose) {
        MedicationSchedule saved = scheduleRepository.saveAndFlush(dose);
        auditService.logClinicalAccess("UPDATE", RESOURCE, saved.getScheduleId(), saved.getPatientSystemId());
        return MedicationDoseResponse.from(saved, today());
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }
}
