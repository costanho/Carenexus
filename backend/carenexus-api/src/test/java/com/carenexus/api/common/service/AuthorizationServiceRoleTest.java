package com.carenexus.api.common.service;

import com.carenexus.api.BaseIntegrationTest;
import com.carenexus.api.common.config.UserContext;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.auth.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

public class AuthorizationServiceRoleTest extends BaseIntegrationTest {

    @Autowired
    private AuthorizationService authorizationService;

    @Autowired
    private UserContext userContext;

    private User doctor;
    private User patient;
    private User admin;

    @BeforeEach
    public void setUp() {
        // Create test users
        doctor = createAndSaveUser(1, "DOC-00001", "doctor@example.com", "DOCTOR");
        patient = createAndSaveUser(2, "PAT-00001", "patient@example.com", "PATIENT");
        admin = createAndSaveUser(3, "ADM-00001", "admin@example.com", "ADMIN");
    }

    /**
     * Test: Doctor can access doctor-only resource
     */
    @Test
    public void testRequireRole_DoctorCanAccessDoctorResource() {
        // Set security context with doctor role
        setSecurityContext(doctor.getUserId(), doctor.getSystemId(), "doctor@example.com", "DOCTOR");

        // Should not throw exception
        assertDoesNotThrow(() -> authorizationService.requireRole("DOCTOR"));
    }

    /**
     * Test: Patient cannot access doctor-only resource
     */
    @Test
    public void testRequireRole_PatientCannotAccessDoctorResource() {
        setSecurityContext(patient.getUserId(), patient.getSystemId(), "patient@example.com", "PATIENT");

        assertThrows(AuthorizationException.class, () -> authorizationService.requireRole("DOCTOR"));
    }

    /**
     * Test: Multiple roles - doctor is in list
     */
    @Test
    public void testRequireAnyRole_DoctorInList() {
        setSecurityContext(doctor.getUserId(), doctor.getSystemId(), "doctor@example.com", "DOCTOR");

        // Doctor is in the list [DOCTOR, NURSE]
        assertDoesNotThrow(() -> authorizationService.requireAnyRole("DOCTOR", "NURSE"));
    }

    /**
     * Test: Multiple roles - doctor not in list
     */
    @Test
    public void testRequireAnyRole_DoctorNotInList() {
        setSecurityContext(doctor.getUserId(), doctor.getSystemId(), "doctor@example.com", "DOCTOR");

        // Doctor is NOT in the list [PATIENT, CAREGIVER]
        assertThrows(AuthorizationException.class,
                () -> authorizationService.requireAnyRole("PATIENT", "CAREGIVER"));
    }

    /**
     * Test: Admin access required
     */
    @Test
    public void testRequireAdmin_AdminCanAccess() {
        setSecurityContext(admin.getUserId(), admin.getSystemId(), "admin@example.com", "ADMIN");

        assertDoesNotThrow(() -> authorizationService.requireAdmin());
    }

    /**
     * Test: Admin access required - doctor denied
     */
    @Test
    public void testRequireAdmin_DoctorDenied() {
        setSecurityContext(doctor.getUserId(), doctor.getSystemId(), "doctor@example.com", "DOCTOR");

        assertThrows(AuthorizationException.class, () -> authorizationService.requireAdmin());
    }

    /**
     * Test: Admin or Facility Admin allowed
     */
    @Test
    public void testRequireAdminAccess_AdminAllowed() {
        setSecurityContext(admin.getUserId(), admin.getSystemId(), "admin@example.com", "ADMIN");

        assertDoesNotThrow(() -> authorizationService.requireAdminAccess());
    }

    /**
     * Test: Admin or Facility Admin allowed - facility admin allowed
     */
    @Test
    public void testRequireAdminAccess_FacilityAdminAllowed() {
        User facilityAdmin = createAndSaveUser(4, "FAC-00001", "facility@example.com", "FACILITY_ADMIN");
        setSecurityContext(facilityAdmin.getUserId(), facilityAdmin.getSystemId(), "facility@example.com", "FACILITY_ADMIN");

        assertDoesNotThrow(() -> authorizationService.requireAdminAccess());
    }

    /**
     * Test: Admin or Facility Admin allowed - doctor denied
     */
    @Test
    public void testRequireAdminAccess_DoctorDenied() {
        setSecurityContext(doctor.getUserId(), doctor.getSystemId(), "doctor@example.com", "DOCTOR");

        assertThrows(AuthorizationException.class, () -> authorizationService.requireAdminAccess());
    }

    // ==================== HELPER METHODS ====================

    private void setSecurityContext(Integer userId, String systemId, String email, String role) {
        var userDetails = new com.carenexus.api.auth.config.CustomUserDetails(userId, systemId, email, role);
        var authentication = new UsernamePasswordAuthenticationToken(
                userDetails,
                null,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role))
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
