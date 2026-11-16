package com.carenexus.api.releasegates;

import com.carenexus.api.common.model.AuditLog;
import com.carenexus.api.reminders.ReminderFixtures;
import com.carenexus.api.reminders.model.Reminder;
import com.carenexus.api.reminders.repository.ReminderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.carenexus.api.reminders.ReminderFixtures.clinicToday;
import static com.carenexus.api.reminders.ReminderFixtures.createBody;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Release gate for reminders (be-reminders): who may list, create, edit, pause, resume and delete a patient's
 * reminders, validation texts, idempotent create, the per-patient cap, automatic-reminder rules, doctor-addressed
 * reminders hidden from the API, pagination and audit rows. Every request commits.
 *
 * Fixtures: patient PAT-00001 with doctor DOC-00001 (care team), otherPatient PAT-00002 with otherDoctor DOC-00002,
 * caregivers CGV-00001 / CGV-00002 (no grants unless a test adds one), admin ADM-00001.
 */
class ReminderReleaseGateTest extends CommittedIntegrationTest {

    private static final String BASE = "/api/reminders";
    private static final String NOT_A_ROLE = "Your role is not authorized to perform this action";
    private static final String OWN_DATA = "Patients can only access their own data";
    private static final String NOT_CARE_TEAM = "You are not part of this patient's care team";
    private static final String NO_PROXY = "You don't have active proxy access to this patient";
    private static final String NO_MANAGE = "Your proxy access for this patient does not allow managing the care plan";
    private static final String NOT_CREATOR =
            "Only the person who created this reminder, the patient or an administrator can delete it";

    @Autowired
    private ReminderRepository reminderRepository;

    private String listUrl(String patientSystemId) {
        return BASE + "/patient/" + patientSystemId;
    }

    private JsonNode create(Map<String, Object> body, com.carenexus.api.auth.model.User as) throws Exception {
        MvcResult result = doPost(BASE, as, body);
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result);
    }

    private void assertStatus(int expected, MvcResult result) throws Exception {
        assertEquals(expected, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    private void assertError(int status, String message, MvcResult result) throws Exception {
        assertStatus(status, result);
        assertEquals(message, message(result));
    }

    private Reminder saveAutomatic(String recipient, String sourceType, int sourceId) {
        LocalDate start = clinicToday().plusDays(5);
        String type = Reminder.SOURCE_CONSULTATION.equals(sourceType) ? Reminder.TYPE_CHECK_IN
                : Reminder.SOURCE_CARE_PLAN.equals(sourceType) ? Reminder.TYPE_CARE_PLAN : Reminder.TYPE_LAB_FOLLOWUP;
        return reminderRepository.saveAndFlush(ReminderFixtures.automatic(patient.getSystemId(), recipient, type,
                sourceType, sourceId, start, start.atTime(8, 0)));
    }

    private List<AuditLog> reminderAudit(String action) {
        return auditLogRepository.findAll().stream()
                .filter(row -> "REMINDER".equals(row.getResourceType()) && action.equals(row.getAction()))
                .toList();
    }

    // ==================== CREATE ====================

    @Test
    void create_byThePatient_returnsTheFullObject_andIsAudited() throws Exception {
        Map<String, Object> body = createBody(patient.getSystemId());
        body.put("type", "vital_sign");
        body.put("frequency", " daily ");
        body.put("title", "  Morning blood pressure  ");
        body.put("isActive", false);
        body.put("sourceType", "CARE_PLAN");
        JsonNode reminder = create(body, patient);

        LocalDate start = clinicToday().plusDays(1);
        assertTrue(reminder.path("reminderId").asInt() > 0);
        assertEquals(patient.getSystemId(), reminder.path("patientSystemId").asText());
        assertEquals("VITAL_SIGN", reminder.path("type").asText());
        assertEquals("Morning blood pressure", reminder.path("title").asText());
        assertEquals("07:30", reminder.path("reminderTime").asText());
        assertEquals("DAILY", reminder.path("frequency").asText());
        assertEquals(start.toString(), reminder.path("startDate").asText());
        assertEquals(start.plusDays(30).toString(), reminder.path("endDate").asText());
        assertTrue(reminder.path("isActive").asBoolean());
        assertEquals("ACTIVE", reminder.path("status").asText());
        assertEquals(start + "T07:30:00", reminder.path("nextDueAt").asText());
        assertTrue(reminder.path("lastSentAt").isNull());
        assertEquals("MANUAL", reminder.path("sourceType").asText());
        assertTrue(reminder.path("sourceId").isNull());
        assertEquals("Manual", reminder.path("sourceLabel").asText());
        assertEquals(patient.getSystemId(), reminder.path("createdBySystemId").asText());
        assertTrue(reminder.path("canDelete").asBoolean());
        assertFalse(reminder.path("createdAt").asText().endsWith("Z"));

        Reminder stored = reminderRepository.findById(reminder.path("reminderId").asInt()).orElseThrow();
        assertEquals(patient.getSystemId(), stored.getRecipientSystemId());
        assertEquals(1, reminderAudit("CREATE").size());
        assertEquals(patient.getSystemId(), reminderAudit("CREATE").get(0).getPatientSystemId());
        // No notification at creation
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class));
    }

    @Test
    void create_onceIgnoresTheEndDate() throws Exception {
        Map<String, Object> body = createBody(patient.getSystemId());
        body.put("frequency", "ONCE");
        JsonNode reminder = create(body, patient);
        assertTrue(reminder.path("endDate").isNull());
    }

    @Test
    void create_authorisationMatrix() throws Exception {
        // the treating doctor
        JsonNode byDoctor = create(createBody(patient.getSystemId()), doctor);
        assertEquals(doctor.getSystemId(), byDoctor.path("createdBySystemId").asText());

        assertError(403, NOT_CARE_TEAM, doPost(BASE, otherDoctor, createBody(patient.getSystemId())));
        assertError(403, OWN_DATA, doPost(BASE, otherPatient, createBody(patient.getSystemId())));
        assertError(403, NOT_A_ROLE, doPost(BASE, admin, createBody(patient.getSystemId())));

        // caregiver: no grant, a grant without the permission, VIEW_ONLY with the flag, expired, then allowed
        assertError(403, NO_PROXY, doPost(BASE, caregiver, createBody(patient.getSystemId())));
        grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));
        assertError(403, NO_MANAGE, doPost(BASE, caregiver, createBody(patient.getSystemId())));
        grantAccess(caregiver, patient, a -> {
            a.setAccessLevel("VIEW_ONLY");
            a.setCanManageCarePlan(true);
        });
        assertError(403, NO_MANAGE, doPost(BASE, caregiver, createBody(patient.getSystemId())));
        grantAccess(caregiver, patient, a -> {
            a.setCanManageCarePlan(true);
            a.setExpiresAt(LocalDateTime.now().minusDays(1));
        });
        assertError(403, NO_PROXY, doPost(BASE, caregiver, createBody(patient.getSystemId())));
        grantAccess(caregiver, patient, a -> a.setCanManageCarePlan(true));
        JsonNode byCaregiver = create(createBody(patient.getSystemId()), caregiver);
        assertEquals(caregiver.getSystemId(), byCaregiver.path("createdBySystemId").asText());
        assertEquals(patient.getSystemId(), byCaregiver.path("patientSystemId").asText());
        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        create(createBody(patient.getSystemId()), caregiver);

        assertEquals(3, reminderRepository.count());
    }

    @Test
    void create_validationTexts() throws Exception {
        Map<String, Object> missing = createBody(patient.getSystemId());
        missing.remove("title");
        MvcResult result = doPost(BASE, patient, missing);
        assertError(400, "title: is required", result);
        assertEquals("VALIDATION_FAILED", json(result).path("code").asText());
        assertEquals("title", json(result).path("errors").get(0).path("field").asText());

        Map<String, Object> badId = createBody("patient-1");
        assertError(400, "patientSystemId: must be a valid system id", doPost(BASE, patient, badId));

        Map<String, Object> longTitle = createBody(patient.getSystemId());
        longTitle.put("title", "x".repeat(81));
        assertError(400, "title: must be at most 80 characters", doPost(BASE, patient, longTitle));

        Map<String, Object> longMessage = createBody(patient.getSystemId());
        longMessage.put("message", "x".repeat(401));
        assertError(400, "message: must be at most 400 characters", doPost(BASE, patient, longMessage));

        Map<String, Object> badTime = createBody(patient.getSystemId());
        badTime.put("reminderTime", "7:30");
        assertError(400, "reminderTime: must be a time in HH:mm format", doPost(BASE, patient, badTime));

        Map<String, Object> badType = createBody(patient.getSystemId());
        badType.put("type", "WALK");
        assertError(400, "type: must be one of MEDICATION, APPOINTMENT, LAB_FOLLOWUP, CARE_PLAN, CHECK_IN, VITAL_SIGN",
                doPost(BASE, patient, badType));

        Map<String, Object> custom = createBody(patient.getSystemId());
        custom.put("frequency", "CUSTOM");
        assertError(400, "frequency: must be one of ONCE, DAILY, WEEKLY, MONTHLY", doPost(BASE, patient, custom));

        Map<String, Object> endBefore = createBody(patient.getSystemId());
        endBefore.put("endDate", clinicToday().toString());
        assertError(400, "endDate: must not be before startDate", doPost(BASE, patient, endBefore));

        Map<String, Object> pastOnce = createBody(patient.getSystemId());
        pastOnce.put("frequency", "ONCE");
        pastOnce.put("startDate", clinicToday().minusDays(1).toString());
        assertError(400, "The reminder has no upcoming time. Choose a later date or time.",
                doPost(BASE, patient, pastOnce));

        Map<String, Object> badDate = createBody(patient.getSystemId());
        badDate.put("startDate", "11/12/2026");
        assertError(400, "Malformed request body", doPost(BASE, patient, badDate));

        // An unknown enum value from an unauthorised caller is refused as 403 first
        assertError(403, OWN_DATA, doPost(BASE, otherPatient, badType));
        assertEquals(0, reminderRepository.count());
    }

    @Test
    void create_isIdempotent() throws Exception {
        String key = "reminder-" + clientId().substring(0, 8);
        MvcResult first = doPostWithHeaders(BASE, patient, createBody(patient.getSystemId()),
                Map.of("Idempotency-Key", key));
        assertStatus(201, first);
        Map<String, Object> changed = createBody(patient.getSystemId());
        changed.put("title", "Something else");
        MvcResult replay = doPostWithHeaders(BASE, patient, changed, Map.of("Idempotency-Key", key));
        assertStatus(200, replay);
        assertEquals(json(first).path("reminderId").asInt(), json(replay).path("reminderId").asInt());
        assertEquals("Morning blood pressure", json(replay).path("title").asText());
        assertEquals(1, reminderRepository.count());
    }

    @Test
    void create_capsActiveManualRemindersAtFifty() throws Exception {
        LocalDate start = clinicToday().plusDays(1);
        List<Reminder> rows = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            rows.add(ReminderFixtures.manual(patient.getSystemId(), patient.getSystemId(), Reminder.TYPE_VITAL_SIGN,
                    Reminder.FREQUENCY_DAILY, start, null, LocalTime.of(7, 30), start.atTime(7, 30)));
        }
        reminderRepository.saveAllAndFlush(rows);
        assertError(409, "This patient already has 50 active reminders. Pause or delete one before adding another.",
                doPost(BASE, patient, createBody(patient.getSystemId())));

        // A paused one does not count
        int paused = rows.get(0).getReminderId();
        assertStatus(200, doPatch(BASE + "/" + paused, patient, Map.of("isActive", false)));
        create(createBody(patient.getSystemId()), patient);
    }

    // ==================== LIST ====================

    @Test
    void list_authorisationMatrix_andEnvelope() throws Exception {
        JsonNode own = create(createBody(patient.getSystemId()), patient);
        create(createBody(otherPatient.getSystemId()), otherPatient);

        MvcResult page = doGet(listUrl(patient.getSystemId()), patient);
        assertStatus(200, page);
        JsonNode body = json(page);
        assertEquals(1, body.path("totalElements").asInt());
        assertEquals(1, body.path("totalPages").asInt());
        assertEquals(0, body.path("page").asInt());
        assertEquals(20, body.path("size").asInt());
        assertEquals(own.path("reminderId").asInt(), body.path("content").get(0).path("reminderId").asInt());

        assertStatus(200, doGet(listUrl(patient.getSystemId()), doctor));
        assertStatus(200, doGet(listUrl(patient.getSystemId()), admin));
        assertError(403, OWN_DATA, doGet(listUrl(patient.getSystemId()), otherPatient));
        assertError(403, NOT_CARE_TEAM, doGet(listUrl(patient.getSystemId()), otherDoctor));
        assertError(403, NO_PROXY, doGet(listUrl(patient.getSystemId()), caregiver));

        // Any active grant may read, whatever its flags; an expired one may not
        grantAccess(caregiver, patient, a -> { });
        MvcResult readOnly = doGet(listUrl(patient.getSystemId()), caregiver);
        assertStatus(200, readOnly);
        assertFalse(json(readOnly).path("content").get(0).path("canDelete").asBoolean());
        grantAccess(caregiver, patient, a -> a.setExpiresAt(LocalDateTime.now().minusMinutes(1)));
        assertError(403, NO_PROXY, doGet(listUrl(patient.getSystemId()), caregiver));

        // size clamp, bad parameters
        assertEquals(100, json(doGet(listUrl(patient.getSystemId()) + "?size=500", patient)).path("size").asInt());
        assertEquals(1, json(doGet(listUrl(patient.getSystemId()) + "?size=0", patient)).path("size").asInt());
        assertError(400, "Invalid value for parameter 'active'",
                doGet(listUrl(patient.getSystemId()) + "?active=maybe", patient));
        MvcResult badId = doGet(listUrl("patient-1"), patient);
        assertStatus(400, badId);
        assertEquals("VALIDATION_FAILED", json(badId).path("code").asText());

        // unknown patient for an admin: an empty page
        JsonNode empty = json(doGet(listUrl("PAT-99999"), admin));
        assertEquals(0, empty.path("totalElements").asInt());
        assertEquals(0, empty.path("totalPages").asInt());

        assertFalse(reminderAudit("READ").isEmpty());
    }

    @Test
    void list_showsOnlyPatientAddressedReminders_activeFirst_withSourceLabels() throws Exception {
        JsonNode manual = create(createBody(patient.getSystemId()), patient);
        Reminder followUp = saveAutomatic(patient.getSystemId(), Reminder.SOURCE_CONSULTATION, 42);
        Reminder carePlanPatient = saveAutomatic(patient.getSystemId(), Reminder.SOURCE_CARE_PLAN, 41);
        saveAutomatic(doctor.getSystemId(), Reminder.SOURCE_CARE_PLAN, 41);
        saveAutomatic(doctor.getSystemId(), Reminder.SOURCE_LAB_RESULT, 7);
        assertStatus(200, doPatch(BASE + "/" + carePlanPatient.getReminderId(), patient, Map.of("isActive", false)));

        JsonNode content = json(doGet(listUrl(patient.getSystemId()), patient)).path("content");
        assertEquals(3, content.size());
        // active first, soonest next due time first; the paused one last
        assertEquals(manual.path("reminderId").asInt(), content.get(0).path("reminderId").asInt());
        assertEquals(followUp.getReminderId(), content.get(1).path("reminderId").asInt());
        assertEquals("Follow-up", content.get(1).path("sourceLabel").asText());
        assertEquals(42, content.get(1).path("sourceId").asInt());
        assertFalse(content.get(1).path("canDelete").asBoolean());
        assertEquals(carePlanPatient.getReminderId(), content.get(2).path("reminderId").asInt());
        assertEquals("Care plan", content.get(2).path("sourceLabel").asText());
        assertEquals("PAUSED", content.get(2).path("status").asText());
        assertTrue(content.get(2).path("nextDueAt").isNull());

        JsonNode active = json(doGet(listUrl(patient.getSystemId()) + "?active=true", patient)).path("content");
        assertEquals(2, active.size());
        JsonNode inactive = json(doGet(listUrl(patient.getSystemId()) + "?active=false", patient)).path("content");
        assertEquals(1, inactive.size());
        assertEquals(3, json(doGet(listUrl(patient.getSystemId()) + "?active=", patient)).path("totalElements")
                .asInt());
    }

    // ==================== PATCH ====================

    @Test
    void pauseAndResume_byPatientDoctorAndPermittedCaregiver() throws Exception {
        JsonNode created = create(createBody(patient.getSystemId()), patient);
        String url = BASE + "/" + created.path("reminderId").asInt();

        JsonNode paused = json(doPatch(url, patient, Map.of("isActive", false)));
        assertFalse(paused.path("isActive").asBoolean());
        assertEquals("PAUSED", paused.path("status").asText());
        assertTrue(paused.path("nextDueAt").isNull());
        // the current value is a no-op
        assertStatus(200, doPatch(url, patient, Map.of("isActive", false)));

        JsonNode resumed = json(doPatch(url, doctor, Map.of("isActive", true)));
        assertEquals("ACTIVE", resumed.path("status").asText());
        assertEquals(created.path("nextDueAt").asText(), resumed.path("nextDueAt").asText());

        assertError(403, NOT_CARE_TEAM, doPatch(url, otherDoctor, Map.of("isActive", false)));
        assertError(403, OWN_DATA, doPatch(url, otherPatient, Map.of("isActive", false)));
        assertError(403, NO_PROXY, doPatch(url, caregiver, Map.of("isActive", false)));
        grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));
        assertError(403, NO_MANAGE, doPatch(url, caregiver, Map.of("isActive", false)));
        grantAccess(caregiver, patient, a -> a.setCanManageCarePlan(true));
        assertEquals("PAUSED", json(doPatch(url, caregiver, Map.of("isActive", false))).path("status").asText());
        assertEquals("ACTIVE", json(doPatch(url, admin, Map.of("isActive", true))).path("status").asText());

        // 2 pauses + 2 resumes changed something; the no-op wrote nothing
        assertEquals(4, reminderAudit("UPDATE").size());
    }

    @Test
    void viewOnlyExpiredAndRevokedGrants_cannotChangeOrDeleteAReminder() throws Exception {
        // The caregiver created the reminder while the grant allowed it
        grantAccess(caregiver, patient, a -> a.setCanManageCarePlan(true));
        int reminderId = create(createBody(patient.getSystemId()), caregiver).path("reminderId").asInt();
        String url = BASE + "/" + reminderId;

        // VIEW_ONLY blocks writes even with the care-plan flag
        grantAccess(caregiver, patient, a -> {
            a.setAccessLevel("VIEW_ONLY");
            a.setCanManageCarePlan(true);
        });
        assertError(403, NO_MANAGE, doPatch(url, caregiver, Map.of("isActive", false)));
        assertError(403, NO_MANAGE, doPatch(url, caregiver, Map.of("title", "Changed")));
        assertError(403, NO_MANAGE, doDelete(url, caregiver));

        // Expired
        grantAccess(caregiver, patient, a -> {
            a.setCanManageCarePlan(true);
            a.setExpiresAt(java.time.LocalDateTime.now().minusMinutes(5));
        });
        assertError(403, NO_PROXY, doPatch(url, caregiver, Map.of("isActive", false)));
        assertError(403, NO_PROXY, doDelete(url, caregiver));

        // Revoked (the patient ended the grant)
        grantAccess(caregiver, patient, a -> {
            a.setCanManageCarePlan(true);
            a.setIsActive(false);
            a.setRevokedAt(java.time.LocalDateTime.now().minusMinutes(1));
        });
        assertError(403, NO_PROXY, doPatch(url, caregiver, Map.of("isActive", false)));
        assertError(403, NO_PROXY, doDelete(url, caregiver));

        // Nothing changed
        Reminder stored = reminderRepository.findById(reminderId).orElseThrow();
        assertTrue(stored.getIsActive());
        assertEquals("Morning blood pressure", stored.getTitle());
        assertEquals(0, reminderAudit("UPDATE").size());
        assertEquals(0, reminderAudit("DELETE").size());
    }

    @Test
    void edit_manualReminder_recomputesTheNextDueTime_andValidatesTheMergedState() throws Exception {
        JsonNode created = create(createBody(patient.getSystemId()), patient);
        String url = BASE + "/" + created.path("reminderId").asInt();
        LocalDate start = clinicToday().plusDays(1);

        JsonNode edited = json(doPatch(url, patient, Map.of("reminderTime", "06:15", "clearEndDate", true)));
        assertEquals("06:15", edited.path("reminderTime").asText());
        assertTrue(edited.path("endDate").isNull());
        assertEquals(start + "T06:15:00", edited.path("nextDueAt").asText());

        JsonNode textOnly = json(doPatch(url, patient, Map.of("title", "Evening check", "type", "medication")));
        assertEquals("Evening check", textOnly.path("title").asText());
        assertEquals("MEDICATION", textOnly.path("type").asText());
        assertEquals(start + "T06:15:00", textOnly.path("nextDueAt").asText());

        assertError(400, "Provide at least one field to change", doPatch(url, patient, Map.of()));
        assertError(400, "title: must not be blank", doPatch(url, patient, Map.of("title", "  ")));
        assertError(400, "endDate: cannot be set and cleared in the same request",
                doPatch(url, patient, Map.of("endDate", start.plusDays(3).toString(), "clearEndDate", true)));
        assertError(400, "endDate: must not be before startDate",
                doPatch(url, patient, Map.of("endDate", start.minusDays(1).toString())));
        assertError(400, "frequency: must be one of ONCE, DAILY, WEEKLY, MONTHLY",
                doPatch(url, patient, Map.of("frequency", "CUSTOM")));
        assertError(400, "The reminder has no upcoming time. Choose a later date or time.",
                doPatch(url, patient, Map.of("frequency", "ONCE", "startDate", clinicToday().minusDays(2).toString())));
        assertError(404, "Reminder not found", doPatch(BASE + "/999999", patient, Map.of("isActive", false)));
    }

    @Test
    void endedReminder_cannotBeResumedAlone_butCanWithANewSchedule() throws Exception {
        LocalDate past = clinicToday().minusDays(3);
        Reminder ended = reminderRepository.saveAndFlush(ReminderFixtures.manual(patient.getSystemId(),
                patient.getSystemId(), Reminder.TYPE_VITAL_SIGN, Reminder.FREQUENCY_ONCE, past, null,
                LocalTime.of(7, 30), null));
        String url = BASE + "/" + ended.getReminderId();
        assertEquals("ENDED", json(doGet(listUrl(patient.getSystemId()), patient)).path("content").get(0)
                .path("status").asText());

        assertError(409, "This reminder has ended. It has no upcoming time to resume.",
                doPatch(url, patient, Map.of("isActive", true)));
        LocalDate next = clinicToday().plusDays(2);
        JsonNode resumed = json(doPatch(url, patient, Map.of("isActive", true, "startDate", next.toString())));
        assertEquals("ACTIVE", resumed.path("status").asText());
        assertEquals(next + "T07:30:00", resumed.path("nextDueAt").asText());
    }

    @Test
    void automaticReminders_canOnlyBePausedOrResumed_andDoctorAddressedOnesAreHidden() throws Exception {
        Reminder followUp = saveAutomatic(patient.getSystemId(), Reminder.SOURCE_CONSULTATION, 42);
        Reminder review = saveAutomatic(doctor.getSystemId(), Reminder.SOURCE_LAB_RESULT, 7);
        String url = BASE + "/" + followUp.getReminderId();

        assertError(409, "Automatic reminders can only be paused or resumed",
                doPatch(url, patient, Map.of("title", "Mine now")));
        assertEquals("PAUSED", json(doPatch(url, patient, Map.of("isActive", false))).path("status").asText());
        assertEquals("ACTIVE", json(doPatch(url, patient, Map.of("isActive", true))).path("status").asText());
        assertError(409, "Automatic reminders cannot be deleted. Pause them instead.", doDelete(url, patient));

        for (var user : List.of(patient, doctor, admin)) {
            assertError(404, "Reminder not found",
                    doPatch(BASE + "/" + review.getReminderId(), user, Map.of("isActive", false)));
            assertError(404, "Reminder not found", doDelete(BASE + "/" + review.getReminderId(), user));
        }
        assertTrue(reminderRepository.findById(review.getReminderId()).orElseThrow().active());
    }

    // ==================== DELETE ====================

    @Test
    void delete_byThePatientAdminOrCreator_only() throws Exception {
        grantAccess(caregiver, patient, a -> a.setCanManageCarePlan(true));
        int byDoctor = create(createBody(patient.getSystemId()), doctor).path("reminderId").asInt();
        int byCaregiver = create(createBody(patient.getSystemId()), caregiver).path("reminderId").asInt();
        int byPatient = create(createBody(patient.getSystemId()), patient).path("reminderId").asInt();

        // canDelete as each caller sees it
        JsonNode asCaregiver = json(doGet(listUrl(patient.getSystemId()), caregiver)).path("content");
        for (JsonNode row : asCaregiver) {
            assertEquals(row.path("reminderId").asInt() == byCaregiver, row.path("canDelete").asBoolean());
        }

        assertError(403, NOT_CREATOR, doDelete(BASE + "/" + byDoctor, caregiver));
        assertError(403, NOT_CREATOR, doDelete(BASE + "/" + byPatient, doctor));
        assertError(403, NOT_CARE_TEAM, doDelete(BASE + "/" + byDoctor, otherDoctor));
        assertError(403, OWN_DATA, doDelete(BASE + "/" + byPatient, otherPatient));

        assertStatus(204, doDelete(BASE + "/" + byDoctor, doctor));
        assertStatus(204, doDelete(BASE + "/" + byCaregiver, patient));
        assertStatus(204, doDelete(BASE + "/" + byPatient, admin));
        assertError(404, "Reminder not found", doDelete(BASE + "/" + byPatient, patient));
        assertEquals(0, reminderRepository.count());
        assertEquals(3, reminderAudit("DELETE").size());

        // A caregiver whose grant was revoked cannot delete even what they created
        int again = create(createBody(patient.getSystemId()), caregiver).path("reminderId").asInt();
        grantAccess(caregiver, patient, a -> {
            a.setCanManageCarePlan(true);
            a.setIsActive(false);
        });
        assertError(403, NO_PROXY, doDelete(BASE + "/" + again, caregiver));
    }
}
