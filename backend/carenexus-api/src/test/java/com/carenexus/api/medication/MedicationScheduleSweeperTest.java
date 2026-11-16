package com.carenexus.api.medication;

import com.carenexus.api.common.event.MedicationDoseMissedEvent;
import com.carenexus.api.common.job.JobSupport;
import com.carenexus.api.common.ratelimit.MutableClock;
import com.carenexus.api.medication.job.MedicationScheduleSweeper;
import com.carenexus.api.medication.repository.MedicationScheduleJdbc;
import com.carenexus.api.medication.service.DoseMaterializer;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static com.carenexus.api.medication.MedicationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MedicationScheduleSweeper with a fixed clinic clock (2026-03-10 10:00, Africa/Harare): next-day materialisation
 * for ACTIVE prescriptions only, the missed-dose grace period, the MedicationDoseMissedEvent with its
 * consecutive-miss count, and idempotent re-runs.
 */
class MedicationScheduleSweeperTest extends CommittedIntegrationTest {

    private static final ZoneId CLINIC = ZoneId.of("Africa/Harare");
    private static final LocalDate DAY = LocalDate.of(2026, 3, 10);

    @Autowired
    private JobSupport jobSupport;

    @Autowired
    private MedicationScheduleJdbc scheduleJdbc;

    @Autowired
    private DoseMaterializer materializer;

    private MutableClock clock;
    private List<Object> published;
    private MedicationScheduleSweeper sweeper;

    @BeforeEach
    void buildSweeper() {
        clock = new MutableClock(DAY.atTime(10, 0).atZone(CLINIC).toInstant());
        published = new ArrayList<>();
        sweeper = new MedicationScheduleSweeper(jobSupport, scheduleJdbc, materializer, published::add, clock, 120);
    }

    private int activeWithPlan(String timesJson) {
        int rx = insertPrescription(jdbc, patient.getSystemId(), doctor.getSystemId(), "Metformin", "Twice daily",
                "ACTIVE", DAY.minusDays(9), null);
        storePlan(jdbc, rx, timesJson, doctor.getSystemId());
        return rx;
    }

    private List<MedicationDoseMissedEvent> missedEvents() {
        return published.stream().filter(MedicationDoseMissedEvent.class::isInstance)
                .map(MedicationDoseMissedEvent.class::cast).toList();
    }

    @Test
    void materialisesRemainingTodayAndTomorrowForActivePrescriptionsOnly() {
        int active = activeWithPlan("[\"08:00\", \"12:00\", \"20:00\"]");
        int onHold = insertPrescription(jdbc, patient.getSystemId(), doctor.getSystemId(), "Amlodipine", "Once daily",
                "ON_HOLD", DAY.minusDays(9), null);
        storePlan(jdbc, onHold, "[\"08:00\"]", doctor.getSystemId());
        int cancelled = insertPrescription(jdbc, patient.getSystemId(), doctor.getSystemId(), "Aspirin", "Once daily",
                "CANCELLED", DAY.minusDays(9), null);
        storePlan(jdbc, cancelled, "[\"21:00\"]", doctor.getSystemId());
        int expiresToday = insertPrescription(jdbc, patient.getSystemId(), doctor.getSystemId(), "Ibuprofen",
                "Once daily", "ACTIVE", DAY.minusDays(9), DAY);
        storePlan(jdbc, expiresToday, "[\"21:00\"]", doctor.getSystemId());
        int emptyPlan = activeWithPlan("[]");
        int noPlan = insertPrescription(jdbc, patient.getSystemId(), doctor.getSystemId(), "Zinc", "Once daily",
                "ACTIVE", DAY.minusDays(9), null);

        int changed = sweeper.sweepOnce();

        assertEquals(List.of("12:00", "20:00"), doseTimes(jdbc, active, DAY), "only times after 10:00 today");
        assertEquals(List.of("08:00", "12:00", "20:00"), doseTimes(jdbc, active, DAY.plusDays(1)));
        assertEquals(0, countDoses(jdbc, onHold));
        assertEquals(0, countDoses(jdbc, cancelled));
        assertEquals(List.of("21:00"), doseTimes(jdbc, expiresToday, DAY));
        assertTrue(doseTimes(jdbc, expiresToday, DAY.plusDays(1)).isEmpty(), "nothing after the expiry date");
        assertEquals(0, countDoses(jdbc, emptyPlan));
        assertEquals(0, countDoses(jdbc, noPlan));
        assertEquals(6, changed);

        assertEquals(0, sweeper.sweepOnce(), "a second run creates nothing");

        // The next day: the day after tomorrow appears, today's doses are already there.
        clock.advance(Duration.ofHours(14)); // 2026-03-11 00:00
        sweeper.sweepOnce();
        assertEquals(List.of("08:00", "12:00", "20:00"), doseTimes(jdbc, active, DAY.plusDays(2)));
        assertEquals(List.of("08:00", "12:00", "20:00"), doseTimes(jdbc, active, DAY.plusDays(1)));
    }

    @Test
    void marksDosesMissedOnlyAfterTheGracePeriod() {
        int rx = activeWithPlan("[]");
        int dueAtEight = insertDose(jdbc, rx, patient.getSystemId(), DAY, LocalTime.of(8, 0), "PENDING", null);
        int dueAtNine = insertDose(jdbc, rx, patient.getSystemId(), DAY, LocalTime.of(9, 0), "PENDING", null);
        int taken = insertDose(jdbc, rx, patient.getSystemId(), DAY, LocalTime.of(7, 0), "TAKEN", patient.getSystemId());

        sweeper.sweepOnce(); // 10:00: 08:00 + 120 min is not before 10:00
        assertEquals("PENDING", status(jdbc, dueAtEight));
        assertTrue(missedEvents().isEmpty());

        clock.advance(Duration.ofMinutes(1)); // 10:01
        sweeper.sweepOnce();
        assertEquals("MISSED", status(jdbc, dueAtEight));
        assertEquals("PENDING", status(jdbc, dueAtNine));
        assertEquals("TAKEN", status(jdbc, taken));
        assertNull(jdbc.queryForObject("SELECT marked_by_system_id FROM medication_schedules WHERE schedule_id = ?",
                String.class, dueAtEight), "an automatic MISSED has no marker");

        List<MedicationDoseMissedEvent> events = missedEvents();
        assertEquals(1, events.size());
        MedicationDoseMissedEvent event = events.get(0);
        assertEquals(dueAtEight, event.scheduleId());
        assertEquals(rx, event.prescriptionId());
        assertEquals(patient.getSystemId(), event.patientSystemId());
        assertEquals(DAY, event.scheduleDate());
        assertEquals(LocalTime.of(8, 0), event.scheduledTime());
        assertEquals(1, event.consecutiveMisses(), "the dose before it was taken");

        published.clear();
        sweeper.sweepOnce();
        assertTrue(missedEvents().isEmpty(), "a re-run publishes nothing");
    }

    @Test
    void consecutiveMissesCountTheUnbrokenRunOfTheSamePrescription() {
        int rx = activeWithPlan("[]");
        int other = activeWithPlan("[]");
        insertDose(jdbc, rx, patient.getSystemId(), DAY.minusDays(2), LocalTime.of(8, 0), "TAKEN", patient.getSystemId());
        insertDose(jdbc, rx, patient.getSystemId(), DAY.minusDays(2), LocalTime.of(20, 0), "MISSED", null);
        insertDose(jdbc, rx, patient.getSystemId(), DAY.minusDays(1), LocalTime.of(8, 0), "PENDING", null);
        insertDose(jdbc, rx, patient.getSystemId(), DAY.minusDays(1), LocalTime.of(20, 0), "PENDING", null);
        // Another prescription's misses never count.
        insertDose(jdbc, other, patient.getSystemId(), DAY.minusDays(1), LocalTime.of(12, 0), "MISSED", null);

        sweeper.sweepOnce();

        List<MedicationDoseMissedEvent> events = missedEvents();
        assertEquals(2, events.size());
        assertEquals(LocalTime.of(8, 0), events.get(0).scheduledTime());
        assertEquals(2, events.get(0).consecutiveMisses(), "this dose and the one missed the evening before");
        assertEquals(LocalTime.of(20, 0), events.get(1).scheduledTime());
        assertEquals(3, events.get(1).consecutiveMisses());
    }

    @Test
    void dosesOlderThanTheMarkableWindowAreMarkedMissedWithoutAnEvent() {
        int rx = activeWithPlan("[]");
        int monthsOld = insertDose(jdbc, rx, patient.getSystemId(), DAY.minusDays(90), LocalTime.of(8, 0), "PENDING",
                null);
        int twoDaysOld = insertDose(jdbc, rx, patient.getSystemId(), DAY.minusDays(2), LocalTime.of(20, 0), "PENDING",
                null);
        int yesterday = insertDose(jdbc, rx, patient.getSystemId(), DAY.minusDays(1), LocalTime.of(0, 0), "PENDING",
                null);
        int takenLongAgo = insertDose(jdbc, rx, patient.getSystemId(), DAY.minusDays(30), LocalTime.of(8, 0), "TAKEN",
                patient.getSystemId());

        assertEquals(3, sweeper.sweepOnce());

        assertEquals("MISSED", status(jdbc, monthsOld));
        assertEquals("MISSED", status(jdbc, twoDaysOld));
        assertEquals("MISSED", status(jdbc, yesterday));
        assertEquals("TAKEN", status(jdbc, takenLongAgo));
        assertNull(jdbc.queryForObject("SELECT marked_by_system_id FROM medication_schedules WHERE schedule_id = ?",
                String.class, twoDaysOld), "a silent MISSED has no marker either");

        List<MedicationDoseMissedEvent> events = missedEvents();
        assertEquals(1, events.size(), "only the dose still inside the today/yesterday window is announced");
        assertEquals(yesterday, events.get(0).scheduleId());
        assertEquals(2, events.get(0).consecutiveMisses(), "the earlier, silently missed dose still counts in the run");

        published.clear();
        assertEquals(0, sweeper.sweepOnce(), "a re-run changes nothing");
        assertTrue(missedEvents().isEmpty());
    }

    @Test
    void materialisationVisitsEveryPlannedPrescriptionAcrossPages() {
        int count = 2 * 200 + 5;
        jdbc.update("INSERT INTO prescriptions (patient_system_id, doctor_system_id, medication_name, dosage, "
                        + "frequency, route, refills_allowed, status, prescribed_date, created_at) "
                        + "SELECT ?, ?, 'Metformin', '850mg', 'Once daily', 'ORAL', 0, 'ACTIVE', ?, now() "
                        + "FROM generate_series(1, ?)",
                patient.getSystemId(), doctor.getSystemId(), java.sql.Date.valueOf(DAY.minusDays(1)), count);
        jdbc.update("INSERT INTO medication_schedule_plans (prescription_id, times, updated_by_system_id, updated_at) "
                + "SELECT prescription_id, '[\"08:00\"]'::jsonb, ?, now() FROM prescriptions",
                doctor.getSystemId());

        sweeper.sweepOnce();

        assertEquals(count, jdbc.queryForObject("SELECT COUNT(DISTINCT prescription_id) FROM medication_schedules "
                + "WHERE schedule_date = ?", Integer.class, java.sql.Date.valueOf(DAY.plusDays(1))),
                "every page of ACTIVE prescriptions got tomorrow's dose");
    }

    @Test
    void sweeperNeverRecreatesDosesOfACancelledPrescription() {
        int rx = activeWithPlan("[\"20:00\"]");
        sweeper.sweepOnce();
        assertEquals(List.of("20:00"), doseTimes(jdbc, rx, DAY.plusDays(1)));

        setPrescriptionStatus(jdbc, rx, "CANCELLED");
        jdbc.update("DELETE FROM medication_schedules WHERE prescription_id = ? AND NOT is_taken AND NOT missed "
                + "AND schedule_date > ?", rx, java.sql.Date.valueOf(DAY));
        sweeper.sweepOnce();
        assertTrue(doseTimes(jdbc, rx, DAY.plusDays(1)).isEmpty());
    }

    @Test
    void rejectsANegativeGracePeriod() {
        assertThrows(IllegalArgumentException.class, () -> new MedicationScheduleSweeper(jobSupport, scheduleJdbc,
                materializer, published::add, clock, -1));
    }

    @Test
    void clockIsTheClinicClock() {
        assertEquals(DAY.atTime(10, 0), LocalDateTime.now(clock));
    }
}
