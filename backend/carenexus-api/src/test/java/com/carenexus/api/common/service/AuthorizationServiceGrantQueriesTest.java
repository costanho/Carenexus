package com.carenexus.api.common.service;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.core.model.DependentAccess;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AuthorizationService against PostgreSQL for the permissions and helpers added by the foundation:
 * caregiversWithPermission / relatedDoctors, and requirePatientAccess for the new constants with a caregiver who has
 * the flag, lacks it, holds FULL_ACCESS, holds VIEW_ONLY, or whose grant expired or was revoked.
 */
public class AuthorizationServiceGrantQueriesTest extends CommittedIntegrationTest {

    @Autowired
    private AuthorizationService authorizationService;

    private void assertDenied(String patientSystemId, PatientPermission permission) {
        assertThrows(AuthorizationException.class,
                () -> authorizationService.requirePatientAccess(patientSystemId, permission), permission.name());
    }

    private void assertAllowed(String patientSystemId, PatientPermission permission) {
        assertDoesNotThrow(() -> authorizationService.requirePatientAccess(patientSystemId, permission), permission.name());
    }

    // ==================== requirePatientAccess: caregiver ====================

    @Test
    void caregiverWithTheFlag_isAllowed_withoutIt_isDeniedAndAudited() {
        grantAccess(caregiver, patient, a -> a.setCanViewBilling(true));
        authenticateAs(caregiver);

        assertAllowed(patient.getSystemId(), PatientPermission.BILLING);
        assertAllowed(patient.getSystemId(), PatientPermission.CARE_TEAM);
        assertDenied(patient.getSystemId(), PatientPermission.MANAGE_BILLING);
        assertDenied(patient.getSystemId(), PatientPermission.DOCUMENTS);
        assertDenied(patient.getSystemId(), PatientPermission.HEALTH_METRICS);
        assertDenied(otherPatient.getSystemId(), PatientPermission.BILLING);

        Integer denials = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'ACCESS_DENIED' "
                + "AND system_id = ? AND patient_system_id = ? AND resource_type = 'INVOICE'", Integer.class,
                caregiver.getSystemId(), patient.getSystemId());
        assertEquals(1, denials, "the MANAGE_BILLING denial is audited with the INVOICE resource type");
    }

    @Test
    void fullAccess_allowsEveryNewPermission_viewOnlyBlocksTheWrites() {
        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        grantAccess(otherCaregiver, patient, a -> {
            a.setAccessLevel("VIEW_ONLY");
            a.setCanViewBilling(true);
            a.setCanManageBilling(true);
            a.setCanViewRecords(true);
            a.setCanManageCarePlan(true);
        });

        authenticateAs(caregiver);
        for (PatientPermission p : List.of(PatientPermission.BILLING, PatientPermission.MANAGE_BILLING,
                PatientPermission.CARE_PLANS, PatientPermission.HEALTH_METRICS, PatientPermission.MANAGE_HEALTH_METRICS,
                PatientPermission.REFERRALS, PatientPermission.DOCUMENTS, PatientPermission.MANAGE_DOCUMENTS,
                PatientPermission.AI_ALERTS, PatientPermission.CARE_TEAM)) {
            assertAllowed(patient.getSystemId(), p);
        }

        authenticateAs(otherCaregiver);
        for (PatientPermission p : List.of(PatientPermission.BILLING, PatientPermission.CARE_PLANS,
                PatientPermission.HEALTH_METRICS, PatientPermission.REFERRALS, PatientPermission.DOCUMENTS,
                PatientPermission.AI_ALERTS, PatientPermission.CARE_TEAM)) {
            assertAllowed(patient.getSystemId(), p);
        }
        for (PatientPermission p : List.of(PatientPermission.MANAGE_BILLING, PatientPermission.MANAGE_HEALTH_METRICS,
                PatientPermission.MANAGE_DOCUMENTS)) {
            assertDenied(patient.getSystemId(), p);
        }
    }

    @Test
    void expiredOrRevokedGrant_losesEveryPermission_includingBillingAndCareTeam() {
        grantAccess(caregiver, patient, a -> {
            a.setAccessLevel("FULL_ACCESS");
            a.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        });
        grantAccess(otherCaregiver, patient, a -> {
            a.setCanViewBilling(true);
            a.setCanManageBilling(true);
            a.setIsActive(false);
            a.setRevokedAt(LocalDateTime.now().minusMinutes(1));
        });

        for (User expiredOrRevoked : List.of(caregiver, otherCaregiver)) {
            authenticateAs(expiredOrRevoked);
            assertDenied(patient.getSystemId(), PatientPermission.BILLING);
            assertDenied(patient.getSystemId(), PatientPermission.MANAGE_BILLING);
            assertDenied(patient.getSystemId(), PatientPermission.CARE_TEAM);
            assertFalse(authorizationService.canAccessPatient(patient.getSystemId(), PatientPermission.BILLING));
            assertFalse(authorizationService.hasCaregiverPermission(expiredOrRevoked.getSystemId(), patient.getSystemId(),
                    PatientPermission.BILLING));
        }

        // Extending the grant brings the permission back.
        grantAccess(caregiver, patient, a -> {
            a.setAccessLevel("FULL_ACCESS");
            a.setExpiresAt(LocalDateTime.now().plusDays(1));
        });
        authenticateAs(caregiver);
        assertAllowed(patient.getSystemId(), PatientPermission.MANAGE_BILLING);
    }

    @Test
    void patientDoctorAndAdmin_areNotRestrictedByCaregiverFlags_unrelatedUsersAreDenied() {
        authenticateAs(patient);
        assertAllowed(patient.getSystemId(), PatientPermission.MANAGE_BILLING);
        assertDenied(otherPatient.getSystemId(), PatientPermission.BILLING);

        authenticateAs(doctor);            // on the patient's care team
        assertAllowed(patient.getSystemId(), PatientPermission.AI_ALERTS);
        assertDenied(otherPatient.getSystemId(), PatientPermission.AI_ALERTS);

        authenticateAs(otherDoctor);       // unrelated doctor
        assertDenied(patient.getSystemId(), PatientPermission.REFERRALS);

        authenticateAs(otherCaregiver);    // no grant at all
        assertDenied(patient.getSystemId(), PatientPermission.CARE_TEAM);

        authenticateAs(admin);
        assertAllowed(patient.getSystemId(), PatientPermission.MANAGE_DOCUMENTS);
    }

    // ==================== caregiversWithPermission ====================

    @Test
    void caregiversWithPermission_returnsActiveUnexpiredGrantsThatGrantIt() {
        User third = createAndSaveUser(8, "CGV-00003", "third.cg@example.com", "CAREGIVER");
        User fourth = createAndSaveUser(9, "CGV-00004", "fourth.cg@example.com", "CAREGIVER");

        grantAccess(caregiver, patient, a -> a.setCanViewBilling(true));                       // has the flag
        grantAccess(otherCaregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));            // everything
        grantAccess(third, patient, a -> {                                                     // expired
            a.setCanViewBilling(true);
            a.setExpiresAt(LocalDateTime.now().minusHours(1));
        });
        grantAccess(fourth, patient, a -> {                                                    // VIEW_ONLY with manage flag
            a.setAccessLevel("VIEW_ONLY");
            a.setCanManageBilling(true);
        });
        grantAccess(caregiver, otherPatient, a -> a.setCanManageBilling(true));                // another patient

        assertEquals(Set.of(caregiver.getSystemId(), otherCaregiver.getSystemId()),
                Set.copyOf(authorizationService.caregiversWithPermission(patient.getSystemId(), PatientPermission.BILLING)));
        assertEquals(List.of(otherCaregiver.getSystemId()),
                authorizationService.caregiversWithPermission(patient.getSystemId(), PatientPermission.MANAGE_BILLING),
                "VIEW_ONLY blocks the write permission even with the flag");
        assertEquals(Set.of(caregiver.getSystemId(), otherCaregiver.getSystemId(), fourth.getSystemId()),
                Set.copyOf(authorizationService.caregiversWithPermission(patient.getSystemId(), PatientPermission.CARE_TEAM)),
                "any active grant sees the care team; the expired one does not");
        assertEquals(List.of(caregiver.getSystemId()),
                authorizationService.caregiversWithPermission(otherPatient.getSystemId(), PatientPermission.MANAGE_BILLING));

        // Communication permissions need the patient's approval stamp, FULL_ACCESS does not imply them.
        assertTrue(authorizationService.caregiversWithPermission(patient.getSystemId(), PatientPermission.MESSAGING).isEmpty());
        grantApprovedAccess(third, otherPatient);
        assertEquals(List.of(third.getSystemId()),
                authorizationService.caregiversWithPermission(otherPatient.getSystemId(), PatientPermission.MESSAGING));

        assertTrue(authorizationService.caregiversWithPermission(null, PatientPermission.BILLING).isEmpty());
        assertTrue(authorizationService.caregiversWithPermission(patient.getSystemId(), null).isEmpty());
        assertTrue(authorizationService.caregiversWithPermission("PAT-99999", PatientPermission.BILLING).isEmpty());
    }

    @Test
    void caregiversWithPermissionByPatient_answersSeveralPatientsWithTheSameRule() {
        User third = createAndSaveUser(8, "CGV-00003", "third.cg@example.com", "CAREGIVER");
        grantAccess(caregiver, patient, a -> a.setCanViewAppointments(true));
        grantAccess(otherCaregiver, patient, a -> a.setCanViewMedications(true));               // other flag
        grantAccess(third, patient, a -> {                                                     // expired
            a.setCanViewAppointments(true);
            a.setExpiresAt(LocalDateTime.now().minusHours(1));
        });
        grantAccess(otherCaregiver, otherPatient, a -> a.setAccessLevel("FULL_ACCESS"));

        java.util.Map<String, List<String>> byPatient = authorizationService.caregiversWithPermissionByPatient(
                List.of(patient.getSystemId(), otherPatient.getSystemId(), patient.getSystemId(), "PAT-99999", " "),
                PatientPermission.APPOINTMENTS);

        assertEquals(List.of(patient.getSystemId(), otherPatient.getSystemId(), "PAT-99999"),
                List.copyOf(byPatient.keySet()), "every requested patient once, blanks skipped");
        assertEquals(List.of(caregiver.getSystemId()), byPatient.get(patient.getSystemId()));
        assertEquals(List.of(otherCaregiver.getSystemId()), byPatient.get(otherPatient.getSystemId()));
        assertTrue(byPatient.get("PAT-99999").isEmpty());
        for (String id : List.of(patient.getSystemId(), otherPatient.getSystemId())) {
            assertEquals(authorizationService.caregiversWithPermission(id, PatientPermission.APPOINTMENTS),
                    byPatient.get(id), "same answer as the single-patient query");
        }
        assertTrue(authorizationService.caregiversWithPermissionByPatient(List.of(), PatientPermission.APPOINTMENTS)
                .isEmpty());
        assertTrue(authorizationService.caregiversWithPermissionByPatient(null, PatientPermission.APPOINTMENTS)
                .isEmpty());
    }

    @Test
    void revokedGrant_isNotReturned() {
        DependentAccess access = grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));
        assertEquals(List.of(caregiver.getSystemId()),
                authorizationService.caregiversWithPermission(patient.getSystemId(), PatientPermission.DOCUMENTS));

        access.setIsActive(false);
        access.setRevokedAt(LocalDateTime.now());
        dependentAccessRepository.saveAndFlush(access);

        assertTrue(authorizationService.caregiversWithPermission(patient.getSystemId(), PatientPermission.DOCUMENTS).isEmpty());
    }

    // ==================== relatedDoctors ====================

    @Test
    void relatedDoctors_areTheActiveDoctorsWithACareRelationship() {
        assertEquals(List.of(doctor.getSystemId()), authorizationService.relatedDoctors(patient.getSystemId()));
        assertEquals(List.of(otherDoctor.getSystemId()), authorizationService.relatedDoctors(otherPatient.getSystemId()));
        assertTrue(authorizationService.relatedDoctors(null).isEmpty());
        assertTrue(authorizationService.relatedDoctors("PAT-99999").isEmpty());

        // A deactivated doctor account is no longer returned.
        jdbc.update("UPDATE users SET is_active = FALSE WHERE system_id = ?", doctor.getSystemId());
        assertTrue(authorizationService.relatedDoctors(patient.getSystemId()).isEmpty());
    }
}
