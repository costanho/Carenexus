package com.carenexus.api.common.service;

import com.carenexus.api.core.service.RelationshipService;

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

public class AuthorizationServiceRelationshipTest extends BaseIntegrationTest {

    @Autowired
    private AuthorizationService authorizationService;

    @Autowired
    private RelationshipService relationshipService;

    private User drMatt;      // doctor
    private User linda;       // patient
    private User mary;        // patient (not related to Linda)
    private User sarah;       // caregiver (hired for Linda)
    private User jake;        // son (family caregiver)
    private User eleanor;     // elderly patient (mother)

    @BeforeEach
    public void setUp() {
        // Create users
        drMatt = createAndSaveUser(1, "matt@hospital.com", "DOCTOR");
        linda = createAndSaveUser(2, "linda@example.com", "PATIENT");
        mary = createAndSaveUser(3, "mary@example.com", "PATIENT");
        sarah = createAndSaveUser(4, "sarah@example.com", "CAREGIVER");
        jake = createAndSaveUser(5, "jake@example.com", "CAREGIVER");
        eleanor = createAndSaveUser(6, "eleanor@example.com", "PATIENT");

        // Relationships:
        // Dr. Matt → Linda (care_team)
        CareTeam careTeam1 = CareTeam.builder()
                .patientId(linda.getUserId())
                .doctorId(drMatt.getUserId())
                .facilityId(1)
                .role("PRIMARY_DOCTOR")
                .assignedAt(LocalDateTime.now())
                .isActive(true)
                .build();
        careTeamRepository.save(careTeam1);

        // Sarah (caregiver) → Linda (care_team)
        CareTeam careTeam2 = CareTeam.builder()
                .patientId(linda.getUserId())
                .caregiverId(sarah.getUserId())
                .facilityId(1)
                .role("ASSIGNED_CAREGIVER")
                .assignedAt(LocalDateTime.now())
                .isActive(true)
                .build();
        careTeamRepository.save(careTeam2);

        // Jake (son) → Eleanor (dependent_access, family relationship)
        DependentAccess access = DependentAccess.builder()
                .dependentId(eleanor.getUserId())
                .caregiverId(jake.getUserId())
                .guardianId(eleanor.getUserId()) // Eleanor granted access to Jake
                .permissionType("FULL")
                .authorizedAt(LocalDateTime.now())
                .authorizedBy(eleanor.getUserId())
                .isActive(true)
                .build();
        dependentAccessRepository.save(access);
    }

    /**
     * Scenario: Doctor accessing assigned patient's data
     */
    @Test
    public void testAuthorizePatientDataAccess_DoctorCanAccessAssignedPatient() {
        setSecurityContext(drMatt.getUserId(), "matt@hospital.com", "DOCTOR");

        assertDoesNotThrow(() ->
            authorizationService.authorizePatientDataAccess(
                linda.getUserId(), // Linda
                "MEDICAL_RECORDS",
                1
            )
        );
    }

    /**
     * Scenario: Doctor trying to access unassigned patient's data
     */
    @Test
    public void testAuthorizePatientDataAccess_DoctorCannotAccessUnassignedPatient() {
        setSecurityContext(drMatt.getUserId(), "matt@hospital.com", "DOCTOR");

        // Dr. Matt is not assigned to Mary
        assertThrows(AuthorizationException.class, () ->
            authorizationService.authorizePatientDataAccess(
                mary.getUserId(), // Mary
                "MEDICAL_RECORDS",
                2
            )
        );
    }

    /**
     * Scenario: Patient accessing own data
     */
    @Test
    public void testAuthorizePatientDataAccess_PatientCanAccessOwnData() {
        setSecurityContext(linda.getUserId(), "linda@example.com", "PATIENT");

        assertDoesNotThrow(() ->
            authorizationService.authorizePatientDataAccess(
                linda.getUserId(), // Linda accessing her own data
                "MEDICAL_RECORDS",
                1
            )
        );
    }

    /**
     * Scenario: Patient trying to access other patient's data
     */
    @Test
    public void testAuthorizePatientDataAccess_PatientCannotAccessOtherPatient() {
        setSecurityContext(linda.getUserId(), "linda@example.com", "PATIENT");

        assertThrows(AuthorizationException.class, () ->
            authorizationService.authorizePatientDataAccess(
                mary.getUserId(), // Linda trying to access Mary's data
                "MEDICAL_RECORDS",
                2
            )
        );
    }

    /**
     * Scenario: Caregiver (hired) accessing assigned patient's data
     */
    @Test
    public void testAuthorizePatientDataAccess_CaregiverCanAccessAssignedPatient() {
        setSecurityContext(sarah.getUserId(), "sarah@example.com", "CAREGIVER");

        assertDoesNotThrow(() ->
            authorizationService.authorizePatientDataAccess(
                linda.getUserId(), // Sarah is assigned to Linda
                "HEALTH_METRICS",
                1
            )
        );
    }

    /**
     * Scenario: Family caregiver (son) accessing dependent's data
     */
    @Test
    public void testAuthorizePatientDataAccess_FamilyCaregiverCanAccessDependent() {
        setSecurityContext(jake.getUserId(), "jake@example.com", "CAREGIVER");

        // Jake has dependent_access to Eleanor
        assertDoesNotThrow(() ->
            authorizationService.authorizePatientDataAccess(
                eleanor.getUserId(),
                "HEALTH_METRICS",
                6
            )
        );
    }

    /**
     * Scenario: Caregiver trying to access unassigned patient's data
     */
    @Test
    public void testAuthorizePatientDataAccess_CaregiverCannotAccessUnassignedPatient() {
        setSecurityContext(sarah.getUserId(), "sarah@example.com", "CAREGIVER");

        // Sarah is not assigned to Mary
        assertThrows(AuthorizationException.class, () ->
            authorizationService.authorizePatientDataAccess(
                mary.getUserId(),
                "MEDICAL_RECORDS",
                2
            )
        );
    }

    /**
     * Scenario: Admin can access all patient data
     */
    @Test
    public void testAuthorizePatientDataAccess_AdminCanAccessAllData() {
        User admin = createAndSaveUser(7, "admin@example.com", "ADMIN");
        setSecurityContext(admin.getUserId(), "admin@example.com", "ADMIN");

        // Admin can access Linda's data
        assertDoesNotThrow(() ->
            authorizationService.authorizePatientDataAccess(
                linda.getUserId(),
                "MEDICAL_RECORDS",
                1
            )
        );

        // Admin can access Mary's data
        assertDoesNotThrow(() ->
            authorizationService.authorizePatientDataAccess(
                mary.getUserId(),
                "MEDICAL_RECORDS",
                2
            )
        );

        // Admin can access Eleanor's data
        assertDoesNotThrow(() ->
            authorizationService.authorizePatientDataAccess(
                eleanor.getUserId(),
                "MEDICAL_RECORDS",
                6
            )
        );
    }

    /**
     * Test: Revoke doctor access
     */
    @Test
    public void testRevokeDoctorAccess() {
        setSecurityContext(drMatt.getUserId(), "matt@hospital.com", "DOCTOR");

        // Dr. Matt can access Linda before revocation
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
     * Test: Revoke family caregiver access
     */
    @Test
    public void testRevokeFamilyCaregiverAccess() {
        setSecurityContext(jake.getUserId(), "jake@example.com", "CAREGIVER");

        // Jake can access Eleanor before revocation
        assertDoesNotThrow(() ->
            authorizationService.authorizePatientDataAccess(
                eleanor.getUserId(),
                "HEALTH_METRICS",
                6
            )
        );

        // Revoke access
        relationshipService.revokeCaregiverAccessToDependent(jake.getUserId(), eleanor.getUserId());

        // Now Jake should be denied
        assertThrows(AuthorizationException.class, () ->
            authorizationService.authorizePatientDataAccess(
                eleanor.getUserId(),
                "HEALTH_METRICS",
                6
            )
        );
    }

    // ==================== HELPER METHODS ====================

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
