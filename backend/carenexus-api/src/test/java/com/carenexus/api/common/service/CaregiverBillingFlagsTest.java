package com.carenexus.api.common.service;

import com.carenexus.api.releasegates.CommittedIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The billing permissions (canViewBilling / canManageBilling) on caregiver grants, end to end over HTTP: the
 * patient grants and changes them, FULL_ACCESS sets them, a caregiver can only give them up, and unrelated users
 * cannot touch the grant.
 */
public class CaregiverBillingFlagsTest extends CommittedIntegrationTest {

    private static final String PATIENT_ACCESS = "/api/patients/me/caregiver-access";
    private static final String CAREGIVER_DEPENDENTS = "/api/caregiver/dependents";

    private Map<String, Boolean> billingFlagsInDatabase(int accessId) {
        return jdbc.queryForObject("SELECT can_view_billing, can_manage_billing FROM dependent_access WHERE access_id = ?",
                (rs, i) -> Map.of("view", rs.getBoolean(1), "manage", rs.getBoolean(2)), accessId);
    }

    @Test
    void patientGrantsBillingFlags_theyAreStoredReturnedAndListed() throws Exception {
        MvcResult created = doPost(PATIENT_ACCESS, patient, Map.of(
                "caregiverEmail", caregiver.getEmail(), "relationship", "daughter", "accessLevel", "CUSTOM",
                "canViewBilling", true));
        assertEquals(201, created.getResponse().getStatus(), created.getResponse().getContentAsString());
        JsonNode grant = json(created);
        assertTrue(grant.path("canViewBilling").asBoolean());
        assertFalse(grant.path("canManageBilling").asBoolean());
        int accessId = grant.path("accessId").asInt();
        assertEquals(Map.of("view", true, "manage", false), billingFlagsInDatabase(accessId));

        // the patient's list and the caregiver's list both show the flags
        JsonNode patientList = json(doGet(PATIENT_ACCESS, patient));
        assertTrue(patientList.get(0).path("canViewBilling").asBoolean());
        assertFalse(patientList.get(0).path("canManageBilling").asBoolean());
        JsonNode caregiverList = json(doGet(CAREGIVER_DEPENDENTS, caregiver));
        assertTrue(caregiverList.get(0).path("canViewBilling").asBoolean());
        assertFalse(caregiverList.get(0).path("canManageBilling").asBoolean());

        // the patient adds the manage permission; fields that are not sent stay as they were
        MvcResult updated = doPatch(PATIENT_ACCESS + "/" + accessId, patient, Map.of("canManageBilling", true));
        assertEquals(200, updated.getResponse().getStatus());
        assertTrue(json(updated).path("canViewBilling").asBoolean());
        assertTrue(json(updated).path("canManageBilling").asBoolean());
        assertEquals(Map.of("view", true, "manage", true), billingFlagsInDatabase(accessId));

        // every change is audited against the patient
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE resource_type = 'DEPENDENT_ACCESS' "
                + "AND resource_id = ? AND patient_system_id = ? AND action IN ('CREATE', 'UPDATE') AND status = 'SUCCESS'",
                Integer.class, accessId, patient.getSystemId()));
    }

    @Test
    void grantWithoutBillingFlags_defaultsToNoBillingAccess() throws Exception {
        MvcResult created = doPost(PATIENT_ACCESS, patient, Map.of(
                "caregiverEmail", caregiver.getEmail(), "relationship", "daughter", "accessLevel", "CUSTOM",
                "canViewRecords", true));
        assertEquals(201, created.getResponse().getStatus());
        JsonNode grant = json(created);
        assertFalse(grant.path("canViewBilling").asBoolean());
        assertFalse(grant.path("canManageBilling").asBoolean());
        assertEquals(Map.of("view", false, "manage", false), billingFlagsInDatabase(grant.path("accessId").asInt()));
    }

    @Test
    void fullAccessGrant_setsBothBillingFlags() throws Exception {
        MvcResult created = doPost(PATIENT_ACCESS, patient, Map.of(
                "caregiverEmail", caregiver.getEmail(), "relationship", "daughter", "accessLevel", "FULL_ACCESS"));
        assertEquals(201, created.getResponse().getStatus());
        assertTrue(json(created).path("canViewBilling").asBoolean());
        assertTrue(json(created).path("canManageBilling").asBoolean());
    }

    @Test
    void caregiverCanGiveUpBillingAccess_butCannotGrantItToThemselves() throws Exception {
        int accessId = grantAccess(caregiver, patient, a -> {
            a.setCanViewBilling(true);
            a.setCanViewAppointments(true);
        }).getAccessId();

        // widening is refused, with or without the view flag
        MvcResult widen = doPatch(CAREGIVER_DEPENDENTS + "/" + accessId, caregiver, Map.of("canManageBilling", true));
        assertEquals(403, widen.getResponse().getStatus());
        assertEquals(Map.of("view", true, "manage", false), billingFlagsInDatabase(accessId));

        // narrowing is allowed
        MvcResult narrow = doPatch(CAREGIVER_DEPENDENTS + "/" + accessId, caregiver, Map.of("canViewBilling", false));
        assertEquals(200, narrow.getResponse().getStatus(), narrow.getResponse().getContentAsString());
        assertFalse(json(narrow).path("canViewBilling").asBoolean());
        assertEquals(Map.of("view", false, "manage", false), billingFlagsInDatabase(accessId));

        // and cannot be undone by the caregiver
        MvcResult regain = doPatch(CAREGIVER_DEPENDENTS + "/" + accessId, caregiver, Map.of("canViewBilling", true));
        assertEquals(403, regain.getResponse().getStatus());
        assertEquals(Map.of("view", false, "manage", false), billingFlagsInDatabase(accessId));
    }

    @Test
    void viewOnlyCaregiver_cannotStoreManageBilling_forALaterAccessLevelChange() throws Exception {
        int accessId = grantAccess(caregiver, patient, a -> {
            a.setAccessLevel("VIEW_ONLY");
            a.setCanViewBilling(true);
        }).getAccessId();

        // Under VIEW_ONLY the write flag has no effect yet; storing it would make it take effect as soon as the
        // patient changes the access level, so it is refused and nothing is written.
        MvcResult widen = doPatch(CAREGIVER_DEPENDENTS + "/" + accessId, caregiver, Map.of("canManageBilling", true));
        assertEquals(403, widen.getResponse().getStatus(), widen.getResponse().getContentAsString());
        assertEquals(Map.of("view", true, "manage", false), billingFlagsInDatabase(accessId));

        // The same holds for the other write flags and for a combined request.
        for (Map<String, Object> change : java.util.List.<Map<String, Object>>of(
                Map.of("canScheduleAppointments", true),
                Map.of("canManageCarePlan", true),
                Map.of("canViewBilling", false, "canManageBilling", true))) {
            assertEquals(403, doPatch(CAREGIVER_DEPENDENTS + "/" + accessId, caregiver, change).getResponse().getStatus(),
                    change.toString());
        }
        assertEquals(Map.of("view", true, "manage", false), billingFlagsInDatabase(accessId));
        assertEquals(Boolean.FALSE, jdbc.queryForObject("SELECT can_schedule_appointments OR can_manage_care_plan "
                + "FROM dependent_access WHERE access_id = ?", Boolean.class, accessId));

        // The patient later allows changes: the caregiver holds only what the patient granted.
        MvcResult levelChange = doPatch(PATIENT_ACCESS + "/" + accessId, patient, Map.of("accessLevel", "CUSTOM"));
        assertEquals(200, levelChange.getResponse().getStatus(), levelChange.getResponse().getContentAsString());
        assertEquals(Map.of("view", true, "manage", false), billingFlagsInDatabase(accessId));
    }

    @Test
    void fullAccessCaregiver_cannotTurnOnAStoredBillingFlag_butCanNarrowToALowerLevel() throws Exception {
        // A FULL_ACCESS grant from before the billing flags existed: the level grants everything, the columns are FALSE.
        int accessId = grantAccess(caregiver, patient, a -> {
            a.setAccessLevel("FULL_ACCESS");
            a.setCanViewRecords(true);
        }).getAccessId();

        for (Map<String, Object> change : java.util.List.<Map<String, Object>>of(
                Map.of("canManageBilling", true),
                Map.of("canViewBilling", true),
                Map.of("accessLevel", "CUSTOM", "canManageBilling", true))) {
            MvcResult widen = doPatch(CAREGIVER_DEPENDENTS + "/" + accessId, caregiver, change);
            assertEquals(403, widen.getResponse().getStatus(), change + ": " + widen.getResponse().getContentAsString());
        }
        assertEquals(Map.of("view", false, "manage", false), billingFlagsInDatabase(accessId));
        assertEquals("FULL_ACCESS", jdbc.queryForObject(
                "SELECT access_level FROM dependent_access WHERE access_id = ?", String.class, accessId));

        // Narrowing to VIEW_ONLY while keeping view permissions the level already gave is a pure reduction
        // (this is what the caregiver's access form sends when a lower level is picked).
        MvcResult narrow = doPatch(CAREGIVER_DEPENDENTS + "/" + accessId, caregiver, Map.of(
                "accessLevel", "VIEW_ONLY", "canViewRecords", true, "canViewAppointments", true,
                "canScheduleAppointments", false, "canViewBilling", false));
        assertEquals(200, narrow.getResponse().getStatus(), narrow.getResponse().getContentAsString());
        assertEquals("VIEW_ONLY", jdbc.queryForObject(
                "SELECT access_level FROM dependent_access WHERE access_id = ?", String.class, accessId));
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT can_view_records AND can_view_appointments "
                + "FROM dependent_access WHERE access_id = ?", Boolean.class, accessId));
        assertEquals(Map.of("view", false, "manage", false), billingFlagsInDatabase(accessId));

        // What was given up cannot be taken back, neither effectively nor as a stored flag.
        for (Map<String, Object> change : java.util.List.<Map<String, Object>>of(
                Map.of("canScheduleAppointments", true),
                Map.of("canViewMedications", true),
                Map.of("accessLevel", "FULL_ACCESS"))) {
            assertEquals(403, doPatch(CAREGIVER_DEPENDENTS + "/" + accessId, caregiver, change).getResponse().getStatus(),
                    change.toString());
        }
        assertEquals(Boolean.FALSE, jdbc.queryForObject("SELECT can_schedule_appointments OR can_view_medications "
                + "FROM dependent_access WHERE access_id = ?", Boolean.class, accessId));
    }

    @Test
    void otherUsersCannotChangeTheGrant() throws Exception {
        int accessId = grantAccess(caregiver, patient, a -> a.setCanViewBilling(true)).getAccessId();
        Map<String, Object> change = Map.of("canManageBilling", true);

        assertEquals(403, doPatch(PATIENT_ACCESS + "/" + accessId, otherPatient, change).getResponse().getStatus());
        assertEquals(403, doPatch(PATIENT_ACCESS + "/" + accessId, doctor, change).getResponse().getStatus());
        assertEquals(403, doPatch(PATIENT_ACCESS + "/" + accessId, caregiver, change).getResponse().getStatus());
        assertEquals(403, doPatch(CAREGIVER_DEPENDENTS + "/" + accessId, otherCaregiver, change).getResponse().getStatus());
        assertEquals(403, doPatch(CAREGIVER_DEPENDENTS + "/" + accessId, patient, change).getResponse().getStatus());
        assertEquals(Map.of("view", true, "manage", false), billingFlagsInDatabase(accessId));
    }

    @Test
    void caregiverCreatedDependent_getsTheRequestedBillingFlags() throws Exception {
        MvcResult created = doPost(CAREGIVER_DEPENDENTS, caregiver, Map.of(
                "firstName", "Junior", "lastName", "Dependent",
                "email", "junior-" + clientId().substring(0, 8) + "@example.com", "password", "Str0ng-Passw0rd-1",
                "relationship", "son", "accessLevel", "CUSTOM",
                "canViewBilling", true, "canManageBilling", true));
        assertEquals(201, created.getResponse().getStatus(), created.getResponse().getContentAsString());
        JsonNode grant = json(created);
        assertTrue(grant.path("canViewBilling").asBoolean());
        assertTrue(grant.path("canManageBilling").asBoolean());
        // the new patient got a fresh system id, not one of the fixture ids
        String dependentId = grant.path("patientSystemId").asText();
        assertTrue(dependentId.matches("^PAT-\\d{5}$"), dependentId);
        assertNotEquals(patient.getSystemId(), dependentId);
        assertNotEquals(otherPatient.getSystemId(), dependentId);
    }
}
