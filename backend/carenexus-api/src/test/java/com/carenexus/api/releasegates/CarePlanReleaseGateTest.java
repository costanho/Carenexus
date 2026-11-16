package com.carenexus.api.releasegates;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.event.CarePlanChangedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.carenexus.api.careplans.CarePlanFixtures.body;
import static com.carenexus.api.careplans.CarePlanFixtures.createBody;
import static com.carenexus.api.careplans.CarePlanFixtures.goal;
import static com.carenexus.api.careplans.CarePlanFixtures.intervention;
import static com.carenexus.api.careplans.CarePlanFixtures.today;
import static com.carenexus.api.careplans.CarePlanFixtures.updateBody;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Release gate for care plans (be-care-plans): who may create, read and change a plan, goal progress rules,
 * status transitions, review dates, paging, idempotent create, audit rows, events and patient-circle notifications.
 * Every request commits.
 *
 * Fixtures: patient PAT-00001 with doctor DOC-00001 (care team), otherPatient PAT-00002 with otherDoctor DOC-00002,
 * caregivers CGV-00001 / CGV-00002 (no grants unless a test adds one), admin ADM-00001.
 */
@RecordApplicationEvents
class CarePlanReleaseGateTest extends CommittedIntegrationTest {

    private static final String BASE = "/api/care-plans";
    private static final String NOT_A_ROLE = "Your role is not authorized to perform this action";
    private static final String NOT_AUTHOR = "Only the doctor who created this item can change it";
    private static final String OWN_DATA = "Patients can only access their own data";
    private static final String NOT_CARE_TEAM = "You are not part of this patient's care team";
    private static final String NO_PROXY = "You don't have active proxy access to this patient";
    private static final String NO_VIEW = "Your proxy access for this patient does not allow viewing care plans";
    private static final String NO_MANAGE = "Your proxy access for this patient does not allow managing the care plan";
    private static final String TERMINAL = "A completed or discontinued care plan cannot be changed";
    private static final String DROPPED_ONLY_DOCTOR = "Only the doctor can drop a goal or change a dropped goal";

    @Autowired
    private ApplicationEvents events;

    // ==================== CREATE ====================

    @Test
    void create_byTreatingDoctor_isActiveWithOrderedChildren_andIsIdempotent() throws Exception {
        String key = "careplan-" + clientId().substring(0, 8);
        MvcResult first = doPostWithHeaders(BASE, doctor, createBody(patient.getSystemId()),
                Map.of("Idempotency-Key", key));
        assertEquals(201, first.getResponse().getStatus(), first.getResponse().getContentAsString());
        JsonNode plan = json(first);
        int planId = plan.path("planId").asInt();
        assertEquals(patient.getSystemId(), plan.path("patientSystemId").asText());
        assertEquals(doctor.getSystemId(), plan.path("doctorSystemId").asText());
        assertEquals("ACTIVE", plan.path("status").asText());
        assertEquals(today().toString(), plan.path("startDate").asText());
        assertTrue(plan.path("consultationId").isNull());
        assertTrue(plan.path("lastReviewedAt").isNull());
        assertTrue(plan.path("lastReviewNote").isNull());
        assertEquals(2, plan.path("goals").size());
        assertEquals("Walk 30 minutes five days a week", plan.path("goals").get(0).path("description").asText());
        assertEquals("OPEN", plan.path("goals").get(0).path("status").asText());
        assertEquals(0, plan.path("goals").get(0).path("position").asInt());
        assertEquals(1, plan.path("goals").get(1).path("position").asInt());
        assertTrue(plan.path("goals").get(1).path("targetDate").isNull());
        assertEquals(2, plan.path("interventions").size());
        assertEquals("Dietitian", plan.path("interventions").get(1).path("responsible").asText());
        assertEquals(1, plan.path("interventions").get(1).path("position").asInt());
        // clinic wall-clock, no offset
        LocalDateTime.parse(plan.path("createdAt").asText());

        MvcResult replay = doPostWithHeaders(BASE, doctor, createBody(patient.getSystemId(), "Something else"),
                Map.of("Idempotency-Key", key));
        assertEquals(200, replay.getResponse().getStatus());
        assertEquals(planId, json(replay).path("planId").asInt());
        assertEquals("Blood pressure management", json(replay).path("title").asText());

        assertEquals(1, count("SELECT COUNT(*) FROM care_plans"));
        assertEquals(2, count("SELECT COUNT(*) FROM care_plan_goals WHERE plan_id = " + planId));
        assertEquals(2, count("SELECT COUNT(*) FROM care_plan_interventions WHERE plan_id = " + planId));
        assertEquals(1, auditRows("CREATE", planId));
        List<CarePlanChangedEvent> changes = events.stream(CarePlanChangedEvent.class).toList();
        assertEquals(1, changes.size());
        CarePlanChangedEvent created = changes.get(0);
        assertEquals(CarePlanChangedEvent.CREATED, created.kind());
        assertEquals(planId, created.planId());
        assertEquals(doctor.getSystemId(), created.actorSystemId());
        assertEquals("ACTIVE", created.status());
        assertEquals(today().plusMonths(1), created.reviewDate());
    }

    @Test
    void create_isRefusedForEveryoneButATreatingDoctor() throws Exception {
        Map<String, Object> body = createBody(patient.getSystemId());
        expect(doPost(BASE, otherDoctor, body), 403, NOT_CARE_TEAM);
        expect(doPost(BASE, patient, body), 403, NOT_A_ROLE);
        expect(doPost(BASE, caregiver, body), 403, NOT_A_ROLE);
        expect(doPost(BASE, admin, body), 403, NOT_A_ROLE);
        expect(doPost(BASE, doctor, createBody("PAT-09999")), 404, "Patient not found");

        doctorRepository.findBySystemId(doctor.getSystemId()).ifPresent(profile -> {
            profile.setIsActive(false);
            doctorRepository.saveAndFlush(profile);
        });
        expect(doPost(BASE, doctor, body), 403, "Your clinician account is not active");
        assertEquals(0, count("SELECT COUNT(*) FROM care_plans"));
        assertEquals(0, events.stream(CarePlanChangedEvent.class).count());
    }

    @Test
    void create_dateAndSizeRules_are400() throws Exception {
        LocalDate start = today();
        Map<String, Object> target = createBody(patient.getSystemId());
        target.put("targetDate", start.minusDays(1).toString());
        expectValidation(doPost(BASE, doctor, target), "targetDate: must not be before startDate");

        Map<String, Object> review = createBody(patient.getSystemId());
        review.put("reviewDate", start.minusDays(1).toString());
        expectValidation(doPost(BASE, doctor, review), "reviewDate: must not be before startDate");

        Map<String, Object> goalDate = createBody(patient.getSystemId());
        goalDate.put("goals", List.of(goal("First", null), goal("Second", start.minusDays(3))));
        expectValidation(doPost(BASE, doctor, goalDate), "goals[1].targetDate: must not be before startDate");

        Map<String, Object> tooMany = createBody(patient.getSystemId());
        List<Map<String, Object>> goals = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            goals.add(goal("Goal " + i, null));
        }
        tooMany.put("goals", goals);
        expectValidation(doPost(BASE, doctor, tooMany), "goals: must contain at most 20 items");

        Map<String, Object> noTitle = createBody(patient.getSystemId());
        noTitle.put("title", "  ");
        expectValidation(doPost(BASE, otherDoctor, noTitle), "title: is required");

        assertEquals(0, count("SELECT COUNT(*) FROM care_plans"));
    }

    // ==================== READ ====================

    @Test
    void get_isLimitedToThePatientCircle() throws Exception {
        int planId = createPlan(doctor, patient);
        String url = BASE + "/" + planId;

        assertEquals(200, doGet(url, patient).getResponse().getStatus());
        assertEquals(200, doGet(url, doctor).getResponse().getStatus());
        assertEquals(200, doGet(url, admin).getResponse().getStatus());
        expect(doGet(url, otherPatient), 403, OWN_DATA);
        expect(doGet(url, otherDoctor), 403, NOT_CARE_TEAM);
        expect(doGet(url, caregiver), 403, NO_PROXY);
        expect(doGet(BASE + "/987654", patient), 404, "Care plan not found");
        expect(doGet(BASE + "/abc", patient), 400, "Invalid value for parameter 'id'");

        // caregiver: flags decide; VIEW_ONLY does not block reading
        grantAccess(caregiver, patient, a -> a.setCanViewMedications(true));
        expect(doGet(url, caregiver), 403, NO_VIEW);
        grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));
        assertEquals(200, doGet(url, caregiver).getResponse().getStatus());
        grantAccess(caregiver, patient, a -> {
            a.setAccessLevel("VIEW_ONLY");
            a.setCanManageCarePlan(true);
        });
        assertEquals(200, doGet(url, caregiver).getResponse().getStatus());
        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        assertEquals(200, doGet(url, caregiver).getResponse().getStatus());
        grantAccess(caregiver, patient, a -> {
            a.setCanViewRecords(true);
            a.setExpiresAt(LocalDateTime.now().minusDays(1));
        });
        expect(doGet(url, caregiver), 403, NO_PROXY);

        // patient, doctor, admin, caregiver (records), caregiver (view-only + manage), caregiver (full)
        assertEquals(6, auditRows("READ", planId));
    }

    @Test
    void lists_arePagedNewestFirst_filtered_andScopedToTheCaller() throws Exception {
        int first = createPlan(doctor, patient);
        int second = createPlan(doctor, patient);
        int third = createPlan(doctor, patient);
        assertEquals(200, doPatch(BASE + "/" + first + "/status", doctor, body("status", "ON_HOLD"))
                .getResponse().getStatus());
        int foreign = createPlan(otherDoctor, otherPatient);

        String patientList = BASE + "/patient/" + patient.getSystemId();
        JsonNode page0 = json(doGet(patientList + "?page=0&size=2", patient));
        assertEquals(3, page0.path("totalElements").asInt());
        assertEquals(2, page0.path("totalPages").asInt());
        assertEquals(2, page0.path("size").asInt());
        assertEquals(0, page0.path("page").asInt());
        assertEquals(third, page0.path("content").get(0).path("planId").asInt());
        assertEquals(second, page0.path("content").get(1).path("planId").asInt());
        assertEquals(2, page0.path("content").get(0).path("goals").size());
        assertEquals(2, page0.path("content").get(0).path("interventions").size());
        JsonNode page1 = json(doGet(patientList + "?page=1&size=2", patient));
        assertEquals(first, page1.path("content").get(0).path("planId").asInt());

        JsonNode clamped = json(doGet(patientList + "?size=1000&page=-3", doctor));
        assertEquals(100, clamped.path("size").asInt());
        assertEquals(0, clamped.path("page").asInt());
        assertEquals(3, clamped.path("content").size());

        JsonNode onHold = json(doGet(patientList + "?status=on_hold", patient));
        assertEquals(1, onHold.path("totalElements").asInt());
        assertEquals(first, onHold.path("content").get(0).path("planId").asInt());
        expectValidation(doGet(patientList + "?status=PAUSED", patient),
                "status: must be one of ACTIVE, ON_HOLD, COMPLETED, DISCONTINUED");
        expectValidation(doGet(BASE + "/patient/thomas", patient), "patientSystemId: must be a valid system id");

        expect(doGet(patientList, otherPatient), 403, OWN_DATA);
        expect(doGet(patientList, otherDoctor), 403, NOT_CARE_TEAM);
        expect(doGet(patientList, caregiver), 403, NO_PROXY);
        grantAccess(caregiver, patient, a -> a.setCanManageCarePlan(true));
        assertEquals(3, json(doGet(patientList, caregiver)).path("totalElements").asInt());
        JsonNode missing = json(doGet(BASE + "/patient/PAT-09999", admin));
        assertEquals(0, missing.path("totalElements").asInt());
        assertEquals(0, missing.path("totalPages").asInt());

        String doctorList = BASE + "/doctor";
        JsonNode own = json(doGet(doctorList + "?size=10", doctor));
        assertEquals(3, own.path("totalElements").asInt());
        JsonNode otherOwn = json(doGet(doctorList, otherDoctor));
        assertEquals(1, otherOwn.path("totalElements").asInt());
        assertEquals(foreign, otherOwn.path("content").get(0).path("planId").asInt());
        assertEquals(2, json(doGet(doctorList + "?status=ACTIVE", doctor)).path("totalElements").asInt());
        assertEquals(3, json(doGet(doctorList + "?patientSystemId=" + patient.getSystemId(), doctor))
                .path("totalElements").asInt());
        assertEquals(0, json(doGet(doctorList + "?patientSystemId=PAT-09999", doctor))
                .path("totalElements").asInt());
        expectValidation(doGet(doctorList + "?patientSystemId=nobody", doctor),
                "patientSystemId: must be a valid system id");
        expect(doGet(doctorList, patient), 403, NOT_A_ROLE);
        expect(doGet(doctorList, caregiver), 403, NOT_A_ROLE);
        expect(doGet(doctorList, admin), 403, NOT_A_ROLE);

        // one list-read row per distinct patient of the doctor's page
        assertTrue(count("SELECT COUNT(*) FROM audit_log WHERE action = 'READ' AND resource_type = 'CARE_PLAN' "
                + "AND resource_id IS NULL AND patient_system_id = '" + patient.getSystemId() + "'") >= 6);
    }

    // ==================== AUTHOR WRITES ====================

    @Test
    void update_isAuthorOnly_keepsGoalProgress_andReplacesInterventions() throws Exception {
        JsonNode created = json(doPost(BASE, doctor, createBody(patient.getSystemId())));
        int planId = created.path("planId").asInt();
        int walkGoal = created.path("goals").get(0).path("goalId").asInt();
        int bpGoal = created.path("goals").get(1).path("goalId").asInt();
        assertEquals(200, doPatch(goalUrl(planId, walkGoal), patient,
                body("status", "IN_PROGRESS", "progressNote", "Three days so far")).getResponse().getStatus());

        // a second related doctor may read but not change the plan
        careTeam(otherDoctor, patient);
        Map<String, Object> edit = updateBody("Blood pressure plan (revised)",
                List.of(goal("Cut salt to under 5 g a day", null), goal(walkGoal, "Walk 40 minutes a day", null)),
                List.of(intervention("Nurse check-in", "Monthly", "Nurse")));
        expect(doPut(BASE + "/" + planId, otherDoctor, edit), 403, NOT_AUTHOR);
        expect(doPut(BASE + "/" + planId, patient, edit), 403, NOT_A_ROLE);
        expect(doPut(BASE + "/" + planId, admin, edit), 403, NOT_A_ROLE);
        expect(doPut(BASE + "/424242", doctor, edit), 404, "Care plan not found");

        MvcResult result = doPut(BASE + "/" + planId, doctor, edit);
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode updated = json(result);
        assertEquals("Blood pressure plan (revised)", updated.path("title").asText());
        assertEquals(today().plusMonths(2).toString(), updated.path("reviewDate").asText());
        assertEquals(2, updated.path("goals").size());
        JsonNode newGoal = updated.path("goals").get(0);
        assertEquals("OPEN", newGoal.path("status").asText());
        assertEquals(0, newGoal.path("position").asInt());
        JsonNode kept = updated.path("goals").get(1);
        assertEquals(walkGoal, kept.path("goalId").asInt());
        assertEquals("Walk 40 minutes a day", kept.path("description").asText());
        assertEquals("IN_PROGRESS", kept.path("status").asText());
        assertEquals("Three days so far", kept.path("progressNote").asText());
        assertEquals(1, kept.path("position").asInt());
        assertEquals(0, count("SELECT COUNT(*) FROM care_plan_goals WHERE goal_id = " + bpGoal));
        assertEquals(1, updated.path("interventions").size());
        assertEquals("Nurse check-in", updated.path("interventions").get(0).path("description").asText());
        assertEquals(1, count("SELECT COUNT(*) FROM care_plan_interventions WHERE plan_id = " + planId));

        // goal id rules
        JsonNode other = json(doPost(BASE, doctor, createBody(patient.getSystemId())));
        int foreignGoal = other.path("goals").get(0).path("goalId").asInt();
        expectValidation(doPut(BASE + "/" + planId, doctor, updateBody("T",
                        List.of(goal(foreignGoal, "Not mine", null)), List.of())),
                "goals[0].goalId: does not belong to this care plan");
        expectValidation(doPut(BASE + "/" + planId, doctor, updateBody("T",
                        List.of(goal(walkGoal, "A", null), goal(walkGoal, "B", null)), List.of())),
                "goals[1].goalId: is listed more than once");
        Map<String, Object> noGoals = updateBody("T", null, List.of());
        expectValidation(doPut(BASE + "/" + planId, doctor, noGoals), "goals: is required");

        assertEquals(2, auditRows("UPDATE", planId), "the patient's goal update and one PUT");
        assertEquals(1, events.stream(CarePlanChangedEvent.class)
                .filter(e -> CarePlanChangedEvent.UPDATED.equals(e.kind())).count());
    }

    @Test
    void status_transitions_followTheTable_andTerminalPlansAreReadOnly() throws Exception {
        JsonNode created = json(doPost(BASE, doctor, createBody(patient.getSystemId())));
        int planId = created.path("planId").asInt();
        int goalId = created.path("goals").get(0).path("goalId").asInt();
        String url = BASE + "/" + planId + "/status";

        expect(doPatch(url, otherDoctor, body("status", "ON_HOLD")), 403, NOT_AUTHOR);
        expect(doPatch(url, patient, body("status", "ON_HOLD")), 403, NOT_A_ROLE);
        expectValidation(doPatch(url, doctor, body("status", "PAUSED")),
                "status: must be one of ACTIVE, ON_HOLD, COMPLETED, DISCONTINUED");
        expectValidation(doPatch(url, doctor, body("status", " ")), "status: is required");

        assertEquals("ON_HOLD", json(doPatch(url, doctor, body("status", "on_hold"))).path("status").asText());
        assertEquals("ACTIVE", json(doPatch(url, doctor, body("status", "ACTIVE"))).path("status").asText());
        long auditBefore = auditRows("UPDATE", planId);
        assertEquals("ACTIVE", json(doPatch(url, doctor, body("status", "ACTIVE"))).path("status").asText());
        assertEquals(auditBefore, auditRows("UPDATE", planId), "same status is a no-op");

        assertEquals("COMPLETED", json(doPatch(url, doctor, body("status", "COMPLETED"))).path("status").asText());
        expect(doPatch(url, doctor, body("status", "ACTIVE")), 409, TERMINAL);
        expect(doPatch(url, doctor, body("status", "DISCONTINUED")), 409, TERMINAL);
        assertEquals(200, doPatch(url, doctor, body("status", "COMPLETED")).getResponse().getStatus());
        expect(doPut(BASE + "/" + planId, doctor, updateBody("T", List.of(), List.of())), 409, TERMINAL);
        expect(doPatch(BASE + "/" + planId + "/review", doctor, body()), 409, TERMINAL);
        expect(doPatch(goalUrl(planId, goalId), patient, body("status", "ACHIEVED")), 409, TERMINAL);

        JsonNode plan = json(doGet(BASE + "/" + planId, patient));
        assertEquals("COMPLETED", plan.path("status").asText());
        assertEquals(today().plusMonths(1).toString(), plan.path("reviewDate").asText(),
                "the review date is kept when the plan becomes terminal");

        int other = createPlan(doctor, patient);
        assertEquals("DISCONTINUED", json(doPatch(BASE + "/" + other + "/status", doctor,
                body("status", "DISCONTINUED"))).path("status").asText());

        List<String> statuses = events.stream(CarePlanChangedEvent.class)
                .filter(e -> CarePlanChangedEvent.STATUS_CHANGED.equals(e.kind()))
                .map(CarePlanChangedEvent::status)
                .toList();
        assertEquals(List.of("ON_HOLD", "ACTIVE", "COMPLETED", "DISCONTINUED"), statuses);
    }

    @Test
    void review_stampsTheReview_andValidatesTheNextDate() throws Exception {
        int planId = createPlan(doctor, patient);
        String url = BASE + "/" + planId + "/review";

        expect(doPatch(url, otherDoctor, body()), 403, NOT_AUTHOR);
        expect(doPatch(url, caregiver, body()), 403, NOT_A_ROLE);
        expectValidation(doPatch(url, doctor, body("nextReviewDate", today().minusDays(1).toString())),
                "nextReviewDate: must not be in the past");
        expect(doPatch(url, doctor, body("nextReviewDate", "10/12/2027")), 400, "Malformed request body");

        JsonNode reviewed = json(doPatch(url, doctor,
                body("nextReviewDate", today().plusMonths(3).toString(), "note", "  Good progress.  ")));
        assertEquals(today().plusMonths(3).toString(), reviewed.path("reviewDate").asText());
        assertEquals("Good progress.", reviewed.path("lastReviewNote").asText());
        assertFalse(reviewed.path("lastReviewedAt").isNull());

        JsonNode cleared = json(doPatch(url, doctor, body()));
        assertTrue(cleared.path("reviewDate").isNull());
        assertTrue(cleared.path("lastReviewNote").isNull());

        // a plan that starts in the future: the next review must not be before its start
        Map<String, Object> future = createBody(patient.getSystemId());
        future.put("startDate", today().plusDays(10).toString());
        future.put("targetDate", null);
        future.put("reviewDate", null);
        future.put("goals", List.of());
        int futurePlan = json(doPost(BASE, doctor, future)).path("planId").asInt();
        expectValidation(doPatch(BASE + "/" + futurePlan + "/review", doctor,
                body("nextReviewDate", today().plusDays(2).toString())), "nextReviewDate: must not be before startDate");

        List<CarePlanChangedEvent> reviews = events.stream(CarePlanChangedEvent.class)
                .filter(e -> CarePlanChangedEvent.REVIEWED.equals(e.kind())).toList();
        assertEquals(2, reviews.size());
        assertEquals(today().plusMonths(3), reviews.get(0).reviewDate());
        assertNull(reviews.get(1).reviewDate());
    }

    // ==================== GOAL PROGRESS ====================

    @Test
    void goalProgress_byPatientCaregiverAndAuthor_withTheDroppedRule() throws Exception {
        JsonNode created = json(doPost(BASE, doctor, createBody(patient.getSystemId())));
        int planId = created.path("planId").asInt();
        int goalId = created.path("goals").get(0).path("goalId").asInt();
        String planUpdatedAt = created.path("updatedAt").asText();
        String url = goalUrl(planId, goalId);
        events.clear();

        JsonNode byPatient = json(doPatch(url, patient, body("status", "in_progress", "progressNote", " Started ")));
        JsonNode goal = byPatient.path("goals").get(0);
        assertEquals("IN_PROGRESS", goal.path("status").asText());
        assertEquals("Started", goal.path("progressNote").asText());
        assertEquals(planUpdatedAt, byPatient.path("updatedAt").asText(), "goal progress does not touch the plan");

        // caregivers: MANAGE_CARE_PLAN needed, VIEW_ONLY never writes
        expect(doPatch(url, caregiver, body("status", "ACHIEVED")), 403, NO_PROXY);
        grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));
        expect(doPatch(url, caregiver, body("status", "ACHIEVED")), 403, NO_MANAGE);
        grantAccess(caregiver, patient, a -> {
            a.setAccessLevel("VIEW_ONLY");
            a.setCanManageCarePlan(true);
        });
        expect(doPatch(url, caregiver, body("status", "ACHIEVED")), 403, NO_MANAGE);
        grantAccess(caregiver, patient, a -> a.setCanManageCarePlan(true));
        assertEquals("ACHIEVED", json(doPatch(url, caregiver, body("status", "ACHIEVED")))
                .path("goals").get(0).path("status").asText());
        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        assertTrue(json(doPatch(url, caregiver, body("progressNote", "   ")))
                .path("goals").get(0).path("progressNote").isNull(), "a blank note clears it");

        expect(doPatch(url, otherPatient, body("status", "OPEN")), 403, OWN_DATA);
        expect(doPatch(url, otherCaregiver, body("status", "OPEN")), 403, NO_PROXY);
        expect(doPatch(url, otherDoctor, body("status", "OPEN")), 403, NOT_AUTHOR);
        expect(doPatch(url, admin, body("status", "OPEN")), 403, NOT_A_ROLE);

        // DROPPED is the author doctor's
        expect(doPatch(url, patient, body("status", "DROPPED")), 403, DROPPED_ONLY_DOCTOR);
        assertEquals("DROPPED", json(doPatch(url, doctor, body("status", "DROPPED")))
                .path("goals").get(0).path("status").asText());
        expect(doPatch(url, patient, body("progressNote", "Back at it")), 403, DROPPED_ONLY_DOCTOR);
        expect(doPatch(url, caregiver, body("status", "OPEN")), 403, DROPPED_ONLY_DOCTOR);
        assertEquals("OPEN", json(doPatch(url, doctor, body("status", "OPEN")))
                .path("goals").get(0).path("status").asText());

        expectValidation(doPatch(url, patient, body()), "Provide a status or a progress note");
        expectValidation(doPatch(url, patient, body("status", "DONE")),
                "status: must be one of OPEN, IN_PROGRESS, ACHIEVED, DROPPED");
        int otherPlan = createPlan(doctor, patient);
        expect(doPatch(goalUrl(otherPlan, goalId), patient, body("status", "OPEN")), 404, "Goal not found");
        expect(doPatch(goalUrl(987654, goalId), patient, body("status", "OPEN")), 404, "Care plan not found");

        // patient, caregiver (custom), caregiver (full, note), doctor drop, doctor reopen
        assertEquals(5, auditRows("UPDATE", planId));
        assertEquals(0, events.stream(CarePlanChangedEvent.class)
                .filter(e -> e.planId() == planId).count(), "goal progress publishes no event");
    }

    // ==================== NOTIFICATIONS ====================

    @Test
    void notifications_reachThePatientCircleOnce() throws Exception {
        enableCarePlanAlerts(patient);
        enableCarePlanAlerts(caregiver);
        enableCarePlanAlerts(otherCaregiver);
        enableCarePlanAlerts(doctor);
        grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));
        grantAccess(otherCaregiver, patient, a -> a.setCanViewMedications(true));

        String key = "careplan-notify-" + clientId().substring(0, 8);
        JsonNode created = json(doPostWithHeaders(BASE, doctor, createBody(patient.getSystemId()),
                Map.of("Idempotency-Key", key)));
        int planId = created.path("planId").asInt();
        int goalId = created.path("goals").get(0).path("goalId").asInt();
        doPostWithHeaders(BASE, doctor, createBody(patient.getSystemId()), Map.of("Idempotency-Key", key));

        // goal progress notifies nobody
        assertEquals(200, doPatch(goalUrl(planId, goalId), patient,
                body("status", "IN_PROGRESS")).getResponse().getStatus());
        assertEquals(1, notifications(patient, null));

        assertEquals(1, notifications(patient, "CARE_PLAN_CREATED:" + planId));
        assertEquals(1, notifications(caregiver, "CARE_PLAN_CREATED:" + planId));
        assertEquals(0, notifications(otherCaregiver, null), "no CARE_PLANS permission");
        assertEquals(0, notifications(doctor, null), "the acting doctor is not notified");
        assertEquals("/dashboard/patient?section=care-plans&id=" + planId, actionUrl(patient, planId));
        assertEquals("/dashboard/proxy?section=care-plans&patient=" + patient.getSystemId() + "&id=" + planId,
                actionUrl(caregiver, planId));
        String text = jdbc.queryForObject("SELECT title || ' ' || message FROM notifications WHERE system_id = ? "
                + "AND dedupe_key = ?", String.class, patient.getSystemId(), "CARE_PLAN_CREATED:" + planId);
        assertFalse(text.contains("Blood pressure"), "notification text is generic");
        assertEquals("CARE_PLAN", jdbc.queryForObject("SELECT category FROM notifications WHERE system_id = ? "
                + "AND dedupe_key = ?", String.class, patient.getSystemId(), "CARE_PLAN_CREATED:" + planId));
        assertEquals("CARE_PLANS", jdbc.queryForObject("SELECT target FROM notifications WHERE system_id = ? "
                + "AND dedupe_key = ?", String.class, patient.getSystemId(), "CARE_PLAN_CREATED:" + planId));

        // two edits on one day: one update notification per recipient
        Map<String, Object> edit = updateBody("Revised", List.of(), List.of());
        assertEquals(200, doPut(BASE + "/" + planId, doctor, edit).getResponse().getStatus());
        assertEquals(200, doPut(BASE + "/" + planId, doctor, edit).getResponse().getStatus());
        String updatedKey = "CARE_PLAN_UPDATED:" + planId + ":" + today();
        assertEquals(1, notifications(patient, updatedKey));
        assertEquals(1, notifications(caregiver, updatedKey));

        doPatch(BASE + "/" + planId + "/review", doctor, body("note", "Reviewed"));
        assertEquals(1, notifications(patient, "CARE_PLAN_REVIEWED:" + planId + ":" + today()));

        doPatch(BASE + "/" + planId + "/status", doctor, body("status", "ON_HOLD"));
        doPatch(BASE + "/" + planId + "/status", doctor, body("status", "ON_HOLD"));
        String statusKey = "CARE_PLAN_STATUS:" + planId + ":ON_HOLD:" + today();
        assertEquals(1, notifications(patient, statusKey));
        assertEquals("A care plan is now On hold.", jdbc.queryForObject("SELECT message FROM notifications "
                + "WHERE system_id = ? AND dedupe_key = ?", String.class, patient.getSystemId(), statusKey));

        assertEquals(4, notifications(patient, null));
        assertEquals(4, notifications(caregiver, null));
    }

    @Test
    void notifications_respectTheCarePlanPreference() throws Exception {
        // no user_settings row: care_plan_update_alerts defaults to FALSE (V11)
        int planId = createPlan(doctor, patient);
        assertEquals(0, notifications(patient, null));
        assertEquals(1, auditRows("CREATE", planId));
    }

    // ==================== HELPERS ====================

    private int createPlan(User author, User forPatient) throws Exception {
        MvcResult result = doPost(BASE, author, createBody(forPatient.getSystemId()));
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result).path("planId").asInt();
    }

    private static String goalUrl(int planId, int goalId) {
        return BASE + "/" + planId + "/goals/" + goalId;
    }

    private void expect(MvcResult result, int status, String message) throws Exception {
        assertEquals(status, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(message, message(result));
    }

    private void expectValidation(MvcResult result, String message) throws Exception {
        expect(result, 400, message);
        assertEquals("VALIDATION_FAILED", json(result).path("code").asText());
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private long auditRows(String action, int planId) {
        return count("SELECT COUNT(*) FROM audit_log WHERE action = '" + action + "' AND resource_type = 'CARE_PLAN' "
                + "AND resource_id = " + planId + " AND status = 'SUCCESS'");
    }

    private void careTeam(User forDoctor, User forPatient) {
        careTeamRepository.saveAndFlush(com.carenexus.api.core.model.CareTeam.builder()
                .patientSystemId(forPatient.getSystemId())
                .providerSystemId(forDoctor.getSystemId())
                .role("DOCTOR")
                .assignedAt(LocalDateTime.now())
                .isActive(true)
                .build());
    }

    private void enableCarePlanAlerts(User user) {
        jdbc.update("INSERT INTO user_settings (system_id, care_plan_update_alerts) VALUES (?, TRUE)",
                user.getSystemId());
    }

    private long notifications(User recipient, String dedupeKey) {
        Long value = dedupeKey == null
                ? jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE system_id = ? AND category = 'CARE_PLAN'",
                Long.class, recipient.getSystemId())
                : jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE system_id = ? AND dedupe_key = ?",
                Long.class, recipient.getSystemId(), dedupeKey);
        return value == null ? 0 : value;
    }

    private String actionUrl(User recipient, int planId) {
        return jdbc.queryForObject("SELECT action_url FROM notifications WHERE system_id = ? AND dedupe_key = ?",
                String.class, recipient.getSystemId(), "CARE_PLAN_CREATED:" + planId);
    }
}
