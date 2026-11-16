package com.carenexus.api.core.prescriptions;

import com.carenexus.api.common.event.PrescriptionStatusChangedEvent;
import com.carenexus.api.common.job.JobSupport;
import com.carenexus.api.core.job.PrescriptionExpirySweeper;
import com.carenexus.api.core.model.Prescription;
import com.carenexus.api.core.repository.PrescriptionRepository;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.carenexus.api.core.prescriptions.PrescriptionFixtures.CLINIC_ZONE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The prescription-expiry job, driven with a fixed clock: ACTIVE and ON_HOLD rows past their expiry date become
 * EXPIRED with one status event each (previous stored status, prescriber), the expiry day itself is still valid,
 * other statuses are untouched, a re-run does nothing, and more than one batch is handled in one run.
 */
@RecordApplicationEvents
class PrescriptionExpirySweeperTest extends CommittedIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    @Autowired
    private PrescriptionRepository prescriptionRepository;

    @Autowired
    private JobSupport jobSupport;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private ApplicationEvents events;

    private PrescriptionExpirySweeper sweeperAt(LocalDate day, LocalTime time) {
        Clock fixed = Clock.fixed(day.atTime(time).atZone(CLINIC_ZONE).toInstant(), CLINIC_ZONE);
        return new PrescriptionExpirySweeper(jobSupport, prescriptionRepository, publisher, fixed);
    }

    private Prescription save(String name, String status, LocalDate expiry) {
        return prescriptionRepository.saveAndFlush(
                PrescriptionFixtures.row(patient, doctor, name, status, TODAY.minusDays(30), expiry));
    }

    private String status(Prescription row) {
        return prescriptionRepository.findById(row.getPrescriptionId()).orElseThrow().getStatus();
    }

    @Test
    void expiresActiveAndOnHoldRowsPastTheirExpiryDate() {
        Prescription lapsedActive = save("A", Prescription.ACTIVE, TODAY.minusDays(1));
        Prescription lapsedHeld = save("B", Prescription.ON_HOLD, TODAY.minusDays(3));
        Prescription lastDay = save("C", Prescription.ACTIVE, TODAY);
        Prescription noExpiry = save("D", Prescription.ACTIVE, null);
        Prescription completed = save("E", Prescription.COMPLETED, TODAY.minusDays(5));
        Prescription cancelled = save("F", Prescription.CANCELLED, TODAY.minusDays(5));

        // Just after midnight in the clinic zone: yesterday's expiry is over, today's is not.
        int expired = sweeperAt(TODAY, LocalTime.of(0, 5)).sweepOnce();

        assertEquals(2, expired);
        assertEquals(Prescription.EXPIRED, status(lapsedActive));
        assertEquals(Prescription.EXPIRED, status(lapsedHeld));
        assertEquals(Prescription.ACTIVE, status(lastDay));
        assertEquals(Prescription.ACTIVE, status(noExpiry));
        assertEquals(Prescription.COMPLETED, status(completed));
        assertEquals(Prescription.CANCELLED, status(cancelled));

        Map<Integer, PrescriptionStatusChangedEvent> byId = events.stream(PrescriptionStatusChangedEvent.class)
                .collect(Collectors.toMap(PrescriptionStatusChangedEvent::prescriptionId, e -> e));
        assertEquals(2, byId.size());
        PrescriptionStatusChangedEvent first = byId.get(lapsedActive.getPrescriptionId());
        assertEquals(Prescription.ACTIVE, first.previousStatus());
        assertEquals(Prescription.EXPIRED, first.status());
        assertEquals(doctor.getSystemId(), first.doctorSystemId());
        assertEquals(patient.getSystemId(), first.patientSystemId());
        assertEquals(Prescription.ON_HOLD, byId.get(lapsedHeld.getPrescriptionId()).previousStatus());

        // Repeat-safe: nothing left to do, no further events.
        assertEquals(0, sweeperAt(TODAY, LocalTime.of(1, 5)).sweepOnce());
        assertEquals(2, events.stream(PrescriptionStatusChangedEvent.class).count());

        // The next clinic day the last-day prescription expires too.
        assertEquals(1, sweeperAt(TODAY.plusDays(1), LocalTime.of(0, 5)).sweepOnce());
        assertEquals(Prescription.EXPIRED, status(lastDay));
        assertTrue(auditLogRepository.findAll().stream().noneMatch(r -> "PRESCRIPTION".equals(r.getResourceType())),
                "the job writes no audit row");
    }

    @Test
    void handlesMoreThanOneBatchInARun() {
        List<Prescription> rows = new ArrayList<>();
        for (int i = 0; i < PrescriptionExpirySweeper.BATCH_SIZE + 5; i++) {
            rows.add(PrescriptionFixtures.row(patient, doctor, "Rx " + i, Prescription.ACTIVE,
                    TODAY.minusDays(30), TODAY.minusDays(2)));
        }
        prescriptionRepository.saveAllAndFlush(rows);

        int expired = sweeperAt(TODAY, LocalTime.NOON).sweepOnce();

        assertEquals(PrescriptionExpirySweeper.BATCH_SIZE + 5, expired);
        assertEquals(0, prescriptionRepository.findByStatus(Prescription.ACTIVE).size());
        assertEquals(PrescriptionExpirySweeper.BATCH_SIZE + 5, events.stream(PrescriptionStatusChangedEvent.class).count());
    }

    @Test
    void anExpiredRowCanNoLongerBeChangedThroughTheApi() throws Exception {
        Prescription lapsed = save("A", Prescription.ACTIVE, PrescriptionFixtures.clinicToday().minusDays(1));
        sweeperAt(PrescriptionFixtures.clinicToday(), LocalTime.NOON).sweepOnce();

        var result = doPatch("/api/doctors/prescriptions/" + lapsed.getPrescriptionId() + "/mark-active", doctor, null);
        assertEquals(409, result.getResponse().getStatus());
        assertEquals("Prescription is EXPIRED and can no longer be changed", message(result));
    }
}
