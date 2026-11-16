package com.carenexus.api.medication;

import com.carenexus.api.common.event.PrescriptionIssuedEvent;
import com.carenexus.api.common.event.PrescriptionStatusChangedEvent;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static com.carenexus.api.medication.MedicationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The prescription listeners, driven by publishing the prescription events in a committed transaction exactly as
 * the prescriptions module does (that module is built in parallel): plan storage, materialisation of today's
 * remaining and tomorrow's doses, frequency derivation, stop on leaving ACTIVE and resume.
 */
class PrescriptionScheduleListenerTest extends CommittedIntegrationTest {

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    private void publishInTransaction(Object event) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> publisher.publishEvent(event));
    }

    private int issue(String frequency, List<String> scheduleTimes, LocalDate expiry) {
        LocalDate today = LocalDate.now(clock);
        int rx = insertPrescription(jdbc, patient.getSystemId(), doctor.getSystemId(), "Metformin", frequency,
                "ACTIVE", today, expiry);
        publishInTransaction(new PrescriptionIssuedEvent(rx, null, patient.getSystemId(), doctor.getSystemId(),
                "Metformin", frequency, today, expiry, scheduleTimes));
        return rx;
    }

    /** The given times that are still ahead of the clinic's current time. */
    private List<String> remainingToday(List<String> times, LocalDateTime before) {
        return times.stream().filter(t -> LocalTime.parse(t).isAfter(before.toLocalTime())).toList();
    }

    @Test
    void issuedPrescriptionStoresPlanAndCreatesTodayAndTomorrow() {
        LocalDateTime before = LocalDateTime.now(clock);
        int rx = issue("Twice daily", List.of("20:00", "08:00", "20:00", "00:00", "23:59"), null);
        LocalDate today = before.toLocalDate();

        assertEquals("[\"00:00\", \"08:00\", \"20:00\", \"23:59\"]", planJson(jdbc, rx));
        assertEquals(List.of("00:00", "08:00", "20:00", "23:59"), doseTimes(jdbc, rx, today.plusDays(1)));
        List<String> todays = doseTimes(jdbc, rx, today);
        assertEquals(remainingToday(List.of("00:00", "08:00", "20:00", "23:59"), before).size(), todays.size(),
                "today's doses are the times still ahead: " + todays);
        assertTrue(doseTimes(jdbc, rx, today.plusDays(2)).isEmpty(), "never more than one day ahead");

        Integer patientRows = jdbc.queryForObject("SELECT COUNT(*) FROM medication_schedules WHERE prescription_id = ? "
                        + "AND patient_system_id = ? AND patient_id IS NULL AND dosage = '850mg' "
                        + "AND medication_name = 'Metformin' AND NOT is_taken AND NOT missed "
                        + "AND marked_by_system_id IS NULL", Integer.class, rx, patient.getSystemId());
        assertEquals(countDoses(jdbc, rx), patientRows);
        assertEquals(doctor.getSystemId(), jdbc.queryForObject(
                "SELECT updated_by_system_id FROM medication_schedule_plans WHERE prescription_id = ?", String.class, rx));
    }

    @Test
    void replayedEventIsIdempotent() {
        LocalDate today = LocalDate.now(clock);
        int rx = issue("Once daily", List.of(), null);
        int doses = countDoses(jdbc, rx);
        publishInTransaction(new PrescriptionIssuedEvent(rx, null, patient.getSystemId(), doctor.getSystemId(),
                "Metformin", "Once daily", today, null, List.of()));
        assertEquals(doses, countDoses(jdbc, rx));
        assertEquals(List.of("08:00"), doseTimes(jdbc, rx, today.plusDays(1)));
    }

    @Test
    void timesAreDerivedFromTheFrequencyWhenNoneAreGiven() {
        LocalDate tomorrow = LocalDate.now(clock).plusDays(1);
        int three = issue("Three times daily", List.of(), null);
        assertEquals(List.of("08:00", "14:00", "20:00"), doseTimes(jdbc, three, tomorrow));
        int bedtime = issue("Once daily at bedtime", null, null);
        assertEquals(List.of("20:00"), doseTimes(jdbc, bedtime, tomorrow));
    }

    @Test
    void unknownFrequencyStoresNoPlanAndNoDoses() {
        int rx = issue("Every 8 hours", List.of(), null);
        assertNull(planJson(jdbc, rx));
        assertEquals(0, countDoses(jdbc, rx));
    }

    @Test
    void noDosesAfterTheExpiryDate() {
        LocalDate today = LocalDate.now(clock);
        int rx = issue("Once daily", List.of("23:59"), today);
        assertTrue(doseTimes(jdbc, rx, today.plusDays(1)).isEmpty());
    }

    @Test
    void prescriptionIssuedOnHoldGetsAPlanButNoDoses() {
        LocalDate today = LocalDate.now(clock);
        int rx = insertPrescription(jdbc, patient.getSystemId(), doctor.getSystemId(), "Metformin", "Twice daily",
                "ON_HOLD", today, null);
        publishInTransaction(new PrescriptionIssuedEvent(rx, null, patient.getSystemId(), doctor.getSystemId(),
                "Metformin", "Twice daily", today, null, List.of()));
        assertEquals("[\"08:00\", \"20:00\"]", planJson(jdbc, rx));
        assertEquals(0, countDoses(jdbc, rx));
    }

    @Test
    void leavingActiveRemovesFutureUntakenDosesAndKeepsMarkedOnes() {
        LocalDate today = LocalDate.now(clock);
        LocalDate tomorrow = today.plusDays(1);
        int rx = issue("Twice daily", List.of("08:00", "20:00"), null);
        // A dose of yesterday that is still pending and one marked taken: both are kept.
        int pastPending = insertDose(jdbc, rx, patient.getSystemId(), today.minusDays(1), LocalTime.of(8, 0),
                "PENDING", null);
        int pastTaken = insertDose(jdbc, rx, patient.getSystemId(), today.minusDays(1), LocalTime.of(20, 0),
                "TAKEN", patient.getSystemId());
        // A future dose already marked taken (taken early) is kept as well.
        jdbc.update("UPDATE medication_schedules SET is_taken = TRUE, taken_at = now(), marked_by_system_id = ? "
                + "WHERE prescription_id = ? AND schedule_date = ? AND scheduled_time = '20:00'",
                patient.getSystemId(), rx, java.sql.Date.valueOf(tomorrow));

        setPrescriptionStatus(jdbc, rx, "CANCELLED");
        publishInTransaction(new PrescriptionStatusChangedEvent(rx, patient.getSystemId(), doctor.getSystemId(),
                "ACTIVE", "CANCELLED"));

        assertEquals(List.of("20:00"), doseTimes(jdbc, rx, tomorrow), "only the marked future dose is left");
        assertEquals("PENDING", status(jdbc, pastPending));
        assertEquals("TAKEN", status(jdbc, pastTaken));
        assertNotNull(planJson(jdbc, rx), "the plan is kept");
    }

    @Test
    void onHoldThenActiveAgainRecreatesTheDoses() {
        LocalDate tomorrow = LocalDate.now(clock).plusDays(1);
        int rx = issue("Twice daily", List.of(), null);
        assertEquals(List.of("08:00", "20:00"), doseTimes(jdbc, rx, tomorrow));

        setPrescriptionStatus(jdbc, rx, "ON_HOLD");
        publishInTransaction(new PrescriptionStatusChangedEvent(rx, patient.getSystemId(), doctor.getSystemId(),
                "ACTIVE", "ON_HOLD"));
        assertTrue(doseTimes(jdbc, rx, tomorrow).isEmpty());

        setPrescriptionStatus(jdbc, rx, "ACTIVE");
        publishInTransaction(new PrescriptionStatusChangedEvent(rx, patient.getSystemId(), doctor.getSystemId(),
                "ON_HOLD", "ACTIVE"));
        assertEquals(List.of("08:00", "20:00"), doseTimes(jdbc, rx, tomorrow));
    }

    @Test
    void sameStatusEventChangesNothing() {
        LocalDate tomorrow = LocalDate.now(clock).plusDays(1);
        int rx = issue("Twice daily", List.of(), null);
        publishInTransaction(new PrescriptionStatusChangedEvent(rx, patient.getSystemId(), doctor.getSystemId(),
                "ACTIVE", "ACTIVE"));
        assertEquals(List.of("08:00", "20:00"), doseTimes(jdbc, rx, tomorrow));
    }
}
