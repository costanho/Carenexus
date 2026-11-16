package com.carenexus.api.releasegates.crossflow;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.core.model.Doctor;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.Map;

import static com.carenexus.api.core.results.ClinicalResultFixtures.labBody;
import static com.carenexus.api.referrals.ReferralFixtures.BASE;
import static com.carenexus.api.referrals.ReferralFixtures.createBody;
import static com.carenexus.api.referrals.ReferralFixtures.note;
import static com.carenexus.api.referrals.ReferralFixtures.url;
import static com.carenexus.api.releasegates.crossflow.CrossFlowFixtures.count;
import static com.carenexus.api.releasegates.crossflow.CrossFlowFixtures.notificationsWithKey;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Cross-slice gate: a referral created without a platform specialist (be-referrals) is assigned later, accepted by
 * the specialist, and from that moment the specialist reads the patient's records through the shared authorization
 * of be-medical-records and be-lab-imaging (care-team row written by the referral). Notifications follow API-NOTES:
 * creation reaches the patient and caregivers with the referrals permission, assignment reaches the specialist,
 * the decision reaches the referring doctor and the patient only, and the actor is never notified.
 */
class ReferralFlowGateTest extends CommittedIntegrationTest {

    private static final String RECORDS = "/api/medical-records";
    private static final String LAB = "/api/lab-results";

    private User specialist;
    private int recordId;
    private int labId;

    @BeforeEach
    void patientHasRecords() throws Exception {
        specialist = createAndSaveUser(8, "DOC-00003", "cardio@example.com", "DOCTOR");
        Doctor profile = doctorRepository.findBySystemId(specialist.getSystemId()).orElseThrow();
        profile.setSpecialization("Cardiology");
        doctorRepository.saveAndFlush(profile);

        recordId = jdbc.queryForObject("INSERT INTO medical_records (patient_system_id, doctor_system_id, record_type, "
                        + "title, description, source, is_deleted, created_at, updated_at) VALUES (?, ?, 'NOTE', "
                        + "'Hypertension', 'Stage 1 hypertension', 'MANUAL', FALSE, LOCALTIMESTAMP, LOCALTIMESTAMP) "
                        + "RETURNING record_id", Integer.class, patient.getSystemId(), doctor.getSystemId());
        Map<String, Object> lab = new HashMap<>(labBody(patient.getSystemId()));
        lab.put("status", "COMPLETED");
        MvcResult created = doPost(LAB, doctor, lab);
        assertEquals(201, created.getResponse().getStatus(), created.getResponse().getContentAsString());
        labId = json(created).path("resultId").asInt();
    }

    private int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private void assertSpecialistReads(int expected) throws Exception {
        assertEquals(expected, status(doGet(RECORDS + "?patientSystemId=" + patient.getSystemId(), specialist)));
        assertEquals(expected, status(doGet(RECORDS + "/" + recordId, specialist)));
        assertEquals(expected, status(doGet(LAB + "/" + labId, specialist)));
    }

    private int specialistCareTeamRows() {
        return count(jdbc, "SELECT COUNT(*) FROM care_team WHERE patient_system_id = ? AND provider_system_id = ? "
                + "AND is_active = TRUE", patient.getSystemId(), specialist.getSystemId());
    }

    @Test
    void unassignedReferral_assignedLater_accepted_givesTheSpecialistRecordAccess() throws Exception {
        grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));     // REFERRALS permission
        grantAccess(otherCaregiver, patient, a -> a.setCanViewAppointments(true));

        // 1. Created without a specialist
        MvcResult created = doPost(BASE, doctor, createBody(patient.getSystemId(), null, "ROUTINE"));
        assertEquals(201, status(created), created.getResponse().getContentAsString());
        int id = json(created).path("referralId").asInt();
        assertEquals("PENDING", json(created).path("status").asText());
        assertEquals(1, notificationsWithKey(jdbc, patient.getSystemId(), "REFERRAL_CREATED:" + id));
        assertEquals(1, notificationsWithKey(jdbc, caregiver.getSystemId(), "REFERRAL_CREATED:" + id));
        assertEquals(0, notificationsWithKey(jdbc, otherCaregiver.getSystemId(), "REFERRAL_CREATED:" + id));
        assertEquals(0, notificationsWithKey(jdbc, doctor.getSystemId(), "REFERRAL_CREATED:" + id));
        assertSpecialistReads(403);

        // 2. Assigned later: the specialist is told once and can read the referral, still not the records
        MvcResult assigned = doPatch(url(id, "specialist"), doctor, Map.of("specialistSystemId", specialist.getSystemId()));
        assertEquals(200, status(assigned), assigned.getResponse().getContentAsString());
        assertEquals("PLATFORM", json(assigned).path("specialistType").asText());
        String assignedKey = "REFERRAL_ASSIGNED:" + id + ":" + specialist.getSystemId();
        assertEquals(1, notificationsWithKey(jdbc, specialist.getSystemId(), assignedKey));
        assertEquals(200, status(doGet(url(id), specialist)));
        assertEquals(0, specialistCareTeamRows());
        assertSpecialistReads(403);

        // 3. Accepted by the specialist: care-team row, record access
        MvcResult accepted = doPatch(url(id, "accept"), specialist, note("Will see the patient next week"));
        assertEquals(200, status(accepted), accepted.getResponse().getContentAsString());
        JsonNode body = json(accepted);
        assertEquals("ACCEPTED", body.path("status").asText());
        assertEquals(1, specialistCareTeamRows());
        assertSpecialistReads(200);
        MvcResult list = doGet(RECORDS + "?patientSystemId=" + patient.getSystemId(), specialist);
        assertEquals(1, json(list).path("totalElements").asInt(), list.getResponse().getContentAsString());

        // Decision notifications: referring doctor and patient once; the actor and caregivers none
        String acceptedKey = "REFERRAL_ACCEPTED:" + id;
        assertEquals(1, notificationsWithKey(jdbc, doctor.getSystemId(), acceptedKey));
        assertEquals(1, notificationsWithKey(jdbc, patient.getSystemId(), acceptedKey));
        assertEquals(0, notificationsWithKey(jdbc, specialist.getSystemId(), acceptedKey));
        assertEquals(0, notificationsWithKey(jdbc, caregiver.getSystemId(), acceptedKey));

        // A double click changes nothing
        MvcResult again = doPatch(url(id, "accept"), specialist, null);
        assertEquals(200, status(again));
        assertEquals(1, specialistCareTeamRows());
        assertEquals(1, notificationsWithKey(jdbc, patient.getSystemId(), acceptedKey));

        // Reading the records is audited against the patient, as for any related doctor
        assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM audit_log WHERE system_id = ? AND action = 'READ' "
                + "AND resource_type = 'MEDICAL_RECORD' AND resource_id = ? AND status = 'SUCCESS'",
                specialist.getSystemId(), recordId));
    }

    @Test
    void anUnrelatedDoctor_neverGainsAccessThroughSomeoneElsesReferral() throws Exception {
        MvcResult created = doPost(BASE, doctor, createBody(patient.getSystemId(), null, "URGENT"));
        int id = json(created).path("referralId").asInt();
        assertEquals(200, status(doPatch(url(id, "specialist"), doctor,
                Map.of("specialistSystemId", specialist.getSystemId()))));
        assertEquals(200, status(doPatch(url(id, "accept"), specialist, null)));

        assertEquals(403, status(doGet(RECORDS + "/" + recordId, otherDoctor)));
        assertEquals(403, status(doGet(url(id), otherDoctor)));
        assertEquals(403, status(doPatch(url(id, "complete"), otherDoctor, note("x"))));
    }
}
