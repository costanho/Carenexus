package com.carenexus.api.medication.service;

import com.carenexus.api.common.event.PrescriptionIssuedEvent;
import com.carenexus.api.common.event.PrescriptionStatusChangedEvent;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.core.service.ConsultationService;
import com.carenexus.api.medication.dto.response.MedicationSchedulePlanResponse;
import com.carenexus.api.medication.model.MedicationSchedulePlan;
import com.carenexus.api.medication.repository.MedicationScheduleJdbc;
import com.carenexus.api.medication.repository.MedicationScheduleJdbc.PrescriptionSnapshot;
import com.carenexus.api.medication.repository.MedicationScheduleJdbc.StoredPlan;
import com.carenexus.api.medication.repository.MedicationSchedulePlanRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Plans (daily dose times) of prescriptions and the doses that follow from them:
 * - the plan is stored when a prescription is issued (explicit times, else derived from the frequency) and today's
 *   remaining and tomorrow's doses are created;
 * - a prescription leaving ACTIVE loses its future PENDING doses, one returning to ACTIVE gets them back;
 * - the author doctor can read and replace the times (GET / PUT /api/prescriptions/{id}/schedule).
 *
 * The prescriptions table belongs to the prescriptions module and is read with native SQL only.
 */
@Service
@Slf4j
@Transactional
public class MedicationPlanService {

    static final String RESOURCE = MedicationScheduleService.RESOURCE;

    static final String ACTIVE = "ACTIVE";
    static final Set<String> TERMINAL = Set.of("COMPLETED", "CANCELLED", "EXPIRED");

    static final String PRESCRIPTION_NOT_FOUND = "Prescription not found";
    static final String TERMINAL_PRESCRIPTION =
            "The schedule of a completed, cancelled or expired prescription cannot be changed";

    private final MedicationScheduleJdbc jdbc;
    private final MedicationSchedulePlanRepository planRepository;
    private final DoseMaterializer materializer;
    private final AuthorizationService authorizationService;
    private final ConsultationService consultationService;
    private final AuditService auditService;
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    public MedicationPlanService(MedicationScheduleJdbc jdbc, MedicationSchedulePlanRepository planRepository,
                                 DoseMaterializer materializer, AuthorizationService authorizationService,
                                 ConsultationService consultationService, AuditService auditService, Clock clock) {
        this.jdbc = jdbc;
        this.planRepository = planRepository;
        this.materializer = materializer;
        this.authorizationService = authorizationService;
        this.consultationService = consultationService;
        this.auditService = auditService;
        this.clock = clock;
    }

    // ==================== HTTP ====================

    /**
     * The plan of a prescription. Read access MEDICATIONS to the prescription's patient. A prescription without a
     * plan gives {@code times = []}.
     */
    @Transactional(readOnly = true)
    public MedicationSchedulePlanResponse getPlan(Integer prescriptionId) {
        PrescriptionSnapshot rx = jdbc.findPrescription(prescriptionId)
                .orElseThrow(() -> new ResourceNotFoundException(PRESCRIPTION_NOT_FOUND));
        authorizationService.requirePatientAccess(rx.patientSystemId(), PatientPermission.MEDICATIONS, prescriptionId);
        Optional<MedicationSchedulePlan> plan = planRepository.findById(prescriptionId);
        MedicationSchedulePlanResponse response = plan
                .map(p -> new MedicationSchedulePlanResponse(prescriptionId, rx.patientSystemId(),
                        ScheduleTimes.format(ScheduleTimes.fromJson(p.getTimes())), p.getUpdatedBySystemId(),
                        p.getUpdatedAt()))
                .orElseGet(() -> new MedicationSchedulePlanResponse(prescriptionId, rx.patientSystemId(), List.of(),
                        null, null));
        auditService.logClinicalAccess("READ", RESOURCE, prescriptionId, rx.patientSystemId());
        return response;
    }

    /**
     * Replaces the times (author doctor only) and rebuilds the future doses: PENDING doses after now are deleted
     * and, while the prescription is ACTIVE, today's remaining and tomorrow's doses are created from the new times.
     * Sending the stored times again is a no-op.
     */
    public MedicationSchedulePlanResponse updatePlan(Integer prescriptionId, List<String> requestedTimes) {
        authorizationService.requireRole(AuthorizationService.DOCTOR);
        PrescriptionSnapshot rx = jdbc.findPrescription(prescriptionId)
                .orElseThrow(() -> new ResourceNotFoundException(PRESCRIPTION_NOT_FOUND));
        consultationService.requireAuthorDoctor(rx.doctorSystemId(), rx.patientSystemId(), RESOURCE, prescriptionId,
                false);
        // Serialise with status changes and the sweeper on this prescription, and decide on the committed status.
        rx = jdbc.lockPrescription(prescriptionId)
                .orElseThrow(() -> new ResourceNotFoundException(PRESCRIPTION_NOT_FOUND));
        LocalDateTime now = now();
        if (isTerminal(rx, now.toLocalDate())) {
            throw new ConflictException(TERMINAL_PRESCRIPTION);
        }

        List<LocalTime> times = ScheduleTimes.normalise(requestedTimes);
        Optional<StoredPlan> current = jdbc.findPlan(prescriptionId);
        if (current.isPresent() && ScheduleTimes.fromJson(current.get().timesJson()).equals(times)) {
            StoredPlan stored = current.get();
            return new MedicationSchedulePlanResponse(prescriptionId, rx.patientSystemId(), ScheduleTimes.format(times),
                    stored.updatedBySystemId(), stored.updatedAt());
        }

        String caller = authorizationService.currentSystemId();
        jdbc.upsertPlan(prescriptionId, ScheduleTimes.toJson(times), caller, now);
        materializer.removeFuturePending(prescriptionId, now);
        if (ACTIVE.equals(rx.status())) {
            materializer.materialise(rx, times, now);
        }
        auditService.logClinicalAccess("UPDATE", RESOURCE, prescriptionId, rx.patientSystemId());
        return new MedicationSchedulePlanResponse(prescriptionId, rx.patientSystemId(), ScheduleTimes.format(times),
                caller, now);
    }

    // ==================== PRESCRIPTION EVENTS (run in the publisher's transaction) ====================

    /**
     * Stores the plan of a newly issued prescription (explicit times win; otherwise derived from the frequency; no
     * plan when neither gives times) and creates today's remaining and tomorrow's doses while it is ACTIVE.
     * Idempotent.
     */
    public void onPrescriptionIssued(PrescriptionIssuedEvent event) {
        if (event.prescriptionId() == null) {
            return;
        }
        entityManager.flush();
        Optional<PrescriptionSnapshot> found = jdbc.findPrescription(event.prescriptionId());
        if (found.isEmpty()) {
            log.warn("Prescription {} of an issued event does not exist; no medication schedule created",
                    event.prescriptionId());
            return;
        }
        PrescriptionSnapshot rx = found.get();
        List<String> given = event.scheduleTimes() == null ? List.of() : event.scheduleTimes();
        long invalid = given.stream().filter(t -> !ScheduleTimes.isValid(t)).count();
        if (invalid > 0) {
            log.warn("Prescription {}: {} schedule time(s) of the issued event are not HH:mm and were ignored",
                    rx.prescriptionId(), invalid);
        }
        List<LocalTime> times = given.isEmpty()
                ? ScheduleTimes.deriveFromFrequency(event.frequency())
                : ScheduleTimes.normalise(given);
        if (times.isEmpty()) {
            log.info("Prescription {}: no daily times given or derivable from the frequency; no plan stored",
                    rx.prescriptionId());
            return;
        }
        LocalDateTime now = now();
        String author = event.doctorSystemId() != null ? event.doctorSystemId() : rx.doctorSystemId();
        jdbc.upsertPlan(rx.prescriptionId(), ScheduleTimes.toJson(times), author, now);
        if (ACTIVE.equals(rx.status())) {
            materializer.materialise(rx, times, now);
        }
    }

    /**
     * Leaving ACTIVE: future PENDING doses are deleted (the plan is kept). Returning to ACTIVE: today's remaining
     * and tomorrow's doses are created from the kept plan. Idempotent.
     */
    public void onPrescriptionStatusChanged(PrescriptionStatusChangedEvent event) {
        if (event.prescriptionId() == null || event.status() == null) {
            return;
        }
        String previous = event.previousStatus();
        String status = event.status();
        if (status.equals(previous)) {
            return;
        }
        entityManager.flush();
        // The publisher's UPDATE already holds this row lock; taking it explicitly keeps the sweeper and a schedule
        // PUT out of this prescription until the status change commits, whatever way the status was written.
        Optional<PrescriptionSnapshot> rx = jdbc.lockPrescription(event.prescriptionId());
        LocalDateTime now = now();
        if (ACTIVE.equals(previous)) {
            materializer.removeFuturePending(event.prescriptionId(), now);
        } else if (ACTIVE.equals(status)) {
            Optional<StoredPlan> plan = jdbc.findPlan(event.prescriptionId());
            if (rx.isPresent() && ACTIVE.equals(rx.get().status()) && plan.isPresent()) {
                materializer.materialise(rx.get(), ScheduleTimes.fromJson(plan.get().timesJson()), now);
            }
        }
    }

    // ==================== HELPERS ====================

    /** COMPLETED, CANCELLED, EXPIRED, or ACTIVE / ON_HOLD past its expiry date. */
    static boolean isTerminal(PrescriptionSnapshot rx, LocalDate today) {
        if (rx.status() == null || TERMINAL.contains(rx.status())) {
            return true;
        }
        return rx.expiryDate() != null && rx.expiryDate().isBefore(today);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }
}
