package com.auth.server.service;

import com.auth.server.BaseIntegrationTest;
import com.auth.server.exception.AuthorizationException;
import com.auth.server.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

public class AuthorizationServiceOwnershipTest extends BaseIntegrationTest {

    @Autowired
    private AuthorizationService authorizationService;

    private User linda;
    private User mary;

    @BeforeEach
    public void setUp() {
        linda = createAndSaveUser(1, "linda@example.com", "PATIENT");
        mary = createAndSaveUser(2, "mary@example.com", "PATIENT");
    }

    /**
     * Test: Patient can access their own records
     */
    @Test
    public void testRequireOwnership_PatientOwnsTheirOwnData() {
        setSecurityContext(linda.getUserId(), "linda@example.com", "PATIENT");

        // Linda is owner, Linda is accessing → should pass
        assertDoesNotThrow(() ->
            authorizationService.requireOwnership(
                linda.getUserId(), // ownerId
                "PATIENT",
                1
            )
        );
    }

    /**
     * Test: Patient cannot access other patient's records
     */
    @Test
    public void testRequireOwnership_PatientCannotAccessOtherPatient() {
        setSecurityContext(linda.getUserId(), "linda@example.com", "PATIENT");

        // Mary is owner, Linda is accessing → should fail
        assertThrows(AuthorizationException.class, () ->
            authorizationService.requireOwnership(
                mary.getUserId(), // ownerId (Mary)
                "PATIENT",
                2
            )
        );
    }

    /**
     * Test: Ownership or role - user is owner
     */
    @Test
    public void testRequireOwnershipOrRole_UserIsOwner() {
        setSecurityContext(linda.getUserId(), "linda@example.com", "PATIENT");

        // Linda is owner → should pass (doesn't matter if role matches)
        assertDoesNotThrow(() ->
            authorizationService.requireOwnershipOrRole(
                linda.getUserId(),
                "DOCTOR",
                "PATIENT",
                1
            )
        );
    }

    /**
     * Test: Ownership or role - user has role but not owner
     */
    @Test
    public void testRequireOwnershipOrRole_UserHasRequiredRole() {
        User doctor = createAndSaveUser(3, "doctor@example.com", "DOCTOR");
        setSecurityContext(doctor.getUserId(), "doctor@example.com", "DOCTOR");

        // Doctor is not owner, but has DOCTOR role → should pass
        assertDoesNotThrow(() ->
            authorizationService.requireOwnershipOrRole(
                linda.getUserId(), // owner
                "DOCTOR", // required role
                "PATIENT",
                1
            )
        );
    }

    /**
     * Test: Ownership or role - user is neither owner nor has role
     */
    @Test
    public void testRequireOwnershipOrRole_UserDenied() {
        setSecurityContext(linda.getUserId(), "linda@example.com", "PATIENT");

        // Linda is not owner, doesn't have DOCTOR role → should fail
        assertThrows(AuthorizationException.class, () ->
            authorizationService.requireOwnershipOrRole(
                mary.getUserId(), // owner is Mary
                "DOCTOR", // required role
                "PATIENT",
                2
            )
        );
    }

    // ==================== HELPER METHODS ====================

    private void setSecurityContext(Integer userId, String email, String role) {
        var userDetails = new com.auth.server.config.CustomUserDetails(userId, email, role);
        var authentication = new UsernamePasswordAuthenticationToken(
                userDetails,
                null,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role))
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
