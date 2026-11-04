package com.carenexus.api.releasegates;

import com.carenexus.api.core.model.DependentAccess;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Release gates for caregiver communication consent (CONTRACT.md section 5). */
class CaregiverConsentReleaseGateTest extends CommittedIntegrationTest {

    private DependentAccess unapprovedGrant() {
        return dependentAccessRepository.saveAndFlush(DependentAccess.builder()
                .patientSystemId(patient.getSystemId())
                .caregiverSystemId(caregiver.getSystemId())
                .authorizedBySystemId(patient.getSystemId())
                .relationship("daughter")
                .accessLevel("FULL_ACCESS")
                .canViewRecords(true)
                .canViewAppointments(true)
                .authorizedDate(LocalDateTime.now())
                .isActive(true)
                .build());
    }

    @Test
    void caregiverCannotSelfGrantMessagingOrCalls() throws Exception {
        DependentAccess access = unapprovedGrant();

        for (String flag : List.of("canMessageCareTeam", "canJoinCalls")) {
            MvcResult widen = doPatch("/api/caregiver/dependents/" + access.getAccessId(), caregiver, Map.of(flag, true));
            assertEquals(403, widen.getResponse().getStatus(), flag + ": " + widen.getResponse().getContentAsString());
        }
        // The patient-side endpoints are closed to caregivers.
        assertEquals(403, doPatch("/api/patients/me/caregiver-access/" + access.getAccessId(), caregiver,
                Map.of("canMessageCareTeam", true)).getResponse().getStatus());
        assertEquals(403, doPost("/api/patients/me/caregiver-access", caregiver, Map.of(
                "caregiverEmail", caregiver.getEmail(), "relationship", "self",
                "canMessageCareTeam", true, "canJoinCalls", true)).getResponse().getStatus());
        assertEquals(403, doPatch("/api/patients/me/caregivers/" + access.getAccessId(), caregiver,
                Map.of("canJoinCalls", true)).getResponse().getStatus());

        DependentAccess stored = dependentAccessRepository.findById(access.getAccessId()).orElseThrow();
        assertFalse(stored.getCanMessageCareTeam());
        assertFalse(stored.getCanJoinCalls());
        assertNull(stored.getMessagingApprovedAt());
        assertNull(stored.getCallsApprovedAt());

        // FULL_ACCESS without approval does not open messaging.
        assertEquals(403, doGet("/api/communications/recipients?patientSystemId=" + patient.getSystemId(), caregiver)
                .getResponse().getStatus());
    }

    @Test
    void createDependentRequestsAreNotApprovals() throws Exception {
        MvcResult created = doPost("/api/caregiver/dependents", caregiver, Map.ofEntries(
                Map.entry("firstName", "Grace"), Map.entry("lastName", "Dube"),
                Map.entry("email", "grace.dube@example.com"), Map.entry("phone", "+263770000111"),
                Map.entry("password", "Str0ngPassw0rd!"), Map.entry("dateOfBirth", "1950-01-01"),
                Map.entry("gender", "FEMALE"), Map.entry("relationship", "mother"),
                Map.entry("accessLevel", "FULL_ACCESS"),
                Map.entry("canMessageCareTeam", true), Map.entry("canJoinCalls", true)));
        assertEquals(201, created.getResponse().getStatus(), created.getResponse().getContentAsString());
        var body = json(created);
        assertFalse(body.path("messagingAllowed").asBoolean(), body.toString());
        assertFalse(body.path("joinCallsAllowed").asBoolean(), body.toString());
        assertTrue(body.path("pendingApproval").asBoolean(), body.toString());
    }

    @Test
    void patientApprovalStampsAndRevocationClearsAccess() throws Exception {
        DependentAccess access = unapprovedGrant();
        MvcResult approve = doPatch("/api/patients/me/caregiver-access/" + access.getAccessId(), patient,
                Map.of("canMessageCareTeam", true, "canJoinCalls", true));
        assertEquals(200, approve.getResponse().getStatus(), approve.getResponse().getContentAsString());
        assertTrue(json(approve).path("messagingAllowed").asBoolean());
        assertTrue(json(approve).path("joinCallsAllowed").asBoolean());
        assertEquals(200, doGet("/api/communications/recipients?patientSystemId=" + patient.getSystemId(), caregiver)
                .getResponse().getStatus());

        // Another patient cannot touch this grant.
        assertEquals(403, doPatch("/api/patients/me/caregiver-access/" + access.getAccessId(), otherPatient,
                Map.of("canMessageCareTeam", false)).getResponse().getStatus());

        assertEquals(204, doDelete("/api/patients/me/caregiver-access/" + access.getAccessId(), patient)
                .getResponse().getStatus());
        assertEquals(403, doGet("/api/communications/recipients?patientSystemId=" + patient.getSystemId(), caregiver)
                .getResponse().getStatus());
        MvcResult afterRevoke = doPatch("/api/patients/me/caregiver-access/" + access.getAccessId(), patient,
                Map.of("canMessageCareTeam", true));
        assertEquals(409, afterRevoke.getResponse().getStatus());
        assertEquals("This access has been revoked", message(afterRevoke));
    }

    @Test
    void expiredGrantGivesNoCommunicationAccess() throws Exception {
        DependentAccess access = grantApprovedAccess(caregiver, patient);
        assertEquals(200, doGet("/api/communications/recipients?patientSystemId=" + patient.getSystemId(), caregiver)
                .getResponse().getStatus());
        access.setExpiresAt(LocalDateTime.now().minusSeconds(5));
        dependentAccessRepository.saveAndFlush(access);
        assertEquals(403, doGet("/api/communications/recipients?patientSystemId=" + patient.getSystemId(), caregiver)
                .getResponse().getStatus());
    }
}
