package com.carenexus.api.common.util;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.core.model.CareTeam;
import com.carenexus.api.core.model.DependentAccess;
import com.carenexus.api.auth.model.RefreshToken;
import com.carenexus.api.common.model.AuditLog;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;

public class TestDataBuilder {

    private static final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);

    // ==================== USERS ====================

    public static User createPatient(Integer userId, String systemId, String email) {
        return User.builder()
                .userId(userId)
                .systemId(systemId)
                .firstName("Patient")
                .lastName("User" + userId)
                .email(email)
                .phone("+1234567890" + userId)
                .passwordHash(passwordEncoder.encode("password123"))
                .role("PATIENT")
                .isActive(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    public static User createDoctor(Integer userId, String systemId, String email) {
        return User.builder()
                .userId(userId)
                .systemId(systemId)
                .firstName("Dr")
                .lastName("Doctor" + userId)
                .email(email)
                .phone("+1234567890" + userId)
                .passwordHash(passwordEncoder.encode("password123"))
                .role("DOCTOR")
                .isActive(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    public static User createCaregiver(Integer userId, String systemId, String email) {
        return User.builder()
                .userId(userId)
                .systemId(systemId)
                .firstName("Caregiver")
                .lastName("User" + userId)
                .email(email)
                .phone("+1234567890" + userId)
                .passwordHash(passwordEncoder.encode("password123"))
                .role("CAREGIVER")
                .isActive(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    public static User createAdmin(Integer userId, String systemId, String email) {
        return User.builder()
                .userId(userId)
                .systemId(systemId)
                .firstName("Admin")
                .lastName("User" + userId)
                .email(email)
                .phone("+1234567890" + userId)
                .passwordHash(passwordEncoder.encode("password123"))
                .role("ADMIN")
                .isActive(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    public static User createUser(Integer userId, String systemId, String email, String role) {
        return User.builder()
                .userId(userId)
                .systemId(systemId)
                .firstName(role)
                .lastName("User" + userId)
                .email(email)
                .phone("+1234567890" + userId)
                .passwordHash(passwordEncoder.encode("password123"))
                .role(role)
                .isActive(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    // ==================== RELATIONSHIPS ====================

    public static CareTeam createDoctorPatientRelationship(String doctorSystemId, String patientSystemId) {
        return CareTeam.builder()
                .patientSystemId(patientSystemId)
                .providerSystemId(doctorSystemId)
                .facilityId(1)
                .role("PRIMARY_DOCTOR")
                .assignedAt(LocalDateTime.now())
                .isActive(true)
                .build();
    }

    public static CareTeam createCaregiverPatientRelationship(String caregiverSystemId, String patientSystemId) {
        return CareTeam.builder()
                .patientSystemId(patientSystemId)
                .providerSystemId(caregiverSystemId)
                .facilityId(1)
                .role("ASSIGNED_CAREGIVER")
                .assignedAt(LocalDateTime.now())
                .isActive(true)
                .build();
    }

    public static DependentAccess createDependentAccess(String patientSystemId, String caregiverSystemId, String authorizedBySystemId) {
        return DependentAccess.builder()
                .patientSystemId(patientSystemId)
                .caregiverSystemId(caregiverSystemId)
                .authorizedBySystemId(authorizedBySystemId)
                .accessLevel("FULL")
                .authorizedAt(LocalDateTime.now())
                .isActive(true)
                .build();
    }

    public static DependentAccess createDependentAccessWithPermission(
            String patientSystemId, String caregiverSystemId, String authorizedBySystemId, String accessLevel) {
        return DependentAccess.builder()
                .patientSystemId(patientSystemId)
                .caregiverSystemId(caregiverSystemId)
                .authorizedBySystemId(authorizedBySystemId)
                .accessLevel(accessLevel)
                .authorizedAt(LocalDateTime.now())
                .isActive(true)
                .build();
    }

    // ==================== TOKENS ====================

    public static RefreshToken createRefreshToken(String systemId) {
        return RefreshToken.builder()
                .systemId(systemId)
                .tokenHash("refresh-token-" + systemId)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .isRevoked(false)
                .deviceType("iOS")
                .deviceInfo("iPhone 14")
                .createdAt(LocalDateTime.now())
                .build();
    }

    // ==================== AUDIT ====================

    public static AuditLog createAuditLog(String systemId, String action, String resourceType, Integer resourceId) {
        return AuditLog.builder()
                .systemId(systemId)
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .status("SUCCESS")
                .ipAddress("192.168.1.100")
                .userAgent("Mozilla/5.0...")
                .timestamp(LocalDateTime.now())
                .build();
    }
}
