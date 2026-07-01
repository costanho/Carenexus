package com.carenexus.api.core.service;

import com.carenexus.api.common.service.AuthorizationService;

import com.carenexus.api.BaseIntegrationTest;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.core.model.CareTeam;
import com.carenexus.api.core.model.DependentAccess;
import com.carenexus.api.auth.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-world scenarios from the Davis family case study
 * Tests the complete authorization flow with realistic data
 */
public class DavisFamilyScenarioTest extends BaseIntegrationTest {

    @Autowired
    private AuthorizationService authorizationService;

    @Autowired
    private RelationshipService relationshipService;

    // Users
    private User linda;         // PATIENT (id=1)
    private User mary;          // PATIENT (id=2)
    private User drMatt;        // DOCTOR (id=3)
    private User drSarah;       // DOCTOR specialist (id=4)
    private User caregiverSarah;// CAREGIVER (id=5)
    private User eleanor;       // PATIENT elderly (id=6)
    private User jake;          // CAREGIVER son (id=7)
    private User lisa;          // CAREGIVER daughter (id=8)
    private User admin;         // ADMIN (id=9)

    @BeforeEach
    public void setUp() {
        // Create all Davis family users
        linda = createAndSaveUser(1, "linda@example.com", "PATIENT");
        mary = createAndSaveUser(2, "mary@example.com", "PATIENT");
        drMatt = createAndSaveUser(3, "matt@hospital.com", "DOCTOR");
        drSarah = createAndSaveUser(4, "sarah-dr@hospital.com", "DOCTOR");
        caregiverSarah = createAndSaveUser(5, "sarah-caregiver@example.com", "CAREGIVER");
        eleanor = createAndSaveUser(6, "eleanor@example.com", "PATIENT");
        jake = createAndSaveUser(7, "jake@example.com", "CAREGIVER");
        lisa = createAndSaveUser(8, "lisa@example.com", "CAREGIVER");
        admin = createAndSaveUser(9, "admin@example.com", "ADMIN");

        // Set up relationships
        setupRelationships();
    }

    private void setupRelationships() {
        // Dr. Matt → Linda (primary doctor)
        createCareTeamRelationship(linda.getUserId(), drMatt.getUserId(), null, "PRIMARY_DOCTOR");

        // Dr. Sarah (specialist) → Linda
        createCareTeamRelationship(linda.getUserId(), drSarah.getUserId(), null, "SPECIALIST");

        // Caregiver Sarah → Linda
        createCareTeamRelationship(linda.getUserId(), null, caregiverSarah.getUserId(), "ASSIGNED_CAREGIVER");

        // Jake (son) → Eleanor (family caregiver with full access)
        createDependentAccess(eleanor.getUserId(), jake.getUserId(), eleanor.getUserId(), "FULL");

        // Lisa (daughter) → Eleanor (family caregiver with limited access)
        createDependentAccess(eleanor.getUserId(), lisa.getUserId(), eleanor.getUserId(), "HEALTH_RECORDS_ONLY");

        // No relationships between Linda and Mary (they're unrelated)
    }

    /**
     * Scenario 1: Linda (patient) can view her own records
     */
    @Test
    public void scenario1_PatientViewsOwnRecords() {
        setSecurityContext(linda.getUserId(), "linda@example.com", "PATIENT");

        assertDoesNotThrow(() ->
            authorizationService.authorizePatientDataAccess(
                linda.getUserId(),
                "MEDICAL_RECORDS",
                1
            )
        );
    }

    /**
     * Scenario 2: Linda (patient) CANNOT view Mary's records
     */
    @Test
    public void scenario2_PatientCannotViewOtherPatient() {
        setSecurityContext(linda.getUserId(), "linda@example.com", "PATIENT");

        assertThrows(AuthorizationException.class, () ->
            authorizationService.authorizePatientDataAccess(
                mary.getUserId(),
                "MEDICAL_RECORDS",
                2
            )
        );
    }

    /**
     * Scenario 3: Dr. Matt (assigned doctor) can view Linda's records
     */
    @Test
    public void scenario3_DoctorViewsAssignedPatient() {
        setSecurityContext(drMatt.getUserId(), "matt@hospital.com", "DOCTOR");

        assertDoesNotThrow(() ->
            authorizationService.authorizePatientDataAccess(
                linda.getUserId(),
                "MEDICAL_RECORDS",
                1
            )
        );
    }

    /**
     * Scenario 4: Dr. Matt (not assigned) CANNOT view Mary's records
     */
    @Test
    public void scenario4_DoctorCannotViewUnassignedPatient() {
        setSecurityContext(drMatt.getUserId(), "matt@hospital.com", "DOCTOR");

        assertThrows(AuthorizationException.class, () ->
            authorizationService.authorizePatientDataAccess(
                mary.getUserId(),
                "MEDICAL_RECORDS",
                2
            )
        );
    }

    /**
     * Scenario 5: Dr. Sarah (specialist) can view Linda's records
     */
    @Test
    public void scenario5_SpecialistDoctorViewsPatient() {
        setSecurityContext(drSarah.getUserId(), "sarah-dr@hospital.com", "DOCTOR");

        assertDoesNotThrow(() ->
            authorizationService.authorizePatientDataAccess(
                linda.getUserId(),
                "MEDICAL_RECORDS",
                1
            )
        );
    }

    /**
     * Scenario 6: Caregiver Sarah (hired) can view Linda's records
     */
    @Test
    public void scenario6_HiredCaregiverViewsAssignedPatient() {
        setSecurityContext(caregiverSarah.getUserId(), "sarah-caregiver@example.com", "CAREGIVER");

        assertDoesNotThrow(() ->
            authorizationService.authorizePatientDataAccess(
                linda.getUserId(),
                "HEALTH_METRICS",
                1
            )
        );
    }

    /**
     * Scenario 7: Jake (son) can view Eleanor's records via family access
     */
    @Test
    public void scenario7_SonViewsElderlyParent() {
        setSecurityContext(jake.getUserId(), "jake@example.com", "CAREGIVER");

        assertDoesNotThrow(() ->
            authorizationService.authorizePatientDataAccess(
                eleanor.getUserId(),
                "HEALTH_METRICS",
                6
            )
        );
    }

    /**
     * Scenario 8: Lisa (daughter) can view Eleanor's health records (limited permission)
     */
    @Test
    public void scenario8_DaughterWithLimitedAccess() {
        setSecurityContext(lisa.getUserId(), "lisa@example.com", "CAREGIVER");

        // Lisa has HEALTH_RECORDS_ONLY permission
        // She should be able to access health records
        assertDoesNotThrow(() ->
            authorizationService.authorizePatientDataAccess(
                eleanor.getUserId(),
                "HEALTH_METRICS",
                6
            )
        );
    }

    /**
     * Scenario 9: Admin can view all patient records
     */
    @Test
    public void scenario9_AdminCanViewAllData() {
        setSecurityContext(admin.getUserId(), "admin@example.com", "ADMIN");

        // Admin can view Linda's data
        assertDoesNotThrow(() ->
            authorizationService.authorizePatientDataAccess(
                linda.getUserId(),
                "MEDICAL_RECORDS",
                1
            )
        );

        // Admin can view Mary's data
        assertDoesNotThrow(() ->
            authorizationService.authorizePatientDataAccess(
                mary.getUserId(),
                "MEDICAL_RECORDS",
                2
            )
        );

        // Admin can view Eleanor's data
        assertDoesNotThrow(() ->
            authorizationService.authorizePatientDataAccess(
                eleanor.getUserId(),
                "MEDICAL_RECORDS",
                6
            )
        );
    }

    /**
     * Scenario 10: After revoking Dr. Matt's access
     */
    @Test
    public void scenario10_RevokedAccessDenies() {
        // Verify Dr. Matt initially has access
        setSecurityContext(drMatt.getUserId(), "matt@hospital.com", "DOCTOR");
        assertDoesNotThrow(() ->
            authorizationService.authorizePatientDataAccess(
                linda.getUserId(),
                "MEDICAL_RECORDS",
                1
            )
        );

        // Revoke access
        relationshipService.revokeDoctorAccessToPatient(drMatt.getUserId(), linda.getUserId());

        // Now Dr. Matt should be denied
        assertThrows(AuthorizationException.class, () ->
            authorizationService.authorizePatientDataAccess(
                linda.getUserId(),
                "MEDICAL_RECORDS",
                1
            )
        );
    }

    /**
     * Scenario 11: Linda cannot access Eleanor's data even with caregiver role name
     */
    @Test
    public void scenario11_UnrelatedPatientsCannotAccess() {
        // Even if we somehow gave Linda the caregiver role,
        // she's not assigned to Eleanor
        setSecurityContext(linda.getUserId(), "linda@example.com", "PATIENT");

        assertThrows(AuthorizationException.class, () ->
            authorizationService.authorizePatientDataAccess(
                eleanor.getUserId(),
                "HEALTH_METRICS",
                6
            )
        );
    }

    /**
     * Scenario 12: Multiple people can access same patient data
     */
    @Test
    public void scenario12_MultipleProvidersAccessSamePatient() {
        // Linda's medical record accessed by:
        // 1. Linda herself
        setSecurityContext(linda.getUserId(), "linda@example.com", "PATIENT");
        assertDoesNotThrow(() ->
            authorizationService.authorizePatientDataAccess(linda.getUserId(), "MEDICAL_RECORDS", 1)
        );

        // 2. Dr. Matt (primary doctor)
        setSecurityContext(drMatt.getUserId(), "matt@hospital.com", "DOCTOR");
        assertDoesNotThrow(() ->
            authorizationService.authorizePatientDataAccess(linda.getUserId(), "MEDICAL_RECORDS", 1)
        );

        // 3. Dr. Sarah (specialist)
        setSecurityContext(drSarah.getUserId(), "sarah-dr@hospital.com", "DOCTOR");
        assertDoesNotThrow(() ->
            authorizationService.authorizePatientDataAccess(linda.getUserId(), "MEDICAL_RECORDS", 1)
        );

        // 4. Caregiver Sarah
        setSecurityContext(caregiverSarah.getUserId(), "sarah-caregiver@example.com", "CAREGIVER");
        assertDoesNotThrow(() ->
            authorizationService.authorizePatientDataAccess(linda.getUserId(), "MEDICAL_RECORDS", 1)
        );

        // 5. Admin
        setSecurityContext(admin.getUserId(), "admin@example.com", "ADMIN");
        assertDoesNotThrow(() ->
            authorizationService.authorizePatientDataAccess(linda.getUserId(), "MEDICAL_RECORDS", 1)
        );

        // But NOT Mary (unrelated patient)
        setSecurityContext(mary.getUserId(), "mary@example.com", "PATIENT");
        assertThrows(AuthorizationException.class, () ->
            authorizationService.authorizePatientDataAccess(linda.getUserId(), "MEDICAL_RECORDS", 1)
        );
    }

    // ==================== HELPER METHODS ====================

    private void createCareTeamRelationship(Integer patientId, Integer doctorId, Integer caregiverId, String role) {
        CareTeam careTeam = CareTeam.builder()
                .patientId(patientId)
                .doctorId(doctorId)
                .caregiverId(caregiverId)
                .facilityId(1)
                .role(role)
                .assignedAt(LocalDateTime.now())
                .isActive(true)
                .build();
        careTeamRepository.save(careTeam);
    }

    private void createDependentAccess(Integer dependentId, Integer caregiverId, Integer guardianId, String permissionType) {
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

    private void setSecurityContext(Integer userId, String email, String role) {
        var userDetails = new com.carenexus.api.auth.config.CustomUserDetails(userId, email, role);
        var authentication = new UsernamePasswordAuthenticationToken(
                userDetails,
                null,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role))
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
