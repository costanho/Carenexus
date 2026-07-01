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
        drMatt = createAndSaveUser(1, "matt@hospital.com", "DOCTOR");
        linda = createAndSaveUser(2, "linda@example.com", "PATIENT");
        mary = createAndSaveUser(3, "mary@example.com", "PATIENT");
        sarah = createAndSaveUser(4, "sarah@example.com", "CAREGIVER");
    }

    // ==================== DOCTOR-PATIENT TESTS ====================

    @Test
    public void testIsDoctorAssignedToPatient_True() {
        createCareTeamRelationship(drMatt.getUserId(), linda.getUserId(), null);

        assertTrue(relationshipService.isDoctorAssignedToPatient(
                drMatt.getUserId(),
                linda.getUserId()
        ));
    }

    @Test
    public void testIsDoctorAssignedToPatient_False() {
        createCareTeamRelationship(drMatt.getUserId(), linda.getUserId(), null);

        assertFalse(relationshipService.isDoctorAssignedToPatient(
                drMatt.getUserId(),
                mary.getUserId()  // Mary, not Linda
        ));
    }

    @Test
    public void testGetPatientsForDoctor() {
        createCareTeamRelationship(drMatt.getUserId(), linda.getUserId(), null);
        createCareTeamRelationship(drMatt.getUserId(), mary.getUserId(), null);

        List<Integer> patients = relationshipService.getPatientsForDoctor(drMatt.getUserId());

        assertEquals(2, patients.size());
        assertTrue(patients.contains(linda.getUserId()));
        assertTrue(patients.contains(mary.getUserId()));
    }

    @Test
    public void testGetDoctorsForPatient() {
        User doctor2 = createAndSaveUser(5, "specialist@hospital.com", "DOCTOR");

        createCareTeamRelationship(drMatt.getUserId(), linda.getUserId(), null);
        createCareTeamRelationship(doctor2.getUserId(), linda.getUserId(), null);

        List<Integer> doctors = relationshipService.getDoctorsForPatient(linda.getUserId());

        assertEquals(2, doctors.size());
        assertTrue(doctors.contains(drMatt.getUserId()));
        assertTrue(doctors.contains(doctor2.getUserId()));
    }

    // ==================== CAREGIVER-PATIENT TESTS ====================

    @Test
    public void testIsCaregiverAssignedToPatient_True() {
        createCareTeamRelationship(null, linda.getUserId(), sarah.getUserId());

        assertTrue(relationshipService.isCaregiverAssignedToPatient(
                sarah.getUserId(),
                linda.getUserId()
        ));
    }

    @Test
    public void testIsCaregiverAssignedToPatient_False() {
        createCareTeamRelationship(null, linda.getUserId(), sarah.getUserId());

        assertFalse(relationshipService.isCaregiverAssignedToPatient(
                sarah.getUserId(),
                mary.getUserId()
        ));
    }

    @Test
    public void testGetPatientsForCaregiver() {
        createCareTeamRelationship(null, linda.getUserId(), sarah.getUserId());
        createCareTeamRelationship(null, mary.getUserId(), sarah.getUserId());

        List<Integer> patients = relationshipService.getPatientsForCaregiver(sarah.getUserId());

        assertEquals(2, patients.size());
        assertTrue(patients.contains(linda.getUserId()));
        assertTrue(patients.contains(mary.getUserId()));
    }

    // ==================== DEPENDENT ACCESS TESTS ====================

    @Test
    public void testHasAccessToDependent_True() {
        createDependentAccess(linda.getUserId(), sarah.getUserId(), linda.getUserId());

        assertTrue(relationshipService.hasAccessToDependent(
                sarah.getUserId(),
                linda.getUserId()
        ));
    }

    @Test
    public void testHasAccessToDependent_False() {
        createDependentAccess(linda.getUserId(), sarah.getUserId(), linda.getUserId());

        assertFalse(relationshipService.hasAccessToDependent(
                sarah.getUserId(),
                mary.getUserId()
        ));
    }

    @Test
    public void testHasPermissionTypeForDependent_Full() {
        createDependentAccessWithPermission(linda.getUserId(), sarah.getUserId(), linda.getUserId(), "FULL");

        assertTrue(relationshipService.hasPermissionTypeForDependent(
                sarah.getUserId(),
                linda.getUserId(),
                "FULL"
        ));
    }

    @Test
    public void testHasPermissionTypeForDependent_LimitedPermission() {
        createDependentAccessWithPermission(
                linda.getUserId(),
                sarah.getUserId(),
                linda.getUserId(),
                "HEALTH_RECORDS_ONLY"
        );

        assertTrue(relationshipService.hasPermissionTypeForDependent(
                sarah.getUserId(),
                linda.getUserId(),
                "HEALTH_RECORDS_ONLY"
        ));

        assertFalse(relationshipService.hasPermissionTypeForDependent(
                sarah.getUserId(),
                linda.getUserId(),
                "FULL"
        ));
    }

    @Test
    public void testGetDependentsForCaregiver() {
        createDependentAccess(linda.getUserId(), sarah.getUserId(), linda.getUserId());
        createDependentAccess(mary.getUserId(), sarah.getUserId(), mary.getUserId());

        List<Integer> dependents = relationshipService.getDependentsForCaregiver(sarah.getUserId());

        assertEquals(2, dependents.size());
        assertTrue(dependents.contains(linda.getUserId()));
        assertTrue(dependents.contains(mary.getUserId()));
    }

    // ==================== REVOCATION TESTS ====================

    @Test
    public void testRevokeDoctorAccessToPatient() {
        createCareTeamRelationship(drMatt.getUserId(), linda.getUserId(), null);

        // Verify access exists
        assertTrue(relationshipService.isDoctorAssignedToPatient(
                drMatt.getUserId(),
                linda.getUserId()
        ));

        // Revoke
        relationshipService.revokeDoctorAccessToPatient(drMatt.getUserId(), linda.getUserId());

        // Verify access is gone
        assertFalse(relationshipService.isDoctorAssignedToPatient(
                drMatt.getUserId(),
                linda.getUserId()
        ));
    }

    @Test
    public void testRevokeCaregiverAccessToDependent() {
        createDependentAccess(linda.getUserId(), sarah.getUserId(), linda.getUserId());

        // Verify access exists
        assertTrue(relationshipService.hasAccessToDependent(
                sarah.getUserId(),
                linda.getUserId()
        ));

        // Revoke
        relationshipService.revokeCaregiverAccessToDependent(sarah.getUserId(), linda.getUserId());

        // Verify access is gone
        assertFalse(relationshipService.hasAccessToDependent(
                sarah.getUserId(),
                linda.getUserId()
        ));
    }

    // ==================== COMPREHENSIVE ACCESS TESTS ====================

    @Test
    public void testCanAccessPatientData_UserIsPatient() {
        assertTrue(relationshipService.canAccessPatientData(
                linda.getUserId(),
                linda.getUserId()  // Linda accessing her own data
        ));
    }

    @Test
    public void testCanAccessPatientData_DoctorAssigned() {
        createCareTeamRelationship(drMatt.getUserId(), linda.getUserId(), null);

        assertTrue(relationshipService.canAccessPatientData(
                drMatt.getUserId(),
                linda.getUserId()
        ));
    }

    @Test
    public void testCanAccessPatientData_CaregiverAssigned() {
        createCareTeamRelationship(null, linda.getUserId(), sarah.getUserId());

        assertTrue(relationshipService.canAccessPatientData(
                sarah.getUserId(),
                linda.getUserId()
        ));
    }

    @Test
    public void testCanAccessPatientData_FamilyAccess() {
        createDependentAccess(linda.getUserId(), sarah.getUserId(), linda.getUserId());

        assertTrue(relationshipService.canAccessPatientData(
                sarah.getUserId(),
                linda.getUserId()
        ));
    }

    @Test
    public void testCanAccessPatientData_NoAccess() {
        assertFalse(relationshipService.canAccessPatientData(
                sarah.getUserId(),
                linda.getUserId()  // No relationship between them
        ));
    }

    // ==================== HELPER METHODS ====================

    private void createCareTeamRelationship(Integer doctorId, Integer patientId, Integer caregiverId) {
        CareTeam careTeam = CareTeam.builder()
                .patientId(patientId)
                .doctorId(doctorId)
                .caregiverId(caregiverId)
                .facilityId(1)
                .role("PRIMARY_DOCTOR")
                .assignedAt(LocalDateTime.now())
                .isActive(true)
                .build();
        careTeamRepository.save(careTeam);
    }

    private void createDependentAccess(Integer dependentId, Integer caregiverId, Integer guardianId) {
        DependentAccess access = DependentAccess.builder()
                .dependentId(dependentId)
                .caregiverId(caregiverId)
                .guardianId(guardianId)
                .permissionType("FULL")
                .authorizedAt(LocalDateTime.now())
                .authorizedBy(guardianId)
                .isActive(true)
                .build();
        dependentAccessRepository.save(access);
    }

    private void createDependentAccessWithPermission(
            Integer dependentId, Integer caregiverId, Integer guardianId, String permissionType) {
        DependentAccess access = DependentAccess.builder()
                .dependentId(dependentId)
                .caregiverId(caregiverId)
                .guardianId(guardianId)
                .permissionType(permissionType)
                .authorizedAt(LocalDateTime.now())
                .authorizedBy(guardianId)
                .isActive(true)
                .build();
        dependentAccessRepository.save(access);
    }
}
