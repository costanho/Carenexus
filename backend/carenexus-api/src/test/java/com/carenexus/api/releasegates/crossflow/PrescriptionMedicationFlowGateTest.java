package com.carenexus.api.releasegates.crossflow;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.medication.MedicationFixtures;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import com.carenexus.api.reminders.auto.job.MedicationDoseReminderJob;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.carenexus.api.core.prescriptions.PrescriptionFixtures.createBody;
import static com.carenexus.api.releasegates.crossflow.CrossFlowFixtures.clinicToday;
import static com.carenexus.api.releasegates.crossflow.CrossFlowFixtures.count;
import static com.carenexus.api.releasegates.crossflow.CrossFlowFixtures.notificationsWithKey;
import static com.carenexus.api.releasegates.crossflow.CrossFlowFixtures.notificationsWithKeyPrefix;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-slice gate: issuing a prescription (be-prescriptions) materialises doses through the medication plan listener
 * (be-medication); the doctor's schedule edit rebuilds them; the dose reminder job (be-reminder-jobs), driven with
 * {@code sweepOnce} at fixed clinic times, reminds the patient circle exactly once at the new time and never at the
 * old one; cancelling the prescription removes the future doses so neither time is reminded.
 *
 * Every assertion is about tomorrow's doses, which always lie in the future whatever the time the test runs.
 */
class PrescriptionMedicationFlowGateTest extends CommittedIntegrationTest {

    private static final String PRESCRIPTIONS = "/api/prescriptions";
    private static final String DOSE_KEY = "DOSE_REMINDER:";

    @Autowired
    private MedicationDoseReminderJob doseJob;

    private LocalDate tomorrow() {
        return clinicToday().plusDays(1);
    }

    private int issue(List<String> scheduleTimes, String frequency) throws Exception {
        Map<String, Object> body = createBody(patient);
        if (frequency != null) {
            body.put("frequency", frequency);
        }
        if (scheduleTimes != null) {
            body.put("scheduleTimes", scheduleTimes);
        }
        MvcResult created = doPost(PRESCRIPTIONS, doctor, body);
        assertEquals(201, created.getResponse().getStatus(), created.getResponse().getContentAsString());
        return json(created).path("prescriptionId").asInt();
    }

    private List<String> planTimes(int prescriptionId, User as) throws Exception {
        MvcResult plan = doGet(PRESCRIPTIONS + "/" + prescriptionId + "/schedule", as);
        assertEquals(200, plan.getResponse().getStatus(), plan.getResponse().getContentAsString());
        List<String> times = new ArrayList<>();
        json(plan).path("times").forEach(t -> times.add(t.asText()));
        return times;
    }

    private List<String> tomorrowsDoses(int prescriptionId) {
        return MedicationFixtures.doseTimes(jdbc, prescriptionId, tomorrow());
    }

    private int doseId(int prescriptionId, LocalDate day, String time) {
        return jdbc.queryForObject("SELECT schedule_id FROM medication_schedules WHERE prescription_id = ? "
                        + "AND schedule_date = ? AND to_char(scheduled_time, 'HH24:MI') = ?", Integer.class,
                prescriptionId, java.sql.Date.valueOf(day), time);
    }

    private int doseReminders(User recipient) {
        return notificationsWithKeyPrefix(jdbc, recipient.getSystemId(), DOSE_KEY);
    }

    private int sweepTomorrowAt(int hour, int minute) {
        return doseJob.sweepOnce(tomorrow().atTime(LocalTime.of(hour, minute)));
    }

    // ==================== ISSUE, EDIT, REMIND ====================

    @Test
    void issuedWithTimes_editedSchedule_remindsTheCircleOnceAtTheNewTime_andNeverAtTheOldOne() throws Exception {
        grantAccess(caregiver, patient, a -> a.setCanViewMedications(true));
        grantAccess(otherCaregiver, patient, a -> a.setCanViewAppointments(true)); // no medication flag

        int rx = issue(List.of("09:00"), "Once daily");
        assertEquals(List.of("09:00"), planTimes(rx, patient), "explicit times win over the frequency");
        assertEquals(List.of("09:00"), tomorrowsDoses(rx));

        MvcResult edited = doPut(PRESCRIPTIONS + "/" + rx + "/schedule", doctor, Map.of("times", List.of("10:30")));
        assertEquals(200, edited.getResponse().getStatus(), edited.getResponse().getContentAsString());
        assertEquals(List.of("10:30"), tomorrowsDoses(rx), "the old future dose is replaced");
        assertEquals(List.of("10:30"), planTimes(rx, caregiver));
        int newDose = doseId(rx, tomorrow(), "10:30");

        // The old time comes and goes: nothing is due
        assertEquals(0, sweepTomorrowAt(9, 0));
        assertEquals(0, sweepTomorrowAt(9, 30));
        assertEquals(0, doseReminders(patient));
        assertEquals(0, doseReminders(caregiver));

        // The new time: exactly one reminder per member of the circle, never twice
        assertEquals(1, sweepTomorrowAt(10, 31));
        assertEquals(0, sweepTomorrowAt(10, 45));
        assertEquals(1, notificationsWithKey(jdbc, patient.getSystemId(), DOSE_KEY + newDose));
        assertEquals(1, notificationsWithKey(jdbc, caregiver.getSystemId(), DOSE_KEY + newDose));
        assertEquals(1, doseReminders(patient));
        assertEquals(1, doseReminders(caregiver));
        assertEquals(0, doseReminders(otherCaregiver));
        assertEquals(0, doseReminders(doctor));
        Map<String, Object> row = jdbc.queryForMap("SELECT type, category, title FROM notifications "
                + "WHERE system_id = ? AND dedupe_key = ?", patient.getSystemId(), DOSE_KEY + newDose);
        assertEquals("MEDICATION", row.get("type"));
        assertEquals("MEDICATION_REMINDER", row.get("category"));

        // The patient sees the dose of tomorrow at the new time in the schedule API
        MvcResult day = doGet("/api/medication-schedules/me?date=" + tomorrow(), patient);
        assertEquals(200, day.getResponse().getStatus(), day.getResponse().getContentAsString());
        assertTrue(day.getResponse().getContentAsString().contains("\"10:30\""), day.getResponse().getContentAsString());
    }

    @Test
    void issuedWithoutTimes_derivesDosesFromTheFrequency_andRemindsAtThoseTimes() throws Exception {
        int rx = issue(null, "Twice daily");
        assertEquals(List.of("08:00", "20:00"), planTimes(rx, doctor));
        assertEquals(List.of("08:00", "20:00"), tomorrowsDoses(rx));

        assertEquals(1, sweepTomorrowAt(8, 0));
        assertEquals(1, notificationsWithKey(jdbc, patient.getSystemId(), DOSE_KEY + doseId(rx, tomorrow(), "08:00")));
        assertEquals(1, sweepTomorrowAt(20, 10));
        assertEquals(1, notificationsWithKey(jdbc, patient.getSystemId(), DOSE_KEY + doseId(rx, tomorrow(), "20:00")));
        assertEquals(2, doseReminders(patient));
    }

    @Test
    void cancellingThePrescription_stopsTheRemindersAtTheOldAndTheNewTime() throws Exception {
        grantAccess(caregiver, patient, a -> a.setCanViewMedications(true));
        int rx = issue(List.of("09:00"), null);
        MvcResult edited = doPut(PRESCRIPTIONS + "/" + rx + "/schedule", doctor, Map.of("times", List.of("10:30")));
        assertEquals(200, edited.getResponse().getStatus(), edited.getResponse().getContentAsString());
        assertEquals(List.of("10:30"), tomorrowsDoses(rx));

        MvcResult cancelled = doDelete(PRESCRIPTIONS + "/" + rx, doctor);
        assertEquals(204, cancelled.getResponse().getStatus(), cancelled.getResponse().getContentAsString());
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM prescriptions WHERE prescription_id = ?",
                String.class, rx));
        assertTrue(tomorrowsDoses(rx).isEmpty(), "future pending doses are removed");
        assertEquals(List.of("10:30"), planTimes(rx, patient), "the plan itself is kept");

        assertEquals(0, sweepTomorrowAt(9, 5));
        assertEquals(0, sweepTomorrowAt(10, 35));
        assertEquals(0, doseReminders(patient));
        assertEquals(0, doseReminders(caregiver));
        assertEquals(1, notificationsWithKey(jdbc, patient.getSystemId(), "RX_CANCELLED:" + rx),
                "the patient is told once that the prescription was cancelled");

        // A cancelled prescription's schedule cannot be changed any more, so no dose can come back
        MvcResult again = doPut(PRESCRIPTIONS + "/" + rx + "/schedule", doctor, Map.of("times", List.of("11:00")));
        assertEquals(409, again.getResponse().getStatus(), again.getResponse().getContentAsString());
        assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM medication_schedules WHERE prescription_id = ? "
                + "AND schedule_date > ?", rx, java.sql.Date.valueOf(clinicToday())));
    }
}
