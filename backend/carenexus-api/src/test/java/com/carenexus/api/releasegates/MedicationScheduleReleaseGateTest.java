package com.carenexus.api.releasegates;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.event.MedicationDoseMissedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.carenexus.api.medication.MedicationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Release gates for medication schedules (contract api/be-medication.md): who may read a patient's doses and plan,
 * who may mark a dose, the taken / missed / undo transitions and their idempotency, the today-or-yesterday window,
 * adherence counts, the author-only schedule edit that rebuilds future doses, the 400 envelopes and the audit rows.
 * Runs with committed transactions.
 */
@RecordApplicationEvents
class MedicationScheduleReleaseGateTest extends CommittedIntegrationTest {

    private static final String BASE = "/api/medication-schedules";
    private static final String A1 = "Your role is not authorized to perform this action";
    private static final String A2 = "Only the doctor who created this item can change it";
    private static final String A3 = "Patients can only access their own data";
    private static final String A4 = "You are not part of this patient's care team";
    private static final String A5 = "You don't have active proxy access to this patient";
    private static final String A6_VIEW = "Your proxy access for this patient does not allow viewing medications";
    private static final String A6_MANAGE = "Your proxy access for this patient does not allow managing the care plan";

    @Autowired
    private Clock clock;

    @Autowired
    private ApplicationEvents events;

    private LocalDate today;
    private int rx;
    private int doseToday;
    private int doseYesterday;
    private int doseOld;
    private int doseTomorrow;

    @BeforeEach
    void prescriptionWithDoses() {
        today = LocalDate.now(clock);
        rx = insertPrescription(jdbc, patient.getSystemId(), doctor.getSystemId(), "Metformin", "Twice daily",
                "ACTIVE", today.minusDays(10), null);
        doseToday = insertDose(jdbc, rx, patient.getSystemId(), today, LocalTime.of(20, 0), "PENDING", null);
        insertDose(jdbc, rx, patient.getSystemId(), today, LocalTime.of(8, 0), "TAKEN", patient.getSystemId());
        doseYesterday = insertDose(jdbc, rx, patient.getSystemId(), today.minusDays(1), LocalTime.of(20, 0),
                "PENDING", null);
        doseOld = insertDose(jdbc, rx, patient.getSystemId(), today.minusDays(2), LocalTime.of(8, 0), "PENDING", null);
        doseTomorrow = insertDose(jdbc, rx, patient.getSystemId(), today.plusDays(1), LocalTime.of(8, 0),
                "PENDING", null);
    }

    private String dayUrl(User forPatient, LocalDate day) {
        return BASE + "/patient/" + forPatient.getSystemId() + "?date=" + day;
    }

    private void assertStatus(int expected, MvcResult result) throws Exception {
        assertEquals(expected, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    private void assertForbidden(String expectedMessage, MvcResult result) throws Exception {
        assertStatus(403, result);
        assertEquals(expectedMessage, message(result));
    }

    private void caregiverWith(boolean viewMedications, boolean manageCarePlan, String accessLevel) {
        grantAccess(caregiver, patient, a -> {
            a.setCanViewMedications(viewMedications);
            a.setCanManageCarePlan(manageCarePlan);
            a.setAccessLevel(accessLevel);
        });
    }

    private List<MedicationDoseMissedEvent> missedEvents() {
        return events.stream(MedicationDoseMissedEvent.class).toList();
    }

    // ==================== DAY LISTS ====================

    @Test
    void patientReadsOwnDaySortedWithEveryField() throws Exception {
        MvcResult result = doGet(BASE + "/me?date=" + today, patient);
        assertStatus(200, result);
        JsonNode body = json(result);
        assertEquals(2, body.size());
        assertEquals("08:00", body.get(0).path("scheduledTime").asText());
        assertEquals("TAKEN", body.get(0).path("status").asText());
        assertEquals(patient.getSystemId(), body.get(0).path("markedBySystemId").asText());
        assertFalse(body.get(0).path("takenAt").isNull());
        JsonNode pending = body.get(1);
        assertEquals(doseToday, pending.path("scheduleId").asInt());
        assertEquals(rx, pending.path("prescriptionId").asInt());
        assertEquals(patient.getSystemId(), pending.path("patientSystemId").asText());
        assertEquals("Metformin", pending.path("medicationName").asText());
        assertEquals("850mg", pending.path("dosage").asText());
        assertEquals(today.toString(), pending.path("scheduleDate").asText());
        assertEquals("20:00", pending.path("scheduledTime").asText());
        assertEquals("PENDING", pending.path("status").asText());
        assertTrue(pending.has("takenAt") && pending.path("takenAt").isNull());
        assertTrue(pending.has("markedBySystemId") && pending.path("markedBySystemId").isNull());
        assertTrue(pending.path("markable").asBoolean());
        assertFalse(pending.has("notes"));
        assertFalse(pending.has("missed"));

        assertEquals(1, auditRows(jdbc, "READ", null, patient.getSystemId()));
    }

    @Test
    void dateDefaultsToTodayAndMarkableFollowsTheDay() throws Exception {
        JsonNode todays = json(doGet(BASE + "/me", patient));
        assertEquals(2, todays.size());
        JsonNode old = json(doGet(BASE + "/me?date=" + today.minusDays(2), patient));
        assertFalse(old.get(0).path("markable").asBoolean());
        JsonNode yesterday = json(doGet(BASE + "/me?date=" + today.minusDays(1), patient));
        assertTrue(yesterday.get(0).path("markable").asBoolean());
        JsonNode tomorrow = json(doGet(BASE + "/me?date=" + today.plusDays(1), patient));
        assertFalse(tomorrow.get(0).path("markable").asBoolean());
        assertEquals(0, json(doGet(BASE + "/me?date=" + today.plusDays(5), patient)).size());
    }

    @Test
    void meIsForPatientsOnly() throws Exception {
        assertForbidden(A1, doGet(BASE + "/me", doctor));
        assertForbidden(A1, doGet(BASE + "/me", caregiver));
        assertForbidden(A1, doGet(BASE + "/me", admin));
    }

    @Test
    void readAccessToAPatientsDay() throws Exception {
        assertStatus(200, doGet(dayUrl(patient, today), patient));
        assertStatus(200, doGet(dayUrl(patient, today), doctor));
        assertStatus(200, doGet(dayUrl(patient, today), admin));
        assertForbidden(A3, doGet(dayUrl(patient, today), otherPatient));
        assertForbidden(A4, doGet(dayUrl(patient, today), otherDoctor));
        assertForbidden(A5, doGet(dayUrl(patient, today), caregiver));
        assertForbidden(A5, doGet(dayUrl(patient, today), otherCaregiver));

        caregiverWith(false, true, "CUSTOM");
        assertForbidden(A6_VIEW, doGet(dayUrl(patient, today), caregiver));
        caregiverWith(true, false, "VIEW_ONLY");
        assertStatus(200, doGet(dayUrl(patient, today), caregiver));
        caregiverWith(false, false, "FULL_ACCESS");
        assertStatus(200, doGet(dayUrl(patient, today), caregiver));

        grantAccess(caregiver, patient, a -> {
            a.setCanViewMedications(true);
            a.setExpiresAt(LocalDateTime.now().minusDays(1));
        });
        assertForbidden(A5, doGet(dayUrl(patient, today), caregiver));

        // A patient that does not exist: 403 for others, empty for an admin.
        assertForbidden(A4, doGet(BASE + "/patient/PAT-99999", doctor));
        MvcResult adminUnknown = doGet(BASE + "/patient/PAT-99999", admin);
        assertStatus(200, adminUnknown);
        assertEquals(0, json(adminUnknown).size());

        // Denials are audited.
        Integer denied = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'ACCESS_DENIED' "
                + "AND patient_system_id = ?", Integer.class, patient.getSystemId());
        assertTrue(denied >= 5);
    }

    @Test
    void invalidParametersGive400() throws Exception {
        MvcResult badId = doGet(BASE + "/patient/patient-1", admin);
        assertStatus(400, badId);
        assertEquals("patientSystemId: must be a valid system id", message(badId));
        assertEquals("VALIDATION_FAILED", json(badId).path("code").asText());

        MvcResult badDate = doGet(BASE + "/me?date=10-11-2026", patient);
        assertStatus(400, badDate);
        assertEquals("Invalid value for parameter 'date'", message(badDate));

        MvcResult badDose = doPatch(BASE + "/abc/taken", patient, null);
        assertStatus(400, badDose);
        assertEquals("Invalid value for parameter 'id'", message(badDose));
    }

    @Test
    void unauthenticatedCallsAre401() throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get(BASE + "/me")).andReturn();
        assertEquals(401, result.getResponse().getStatus());
    }

    // ==================== ADHERENCE ====================

    @Test
    void adherenceCountsTheWindowUpToToday() throws Exception {
        insertDose(jdbc, rx, patient.getSystemId(), today.minusDays(3), LocalTime.of(8, 0), "MISSED", null);
        insertDose(jdbc, rx, patient.getSystemId(), today.minusDays(3), LocalTime.of(20, 0), "TAKEN",
                patient.getSystemId());
        insertDose(jdbc, rx, patient.getSystemId(), today.minusDays(40), LocalTime.of(8, 0), "MISSED", null);

        MvcResult result = doGet(BASE + "/patient/" + patient.getSystemId() + "/adherence", patient);
        assertStatus(200, result);
        JsonNode body = json(result);
        assertEquals(patient.getSystemId(), body.path("patientSystemId").asText());
        assertEquals(30, body.path("days").asInt());
        assertEquals(today.minusDays(29).toString(), body.path("from").asText());
        assertEquals(today.toString(), body.path("to").asText());
        assertEquals(2, body.path("taken").asInt());
        assertEquals(1, body.path("missed").asInt());
        assertEquals(3, body.path("pending").asInt(), "tomorrow's dose is not counted");
        assertEquals(6, body.path("total").asInt());
        assertEquals(67, body.path("adherenceRate").asInt());
        assertEquals(1, auditRows(jdbc, "READ", null, patient.getSystemId()));

        JsonNode oneDay = json(doGet(BASE + "/patient/" + patient.getSystemId() + "/adherence?days=1", doctor));
        assertEquals(1, oneDay.path("taken").asInt());
        assertEquals(0, oneDay.path("missed").asInt());
        assertEquals(100, oneDay.path("adherenceRate").asInt());

        JsonNode none = json(doGet(BASE + "/patient/" + otherPatient.getSystemId() + "/adherence?days=90", otherPatient));
        assertEquals(0, none.path("total").asInt());
        assertTrue(none.path("adherenceRate").isNull());
    }

    @Test
    void adherenceValidationAndAccess() throws Exception {
        String url = BASE + "/patient/" + patient.getSystemId() + "/adherence?days=";
        MvcResult zero = doGet(url + "0", patient);
        assertStatus(400, zero);
        assertEquals("days: must be between 1 and 90", message(zero));
        assertEquals("VALIDATION_FAILED", json(zero).path("code").asText());
        MvcResult tooMany = doGet(url + "91", patient);
        assertEquals("days: must be between 1 and 90", message(tooMany));
        MvcResult notNumber = doGet(url + "abc", patient);
        assertStatus(400, notNumber);
        assertEquals("Invalid value for parameter 'days'", message(notNumber));

        assertForbidden(A3, doGet(url + "30", otherPatient));
        assertForbidden(A4, doGet(url + "30", otherDoctor));
        assertForbidden(A5, doGet(url + "30", caregiver));
        caregiverWith(true, false, "CUSTOM");
        assertStatus(200, doGet(url + "30", caregiver));
    }

    // ==================== MARKS ====================

    @Test
    void patientMarksTakenMissedAndUndoWithIdempotency() throws Exception {
        MvcResult taken = doPatch(BASE + "/" + doseToday + "/taken", patient, null);
        assertStatus(200, taken);
        JsonNode body = json(taken);
        assertEquals("TAKEN", body.path("status").asText());
        assertEquals(patient.getSystemId(), body.path("markedBySystemId").asText());
        String takenAt = body.path("takenAt").asText();
        assertTrue(takenAt.startsWith(today.toString()) || takenAt.startsWith(today.plusDays(1).toString()));
        assertEquals(1, auditRows(jdbc, "UPDATE", doseToday, patient.getSystemId()));

        JsonNode again = json(doPatch(BASE + "/" + doseToday + "/taken", patient, null));
        assertEquals("TAKEN", again.path("status").asText());
        assertEquals(takenAt, again.path("takenAt").asText());
        assertEquals(1, auditRows(jdbc, "UPDATE", doseToday, patient.getSystemId()), "a no-op is not audited");

        JsonNode missed = json(doPatch(BASE + "/" + doseToday + "/missed", patient, null));
        assertEquals("MISSED", missed.path("status").asText());
        assertTrue(missed.path("takenAt").isNull());
        assertEquals(1, missedEvents().size());
        MedicationDoseMissedEvent event = missedEvents().get(0);
        assertEquals(doseToday, event.scheduleId());
        assertEquals(rx, event.prescriptionId());
        assertEquals(1, event.consecutiveMisses(), "the 08:00 dose before it was taken");

        doPatch(BASE + "/" + doseToday + "/missed", patient, null);
        assertEquals(1, missedEvents().size(), "a no-op publishes nothing");

        JsonNode undone = json(doPatch(BASE + "/" + doseToday + "/undo", patient, null));
        assertEquals("PENDING", undone.path("status").asText());
        assertTrue(undone.path("markedBySystemId").isNull());
        assertTrue(undone.path("takenAt").isNull());
        assertEquals("PENDING", json(doPatch(BASE + "/" + doseToday + "/undo", patient, null)).path("status").asText());
        assertEquals(3, auditRows(jdbc, "UPDATE", doseToday, patient.getSystemId()));

        // MISSED (set automatically) can be corrected to TAKEN.
        jdbc.update("UPDATE medication_schedules SET missed = TRUE WHERE schedule_id = ?", doseYesterday);
        JsonNode corrected = json(doPatch(BASE + "/" + doseYesterday + "/taken", patient, null));
        assertEquals("TAKEN", corrected.path("status").asText());
        assertEquals("TAKEN", status(jdbc, doseYesterday));
    }

    @Test
    void consecutiveMissesOfAManualMarkIncludeEarlierMisses() throws Exception {
        jdbc.update("UPDATE medication_schedules SET missed = TRUE WHERE schedule_id IN (?, ?)", doseOld, doseYesterday);
        jdbc.update("UPDATE medication_schedules SET is_taken = FALSE, taken_at = NULL, missed = TRUE "
                + "WHERE prescription_id = ? AND schedule_date = ? AND scheduled_time = '08:00'", rx,
                java.sql.Date.valueOf(today));
        doPatch(BASE + "/" + doseToday + "/missed", patient, null);
        assertEquals(4, missedEvents().get(0).consecutiveMisses());
    }

    @Test
    void onlyTodayAndYesterdayCanBeMarked() throws Exception {
        assertStatus(200, doPatch(BASE + "/" + doseYesterday + "/taken", patient, null));
        for (String action : List.of("taken", "missed", "undo")) {
            MvcResult old = doPatch(BASE + "/" + doseOld + "/" + action, patient, null);
            assertStatus(409, old);
            assertEquals("Only doses scheduled for today or yesterday can be updated", message(old));
            assertStatus(409, doPatch(BASE + "/" + doseTomorrow + "/" + action, patient, null));
        }
        assertEquals("PENDING", status(jdbc, doseOld));
        MvcResult missing = doPatch(BASE + "/999999/taken", patient, null);
        assertStatus(404, missing);
        assertEquals("Medication dose not found", message(missing));
    }

    @Test
    void whoMayMarkADose() throws Exception {
        String url = BASE + "/" + doseToday + "/taken";
        assertForbidden(A1, doPatch(url, doctor, null));
        assertForbidden(A1, doPatch(url, admin, null));
        assertForbidden(A3, doPatch(url, otherPatient, null));
        assertForbidden(A5, doPatch(url, caregiver, null));
        assertForbidden(A5, doPatch(url, otherCaregiver, null));

        caregiverWith(false, true, "CUSTOM");
        assertForbidden(A6_VIEW, doPatch(url, caregiver, null));
        caregiverWith(true, false, "CUSTOM");
        assertForbidden(A6_MANAGE, doPatch(url, caregiver, null));
        caregiverWith(true, true, "VIEW_ONLY");
        assertForbidden(A6_MANAGE, doPatch(url, caregiver, null));
        assertEquals("PENDING", status(jdbc, doseToday));

        grantAccess(caregiver, patient, a -> {
            a.setCanViewMedications(true);
            a.setCanManageCarePlan(true);
            a.setExpiresAt(LocalDateTime.now().minusHours(1));
        });
        assertForbidden(A5, doPatch(url, caregiver, null));

        caregiverWith(true, true, "CUSTOM");
        MvcResult marked = doPatch(url, caregiver, null);
        assertStatus(200, marked);
        assertEquals(caregiver.getSystemId(), json(marked).path("markedBySystemId").asText());
        caregiverWith(false, false, "FULL_ACCESS");
        assertStatus(200, doPatch(BASE + "/" + doseToday + "/undo", caregiver, null));
        assertEquals("PENDING", status(jdbc, doseToday));
    }

    // ==================== PLAN (GET / PUT /api/prescriptions/{id}/schedule) ====================

    private String scheduleUrl(int prescriptionId) {
        return "/api/prescriptions/" + prescriptionId + "/schedule";
    }

    @Test
    void planReadWithoutAStoredPlan() throws Exception {
        MvcResult result = doGet(scheduleUrl(rx), patient);
        assertStatus(200, result);
        JsonNode body = json(result);
        assertEquals(rx, body.path("prescriptionId").asInt());
        assertEquals(patient.getSystemId(), body.path("patientSystemId").asText());
        assertEquals(0, body.path("times").size());
        assertTrue(body.path("updatedBySystemId").isNull());
        assertTrue(body.path("updatedAt").isNull());
        assertEquals(1, auditRows(jdbc, "READ", rx, patient.getSystemId()));

        assertStatus(200, doGet(scheduleUrl(rx), doctor));
        assertStatus(200, doGet(scheduleUrl(rx), admin));
        assertForbidden(A3, doGet(scheduleUrl(rx), otherPatient));
        assertForbidden(A4, doGet(scheduleUrl(rx), otherDoctor));
        assertForbidden(A5, doGet(scheduleUrl(rx), caregiver));
        caregiverWith(false, true, "CUSTOM");
        assertForbidden(A6_VIEW, doGet(scheduleUrl(rx), caregiver));
        caregiverWith(true, false, "VIEW_ONLY");
        assertStatus(200, doGet(scheduleUrl(rx), caregiver));

        MvcResult missing = doGet(scheduleUrl(999999), patient);
        assertStatus(404, missing);
        assertEquals("Prescription not found", message(missing));
        MvcResult badId = doGet("/api/prescriptions/abc/schedule", patient);
        assertStatus(400, badId);
        assertEquals("Invalid value for parameter 'prescriptionId'", message(badId));
    }

    @Test
    void authorReplacesTimesAndFutureDosesAreRebuilt() throws Exception {
        storePlan(jdbc, rx, "[\"08:00\", \"20:00\"]", doctor.getSystemId());

        MvcResult result = doPut(scheduleUrl(rx), doctor, Map.of("times", List.of("21:00", "07:30", "13:00", "21:00")));
        assertStatus(200, result);
        JsonNode body = json(result);
        List<String> times = new ArrayList<>();
        body.path("times").forEach(t -> times.add(t.asText()));
        assertEquals(List.of("07:30", "13:00", "21:00"), times);
        assertEquals(doctor.getSystemId(), body.path("updatedBySystemId").asText());
        assertFalse(body.path("updatedAt").isNull());

        // Tomorrow's old 08:00 dose is gone, the new times are there; marked and past doses are untouched.
        assertEquals(List.of("07:30", "13:00", "21:00"), doseTimes(jdbc, rx, today.plusDays(1)));
        assertEquals("PENDING", status(jdbc, doseYesterday));
        assertEquals("PENDING", status(jdbc, doseOld));
        assertTrue(doseTimes(jdbc, rx, today).contains("08:00"), "the taken 08:00 dose of today is kept");
        assertEquals(1, auditRows(jdbc, "UPDATE", rx, patient.getSystemId()));

        // The same times again (in another order) are a no-op.
        String updatedAt = body.path("updatedAt").asText();
        int dosesBefore = countDoses(jdbc, rx);
        JsonNode same = json(doPut(scheduleUrl(rx), doctor, Map.of("times", List.of("13:00", "07:30", "21:00"))));
        assertEquals(updatedAt, same.path("updatedAt").asText());
        assertEquals(dosesBefore, countDoses(jdbc, rx));
        assertEquals(1, auditRows(jdbc, "UPDATE", rx, patient.getSystemId()));

        JsonNode read = json(doGet(scheduleUrl(rx), caregiverReadable()));
        assertEquals(3, read.path("times").size());

        // [] clears the schedule: no future doses remain.
        JsonNode cleared = json(doPut(scheduleUrl(rx), doctor, Map.of("times", List.of())));
        assertEquals(0, cleared.path("times").size());
        assertTrue(doseTimes(jdbc, rx, today.plusDays(1)).isEmpty());
        assertEquals("[]", planJson(jdbc, rx));
    }

    private User caregiverReadable() {
        caregiverWith(true, false, "CUSTOM");
        return caregiver;
    }

    @Test
    void onlyTheAuthorDoctorMayReplaceTimesOfALivePrescription() throws Exception {
        Map<String, Object> body = Map.of("times", List.of("09:00"));
        assertForbidden(A1, doPut(scheduleUrl(rx), patient, body));
        assertForbidden(A1, doPut(scheduleUrl(rx), admin, body));
        assertForbidden(A1, doPut(scheduleUrl(rx), caregiver, body));
        assertForbidden(A2, doPut(scheduleUrl(rx), otherDoctor, body));
        MvcResult missing = doPut(scheduleUrl(999999), doctor, body);
        assertStatus(404, missing);
        assertEquals("Prescription not found", message(missing));
        assertNull(planJson(jdbc, rx));

        setPrescriptionStatus(jdbc, rx, "CANCELLED");
        MvcResult cancelled = doPut(scheduleUrl(rx), doctor, body);
        assertStatus(409, cancelled);
        assertEquals("The schedule of a completed, cancelled or expired prescription cannot be changed",
                message(cancelled));

        // ON_HOLD: the plan is stored, no doses are created until it is ACTIVE again.
        setPrescriptionStatus(jdbc, rx, "ON_HOLD");
        assertStatus(200, doPut(scheduleUrl(rx), doctor, body));
        assertEquals("[\"09:00\"]", planJson(jdbc, rx));
        assertTrue(doseTimes(jdbc, rx, today.plusDays(1)).isEmpty());
    }

    @Test
    void scheduleBodyValidation() throws Exception {
        MvcResult missingTimes = doPut(scheduleUrl(rx), otherDoctor, Map.of());
        assertStatus(400, missingTimes);
        assertEquals("times: is required", message(missingTimes));
        assertEquals("VALIDATION_FAILED", json(missingTimes).path("code").asText());

        MvcResult tooMany = doPut(scheduleUrl(rx), doctor, Map.of("times",
                List.of("01:00", "02:00", "03:00", "04:00", "05:00", "06:00", "07:00", "08:00", "09:00")));
        assertStatus(400, tooMany);
        assertEquals("times: must contain at most 8 items", message(tooMany));

        for (String bad : List.of("8:00", "08:00:00", "24:00", "8am")) {
            MvcResult invalid = doPut(scheduleUrl(rx), doctor, Map.of("times", List.of("08:00", bad)));
            assertStatus(400, invalid);
            assertEquals("times[1]: must be a time in HH:mm format", message(invalid), bad);
        }

        MvcResult nullItem = putRaw(scheduleUrl(rx), "{\"times\": [null]}");
        assertStatus(400, nullItem);
        assertEquals("times[0]: must be a time in HH:mm format", message(nullItem));

        for (String malformed : List.of("{\"times\": [8]}", "{\"times\": \"08:00\"}", "{\"times\": ", "")) {
            MvcResult result = putRaw(scheduleUrl(rx), malformed);
            assertStatus(400, result);
            assertEquals("Malformed request body", message(result), malformed);
        }
        assertNull(planJson(jdbc, rx));
    }

    private MvcResult putRaw(String url, String rawBody) throws Exception {
        return mockMvc.perform(put(url).header("Authorization", tokenFor(doctor))
                .contentType(MediaType.APPLICATION_JSON).content(rawBody)).andReturn();
    }
}
