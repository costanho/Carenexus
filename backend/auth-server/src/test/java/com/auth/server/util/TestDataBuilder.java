package com.auth.server.util;

import com.auth.server.model.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;

public class TestDataBuilder {

    private static final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);

    // ==================== USERS ====================

    public static User createPatient(Integer userId, String email) {
        return User.builder()
                .userId(userId)
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

    public static User createDoctor(Integer userId, String email) {
        return User.builder()
                .userId(userId)
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

    public static User createCaregiver(Integer userId, String email) {
        return User.builder()
                .userId(userId)
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

    public static User createAdmin(Integer userId, String email) {
        return User.builder()
                .userId(userId)
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

    public static User createUser(Integer userId, String email, String role) {
        return User.builder()
                .userId(userId)
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

    public static CareTeam createDoctorPatientRelationship(Integer doctorId, Integer patientId) {
        return CareTeam.builder()
                .patientId(patientId)
                .doctorId(doctorId)
                .caregiverId(null)
                .facilityId(1)
                .role("PRIMARY_DOCTOR")
                .assignedAt(LocalDateTime.now())
                .isActive(true)
                .build();
    }

    public static CareTeam createCaregiverPatientRelationship(Integer caregiverId, Integer patientId) {
        return CareTeam.builder()
                .patientId(patientId)
                .doctorId(null)
                .caregiverId(caregiverId)
                .facilityId(1)
                .role("ASSIGNED_CAREGIVER")
                .assignedAt(LocalDateTime.now())
                .isActive(true)
                .build();
    }

    public static DependentAccess createDependentAccess(Integer dependentId, Integer caregiverId, Integer guardianId) {
        return DependentAccess.builder()
                .dependentId(dependentId)
                .caregiverId(caregiverId)
                .guardianId(guardianId)
                .permissionType("FULL")
                .authorizedAt(LocalDateTime.now())
                .authorizedBy(guardianId)
                .isActive(true)
                .build();
    }

    public static DependentAccess createDependentAccessWithPermission(
            Integer dependentId, Integer caregiverId, Integer guardianId, String permissionType) {
        return DependentAccess.builder()
                .dependentId(dependentId)
                .caregiverId(caregiverId)
                .guardianId(guardianId)
                .permissionType(permissionType)
                .authorizedAt(LocalDateTime.now())
                .authorizedBy(guardianId)
                .isActive(true)
                .build();
    }

    // ==================== TOKENS ====================

    public static RefreshToken createRefreshToken(Integer userId) {
        return RefreshToken.builder()
                .userId(userId)
                .tokenHash("refresh-token-" + userId)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .isRevoked(false)
                .deviceType("iOS")
                .deviceInfo("iPhone 14")
                .createdAt(LocalDateTime.now())
                .build();
    }

    // ==================== AUDIT ====================

    public static AuditLog createAuditLog(Integer userId, String action, String resourceType, Integer resourceId) {
        return AuditLog.builder()
                .userId(userId)
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
