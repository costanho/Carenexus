package com.carenexus.api.releasegates.crossflow;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.carenexus.api.core.results.ClinicalResultFixtures.labBody;
import static com.carenexus.api.releasegates.crossflow.CrossFlowFixtures.allNotifications;
import static com.carenexus.api.releasegates.crossflow.CrossFlowFixtures.clinicNow;
import static com.carenexus.api.releasegates.crossflow.CrossFlowFixtures.count;
import static com.carenexus.api.releasegates.crossflow.CrossFlowFixtures.insertAppointment;
import static com.carenexus.api.releasegates.crossflow.CrossFlowFixtures.notificationsOf;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Cross-slice gate: one critical lab result reaches two audiences through two slices, and nobody else.
 * <ul>
 *   <li>be-clinical-notifications (N10): each member of the patient circle (the patient, a caregiver with the lab
 *       flag, a FULL_ACCESS caregiver) gets exactly one HIGH {@code LAB_RESULT} notification;</li>
 *   <li>be-ai-generators: one CRITICAL_RESULT alert and exactly one {@code AI_ALERT} notification for each related
 *       doctor (care team and appointment), the recording doctor included, and no other notification;</li>
 *   <li>repeated updates add nothing; a caregiver without the lab flag and an unrelated doctor get nothing.</li>
 * </ul>
 */
class CriticalResultFlowGateTest extends CommittedIntegrationTest {

    private static final String LAB = "/api/lab-results";

    private User fullAccessCaregiver;
    private User appointmentDoctor;

    @BeforeEach
    void widenTheCircle() {
        fullAccessCaregiver = createAndSaveUser(8, "CGV-00003", "full.cg@example.com", "CAREGIVER");
        appointmentDoctor = createAndSaveUser(9, "DOC-00003", "cardio@example.com", "DOCTOR");
        grantAccess(caregiver, patient, a -> a.setCanViewLabResults(true));
        grantAccess(fullAccessCaregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        grantAccess(otherCaregiver, patient, a -> a.setCanViewAppointments(true)); // no lab flag
        // A second related doctor: an upcoming appointment, no care-team row (inserted without booking events)
        insertAppointment(jdbc, patient.getSystemId(), appointmentDoctor.getSystemId(),
                clinicNow().plusDays(3).withHour(10).withMinute(0), "IN_PERSON", "SCHEDULED");
    }

    private int createCriticalLab() throws Exception {
        Map<String, Object> body = new HashMap<>(labBody(patient.getSystemId()));
        body.put("status", "COMPLETED");
        body.put("isCritical", true);
        MvcResult created = doPost(LAB, doctor, body);
        assertEquals(201, created.getResponse().getStatus(), created.getResponse().getContentAsString());
        assertEquals("CRITICAL", json(created).path("status").asText());
        return json(created).path("resultId").asInt();
    }

    private void assertOnlyNotification(User recipient, String type, String priority, String dedupeKey) {
        List<Map<String, Object>> rows = notificationsOf(jdbc, recipient.getSystemId());
        assertEquals(1, rows.size(), recipient.getSystemId() + ": " + rows);
        Map<String, Object> row = rows.get(0);
        assertEquals(type, row.get("type"), recipient.getSystemId() + ": " + row);
        assertEquals(priority, row.get("priority"), recipient.getSystemId() + ": " + row);
        if (dedupeKey != null) {
            assertEquals(dedupeKey, row.get("dedupe_key"), recipient.getSystemId() + ": " + row);
        }
    }

    private void assertEverybodyNotifiedExactlyOnce(int resultId) {
        String critical = "RESULT_CRITICAL:LAB:" + resultId;
        for (User member : List.of(patient, caregiver, fullAccessCaregiver)) {
            assertOnlyNotification(member, "LAB_RESULT", "HIGH", critical);
        }
        int alertId = jdbc.queryForObject("SELECT alert_id FROM ai_alerts WHERE dedupe_key = ?", Integer.class,
                "CRITICAL_RESULT:LAB:" + resultId);
        for (User relatedDoctor : List.of(doctor, appointmentDoctor)) {
            assertOnlyNotification(relatedDoctor, "AI_ALERT", "CRITICAL", "AI_ALERT:" + alertId);
        }
        for (User outsider : List.of(otherCaregiver, otherDoctor, otherPatient, admin)) {
            assertEquals(0, allNotifications(jdbc, outsider.getSystemId()), outsider.getSystemId());
        }
        assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM ai_alerts WHERE patient_system_id = ?",
                patient.getSystemId()), "one alert, and no disease screening for a lab result");
    }

    @Test
    void aCriticalLab_notifiesTheCircleOnceAtHighPriority_andEachRelatedDoctorOnceThroughTheAlert() throws Exception {
        int resultId = createCriticalLab();
        assertEverybodyNotifiedExactlyOnce(resultId);

        // The relevant readers can see it; the caregiver without the lab flag cannot
        assertEquals(200, doGet(LAB + "/" + resultId, caregiver).getResponse().getStatus());
        assertEquals(403, doGet(LAB + "/" + resultId, otherCaregiver).getResponse().getStatus());
        assertEquals(1, json(doGet("/api/ai-alerts", appointmentDoctor)).path("content").size());
        assertEquals(0, json(doGet("/api/ai-alerts", otherDoctor)).path("content").size());
    }

    @Test
    void repeatedUpdatesOfTheCriticalResult_addNothing() throws Exception {
        int resultId = createCriticalLab();

        MvcResult edited = doPut(LAB + "/" + resultId, doctor, Map.of("unit", "g/dL"));
        assertEquals(200, edited.getResponse().getStatus(), edited.getResponse().getContentAsString());
        MvcResult again = doPut(LAB + "/" + resultId, doctor, Map.of("status", "CRITICAL", "isCritical", true));
        assertEquals(200, again.getResponse().getStatus(), again.getResponse().getContentAsString());
        MvcResult sameStatus = doPatch(LAB + "/" + resultId + "/status?status=CRITICAL", appointmentDoctor, null);
        assertEquals(200, sameStatus.getResponse().getStatus(), sameStatus.getResponse().getContentAsString());

        assertEverybodyNotifiedExactlyOnce(resultId);
    }

    @Test
    void aCaregiverWithoutTheLabFlag_getsNothing_evenWithEveryOtherFlag() throws Exception {
        grantAccess(otherCaregiver, patient, a -> {
            a.setCanViewAppointments(true);
            a.setCanViewMedications(true);
            a.setCanViewRecords(true);
            a.setCanViewConsultations(true);
            a.setCanViewImaging(true);
        });

        int resultId = createCriticalLab();

        assertEquals(0, allNotifications(jdbc, otherCaregiver.getSystemId()));
        assertOnlyNotification(caregiver, "LAB_RESULT", "HIGH", "RESULT_CRITICAL:LAB:" + resultId);
    }
}
