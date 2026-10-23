package com.carenexus.api.core.service;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.core.model.CareTeam;
import com.carenexus.api.core.model.DependentAccess;

import com.carenexus.api.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class RelationshipServiceTest extends BaseIntegrationTest {

    @Autowired
    private RelationshipService relationshipService;

    private User drMatt;
    private User linda;
    private User mary;
    private User sarah;

    @BeforeEach
    public void setUp() {
        drMatt = createAndSaveUser(1, "DOC-00001", "matt@hospital.com", "DOCTOR");
        linda = createAndSaveUser(2, "PAT-00001", "linda@example.com", "PATIENT");
        mary = createAndSaveUser(3, "PAT-00002", "mary@example.com", "PATIENT");
        sarah = createAndSaveUser(4, "CGV-00001", "sarah@example.com", "CAREGIVER");
    }

    // ==================== DOCTOR-PATIENT TESTS ====================

    @Test
    public void testIsDoctorAssignedToPatient_True() {
        createCareTeamRelationship(drMatt.getSystemId(), linda.getSystemId(), null);

        assertTrue(relationshipService.isDoctorAssignedToPatient(
                drMatt.getSystemId(),
                linda.getSystemId()
        ));
    }

    @Test
    public void testIsDoctorAssignedToPatient_False() {
        createCareTeamRelationship(drMatt.getSystemId(), linda.getSystemId(), null);

        assertFalse(relationshipService.isDoctorAssignedToPatient(
                drMatt.getSystemId(),
                mary.getSystemId()  // Mary, not Linda
        ));
    }

    @Test
    public void testGetPatientsForDoctor() {
        createCareTeamRelationship(drMatt.getSystemId(), linda.getSystemId(), null);
        createCareTeamRelationship(drMatt.getSystemId(), mary.getSystemId(), null);

        List<String> patients = relationshipService.getPatientsForDoctor(drMatt.getSystemId());

        assertEquals(2, patients.size());
        assertTrue(patients.contains(linda.getSystemId()));
        assertTrue(patients.contains(mary.getSystemId()));
    }

    @Test
    public void testGetDoctorsForPatient() {
        User doctor2 = createAndSaveUser(5, "DOC-00002", "specialist@hospital.com", "DOCTOR");

        createCareTeamRelationship(drMatt.getSystemId(), linda.getSystemId(), null);
        createCareTeamRelationship(doctor2.getSystemId(), linda.getSystemId(), null);

        List<String> doctors = relationshipService.getDoctorsForPatient(linda.getSystemId());

        assertEquals(2, doctors.size());
        assertTrue(doctors.contains(drMatt.getSystemId()));
        assertTrue(doctors.contains(doctor2.getSystemId()));
    }

    // ==================== CAREGIVER-PATIENT TESTS ====================

    @Test
    public void testIsCaregiverAssignedToPatient_True() {
        createCareTeamRelationship(null, linda.getSystemId(), sarah.getSystemId());

        assertTrue(relationshipService.isCaregiverAssignedToPatient(
                sarah.getSystemId(),
                linda.getSystemId()
        ));
    }

    @Test
    public void testIsCaregiverAssignedToPatient_False() {
        createCareTeamRelationship(null, linda.getSystemId(), sarah.getSystemId());

        assertFalse(relationshipService.isCaregiverAssignedToPatient(
                sarah.getSystemId(),
                mary.getSystemId()
        ));
    }

    @Test
    public void testGetPatientsForCaregiver() {
        createCareTeamRelationship(null, linda.getSystemId(), sarah.getSystemId());
        createCareTeamRelationship(null, mary.getSystemId(), sarah.getSystemId());

        List<String> patients = relationshipService.getPatientsForCaregiver(sarah.getSystemId());

        assertEquals(2, patients.size());
        assertTrue(patients.contains(linda.getSystemId()));
        assertTrue(patients.contains(mary.getSystemId()));
    }

    // ==================== DEPENDENT ACCESS TESTS ====================

    @Test
    public void testHasAccessToDependent_True() {
        createDependentAccess(linda.getSystemId(), sarah.getSystemId(), linda.getSystemId());

        assertTrue(relationshipService.hasAccessToDependent(
                sarah.getSystemId(),
                linda.getSystemId()
        ));
    }

    @Test
    public void testHasAccessToDependent_False() {
        createDependentAccess(linda.getSystemId(), sarah.getSystemId(), linda.getSystemId());

        assertFalse(relationshipService.hasAccessToDependent(
                sarah.getSystemId(),
                mary.getSystemId()
        ));
    }

    @Test
    public void testHasPermissionTypeForDependent_Full() {
        createDependentAccessWithPermission(linda.getSystemId(), sarah.getSystemId(), linda.getSystemId(), "FULL");

        assertTrue(relationshipService.hasPermissionTypeForDependent(
                sarah.getSystemId(),
                linda.getSystemId(),
                "FULL"
        ));
    }

    @Test
    public void testHasPermissionTypeForDependent_LimitedPermission() {
        createDependentAccessWithPermission(
                linda.getSystemId(),
                sarah.getSystemId(),
                linda.getSystemId(),
                "HEALTH_RECORDS_ONLY"
        );

        assertTrue(relationshipService.hasPermissionTypeForDependent(
                sarah.getSystemId(),
                linda.getSystemId(),
                "HEALTH_RECORDS_ONLY"
        ));

        assertFalse(relationshipService.hasPermissionTypeForDependent(
                sarah.getSystemId(),
                linda.getSystemId(),
                "FULL"
        ));
    }

    @Test
    public void testGetDependentsForCaregiver() {
        createDependentAccess(linda.getSystemId(), sarah.getSystemId(), linda.getSystemId());
        createDependentAccess(mary.getSystemId(), sarah.getSystemId(), mary.getSystemId());

        List<String> dependents = relationshipService.getDependentsForCaregiver(sarah.getSystemId());

        assertEquals(2, dependents.size());
        assertTrue(dependents.contains(linda.getSystemId()));
        assertTrue(dependents.contains(mary.getSystemId()));
    }

    // ==================== REVOCATION TESTS ====================

    @Test
    public void testRevokeDoctorAccessToPatient() {
        createCareTeamRelationship(drMatt.getSystemId(), linda.getSystemId(), null);

        // Verify access exists
        assertTrue(relationshipService.isDoctorAssignedToPatient(
                drMatt.getSystemId(),
                linda.getSystemId()
        ));

        // Revoke
        relationshipService.revokeDoctorAccessToPatient(drMatt.getSystemId(), linda.getSystemId());

        // Verify access is gone
        assertFalse(relationshipService.isDoctorAssignedToPatient(
                drMatt.getSystemId(),
                linda.getSystemId()
        ));
    }

    @Test
    public void testRevokeCaregiverAccessToDependent() {
        createDependentAccess(linda.getSystemId(), sarah.getSystemId(), linda.getSystemId());

        // Verify access exists
        assertTrue(relationshipService.hasAccessToDependent(
                sarah.getSystemId(),
                linda.getSystemId()
        ));

        // Revoke
        relationshipService.revokeCaregiverAccessToDependent(sarah.getSystemId(), linda.getSystemId());

        // Verify access is gone
        assertFalse(relationshipService.hasAccessToDependent(
                sarah.getSystemId(),
                linda.getSystemId()
        ));
    }

    // ==================== COMPREHENSIVE ACCESS TESTS ====================

    @Test
    public void testCanAccessPatientData_UserIsPatient() {
        assertTrue(relationshipService.canAccessPatientData(
                linda.getSystemId(),
                linda.getSystemId()  // Linda accessing her own data
        ));
    }

    @Test
    public void testCanAccessPatientData_DoctorAssigned() {
        createCareTeamRelationship(drMatt.getSystemId(), linda.getSystemId(), null);

        assertTrue(relationshipService.canAccessPatientData(
                drMatt.getSystemId(),
                linda.getSystemId()
        ));
    }

    @Test
    public void testCanAccessPatientData_CaregiverAssigned() {
        createCareTeamRelationship(null, linda.getSystemId(), sarah.getSystemId());

        assertTrue(relationshipService.canAccessPatientData(
                sarah.getSystemId(),
                linda.getSystemId()
        ));
    }

    @Test
    public void testCanAccessPatientData_FamilyAccess() {
        createDependentAccess(linda.getSystemId(), sarah.getSystemId(), linda.getSystemId());

        assertTrue(relationshipService.canAccessPatientData(
                sarah.getSystemId(),
                linda.getSystemId()
        ));
    }

    @Test
    public void testCanAccessPatientData_NoAccess() {
        assertFalse(relationshipService.canAccessPatientData(
                sarah.getSystemId(),
                linda.getSystemId()  // No relationship between them
        ));
    }

    // ==================== HELPER METHODS ====================

    private void createCareTeamRelationship(String providerSystemId, String patientSystemId, String caregiverSystemId) {
        CareTeam careTeam = CareTeam.builder()
                .patientSystemId(patientSystemId)
                .providerSystemId(providerSystemId != null ? providerSystemId : caregiverSystemId)
                .facilityId(1)
                .role(providerSystemId != null ? "PRIMARY_DOCTOR" : "CAREGIVER")
                .assignedAt(LocalDateTime.now())
                .isActive(true)
                .build();
        careTeamRepository.save(careTeam);
    }

    private void createDependentAccess(String patientSystemId, String caregiverSystemId, String authorizedBySystemId) {
        DependentAccess access = DependentAccess.builder()
                .patientSystemId(patientSystemId)
                .caregiverSystemId(caregiverSystemId)
                .authorizedBySystemId(authorizedBySystemId)
                .accessLevel("FULL")
                .authorizedAt(LocalDateTime.now())
                .isActive(true)
                .build();
        dependentAccessRepository.save(access);
    }

    private void createDependentAccessWithPermission(
            String patientSystemId, String caregiverSystemId, String authorizedBySystemId, String accessLevel) {
        DependentAccess access = DependentAccess.builder()
                .patientSystemId(patientSystemId)
                .caregiverSystemId(caregiverSystemId)
                .authorizedBySystemId(authorizedBySystemId)
                .accessLevel(accessLevel)
                .authorizedAt(LocalDateTime.now())
                .isActive(true)
                .build();
        dependentAccessRepository.save(access);
    }
}
