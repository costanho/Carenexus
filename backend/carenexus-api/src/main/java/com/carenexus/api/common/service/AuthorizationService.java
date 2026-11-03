package com.carenexus.api.common.service;

import com.carenexus.api.common.config.UserContext;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.core.model.DependentAccess;
import com.carenexus.api.core.service.RelationshipService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Central authorization checks. Every require* method THROWS {@link AuthorizationException} (HTTP 403) on
 * denial, and writes an ACCESS_DENIED audit row (in its own transaction) for patient-data denials.
 *
 * The caller's identity comes from the Spring Security context (set by JwtAuthenticationFilter), falling back
 * to the "systemId" / "userRole" request attributes. Roles are uppercase: PATIENT, DOCTOR, CAREGIVER, ADMIN.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthorizationService {

    public static final String PATIENT = "PATIENT";
    public static final String DOCTOR = "DOCTOR";
    public static final String CAREGIVER = "CAREGIVER";
    public static final String ADMIN = "ADMIN";

    private final UserContext userContext;
    private final AuditService auditService;
    private final RelationshipService relationshipService;

    // ==================== CALLER ====================

    /**
     * System ID of the authenticated caller. Throws 401 if the request is not authenticated.
     */
    public String currentSystemId() {
        String systemId = userContext.getCurrentUserSystemId();
        if (systemId == null) {
            systemId = requestAttribute("systemId");
        }
        if (systemId == null) {
            throw new InsufficientAuthenticationException("Authentication required");
        }
        return systemId;
    }

    /**
     * Uppercase role of the authenticated caller (PATIENT, DOCTOR, CAREGIVER, ADMIN). Throws 401 if unauthenticated.
     */
    public String currentRole() {
        String role = userContext.getCurrentUserRole();
        if (role == null) {
            role = requestAttribute("userRole");
        }
        if (role == null) {
            throw new InsufficientAuthenticationException("Authentication required");
        }
        return role.toUpperCase(Locale.ROOT);
    }

    public boolean isAdmin() {
        return ADMIN.equals(currentRoleOrNull());
    }

    // ==================== ROLE / SELF CHECKS ====================

    /**
     * Caller must have one of the given roles (e.g. requireRole("DOCTOR", "ADMIN")).
     */
    public void requireRole(String... roles) {
        String userRole = currentRoleOrNull();
        boolean authorized = userRole != null && Arrays.stream(roles).anyMatch(r -> r.equalsIgnoreCase(userRole));
        if (!authorized) {
            throw new AuthorizationException("Your role is not authorized to perform this action");
        }
    }

    /**
     * Same as {@link #requireRole(String...)} (kept for existing callers).
     */
    public void requireAnyRole(String... requiredRoles) {
        requireRole(requiredRoles);
    }

    /**
     * Caller is the target user, or an ADMIN. Use for account-level data (profile, settings, emergency contacts
     * of oneself). For a patient's data accessed by others use {@link #requirePatientAccess}.
     */
    public void requireSelfOrAdmin(String targetSystemId) {
        String userSystemId = currentSystemId();
        if (userSystemId.equals(targetSystemId) || isAdmin()) {
            return;
        }
        deny(userSystemId, "USER", null, null, "Self-or-admin check failed for " + targetSystemId,
                "You can only access your own account");
    }

    /**
     * Caller is the DOCTOR with this system ID, or an ADMIN. Use for /doctor/{doctorSystemId} endpoints.
     */
    public void requireDoctorSelf(String doctorSystemId) {
        requireSelfWithRole(DOCTOR, doctorSystemId, "You can only access your own doctor data");
    }

    /**
     * Caller is the CAREGIVER with this system ID, or an ADMIN.
     */
    public void requireCaregiverSelf(String caregiverSystemId) {
        requireSelfWithRole(CAREGIVER, caregiverSystemId, "You can only access your own caregiver data");
    }

    /**
     * Admin only
     */
    public void requireAdmin() {
        if (!isAdmin()) {
            throw new AuthorizationException("Admin role required");
        }
    }

    /**
     * Admin (FACILITY_ADMIN is not a role in this system, it is accepted for backwards compatibility only)
     */
    public void requireAdminAccess() {
        String userRole = currentRoleOrNull();
        if (!ADMIN.equals(userRole) && !"FACILITY_ADMIN".equals(userRole)) {
            throw new AuthorizationException("Admin or Facility Admin role required");
        }
    }

    // ==================== PATIENT DATA ====================

    /**
     * Caller may perform {@code permission} on this patient's data:
     * - PATIENT: only their own data;
     * - DOCTOR: patient they have a care relationship with (care_team, non-cancelled appointment or consultation);
     * - CAREGIVER: active (not revoked, not expired) dependent_access whose flag for {@code permission} is TRUE
     *   (FULL_ACCESS grants all; write permissions also require access_level other than VIEW_ONLY);
     * - ADMIN: allowed.
     * Anything else is denied with 403 and an ACCESS_DENIED audit row.
     */
    public void requirePatientAccess(String patientSystemId, PatientPermission permission) {
        requirePatientAccess(patientSystemId, permission, null);
    }

    /**
     * Same as {@link #requirePatientAccess(String, PatientPermission)}, recording {@code resourceId} on denial.
     */
    public void requirePatientAccess(String patientSystemId, PatientPermission permission, Integer resourceId) {
        if (patientSystemId == null || patientSystemId.isBlank()) {
            throw new IllegalArgumentException("Patient system ID is required");
        }
        String userSystemId = currentSystemId();
        String userRole = currentRole();
        String resourceType = permission.getResourceType();

        switch (userRole) {
            case PATIENT -> {
                if (!userSystemId.equals(patientSystemId)) {
                    deny(userSystemId, resourceType, resourceId, patientSystemId,
                            "Patient accessing another patient's data", "Patients can only access their own data");
                }
            }
            case DOCTOR -> {
                if (!relationshipService.isDoctorAssignedToPatient(userSystemId, patientSystemId)) {
                    deny(userSystemId, resourceType, resourceId, patientSystemId,
                            "Doctor has no care relationship with patient", "You are not part of this patient's care team");
                }
            }
            case CAREGIVER -> {
                Optional<DependentAccess> access = relationshipService.getActiveDependentAccess(userSystemId, patientSystemId);
                if (access.isEmpty()) {
                    deny(userSystemId, resourceType, resourceId, patientSystemId,
                            "Caregiver has no active access to patient", "You don't have active proxy access to this patient");
                }
                if (!permission.isGrantedBy(access.get())) {
                    deny(userSystemId, resourceType, resourceId, patientSystemId,
                            "Caregiver access does not grant " + permission,
                            "Your proxy access for this patient does not allow " + describe(permission));
                }
            }
            case ADMIN -> {
                // allowed
            }
            default -> deny(userSystemId, resourceType, resourceId, patientSystemId,
                    "Unauthorized role: " + userRole, "You don't have permission to access this resource");
        }
    }

    /**
     * Non-throwing variant for filtering (e.g. hiding fields a caregiver may not see). Does not write audit rows.
     */
    public boolean canAccessPatient(String patientSystemId, PatientPermission permission) {
        String userSystemId = userContext.getCurrentUserSystemId();
        String userRole = currentRoleOrNull();
        if (patientSystemId == null || userSystemId == null || userRole == null) {
            return false;
        }
        return switch (userRole) {
            case PATIENT -> userSystemId.equals(patientSystemId);
            case DOCTOR -> relationshipService.isDoctorAssignedToPatient(userSystemId, patientSystemId);
            case CAREGIVER -> relationshipService.getActiveDependentAccess(userSystemId, patientSystemId)
                    .map(permission::isGrantedBy)
                    .orElse(false);
            case ADMIN -> true;
            default -> false;
        };
    }

    // ==================== LEGACY API (kept so existing callers compile) ====================

    /**
     * Legacy patient-data check. Maps the resourceType string to a {@link PatientPermission} (read access) and
     * delegates to {@link #requirePatientAccess(String, PatientPermission, Integer)}.
     */
    public void authorizePatientDataAccess(String targetPatientSystemId, String resourceType, Integer resourceId) {
        requirePatientAccess(targetPatientSystemId, permissionForResourceType(resourceType), resourceId);
    }

    /**
     * Caller must be the owner of the resource (no admin bypass).
     */
    public void requireOwnership(String ownerSystemId, String resourceType, Integer resourceId) {
        String userSystemId = currentSystemId();
        if (!userSystemId.equals(ownerSystemId)) {
            deny(userSystemId, resourceType, resourceId, null,
                    "Resource ownership check failed: user " + userSystemId + " is not owner of " + ownerSystemId,
                    "You don't have permission to access this resource");
        }
    }

    /**
     * Caller is the owner, or has the given role.
     */
    public void requireOwnershipOrRole(String ownerSystemId, String requiredRole, String resourceType, Integer resourceId) {
        String userSystemId = currentSystemId();
        String userRole = currentRole();
        if (!userSystemId.equals(ownerSystemId) && !requiredRole.equalsIgnoreCase(userRole)) {
            throw new AuthorizationException("You don't have permission to access this resource", resourceType, resourceId);
        }
    }

    public String getCurrentUserSystemId() {
        return userContext.getCurrentUserSystemId();
    }

    public boolean isDoctorAssignedToPatient(String doctorSystemId, String patientSystemId) {
        return relationshipService.isDoctorAssignedToPatient(doctorSystemId, patientSystemId);
    }

    /**
     * True if the caregiver has an active (not revoked, not expired) dependent_access grant for the patient.
     */
    public boolean isCaregiverAssignedToDependent(String caregiverSystemId, String dependentSystemId) {
        return relationshipService.hasAccessToDependent(caregiverSystemId, dependentSystemId);
    }

    // ==================== HELPERS ====================

    static PatientPermission permissionForResourceType(String resourceType) {
        if (resourceType == null) {
            return PatientPermission.RECORDS;
        }
        String type = resourceType.toUpperCase(Locale.ROOT);
        if (type.contains("LAB")) return PatientPermission.LAB_RESULTS;
        if (type.contains("IMAGING")) return PatientPermission.IMAGING;
        if (type.contains("PRESCRIPTION") || type.contains("MEDICATION")) return PatientPermission.MEDICATIONS;
        if (type.contains("APPOINTMENT")) return PatientPermission.APPOINTMENTS;
        if (type.contains("CONSULTATION")) return PatientPermission.CONSULTATIONS;
        if (type.equals("PROFILE") || type.equals("PATIENT_PROFILE")) return PatientPermission.PROFILE;
        // MEDICAL_RECORDS, MEDICAL_HISTORY, HEALTH_METRICS, COMPREHENSIVE_DATA, unknown -> records
        return PatientPermission.RECORDS;
    }

    private static String describe(PatientPermission permission) {
        return switch (permission) {
            case PROFILE -> "viewing the profile";
            case MANAGE_PROFILE -> "editing the profile";
            case RECORDS -> "viewing medical records";
            case MEDICATIONS -> "viewing medications";
            case LAB_RESULTS -> "viewing lab results";
            case IMAGING -> "viewing imaging results";
            case APPOINTMENTS -> "viewing appointments";
            case CONSULTATIONS -> "viewing consultations";
            case MANAGE_APPOINTMENTS -> "managing appointments";
            case MANAGE_CARE_PLAN -> "managing the care plan";
            case MESSAGE_CARE_TEAM -> "messaging the care team";
        };
    }

    private void requireSelfWithRole(String role, String targetSystemId, String message) {
        String userSystemId = currentSystemId();
        String userRole = currentRole();
        if (ADMIN.equals(userRole)) {
            return;
        }
        if (role.equals(userRole) && userSystemId.equals(targetSystemId)) {
            return;
        }
        deny(userSystemId, role, null, null, role + " self check failed for " + targetSystemId, message);
    }

    /**
     * Audit (REQUIRES_NEW) and throw. The audit write never masks the denial.
     */
    private void deny(String userSystemId, String resourceType, Integer resourceId, String patientSystemId,
                      String auditReason, String clientMessage) {
        try {
            auditService.logAccessDenied(userSystemId, resourceType, resourceId, patientSystemId, null, auditReason);
        } catch (RuntimeException auditFailure) {
            log.error("Failed to write ACCESS_DENIED audit row: {}", auditFailure.getClass().getName());
        }
        throw new AuthorizationException(clientMessage, resourceType, resourceId);
    }

    private String currentRoleOrNull() {
        String role = userContext.getCurrentUserRole();
        if (role == null) {
            role = requestAttribute("userRole");
        }
        return role == null ? null : role.toUpperCase(Locale.ROOT);
    }

    private static String requestAttribute(String name) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        Object value = attributes.getAttribute(name, RequestAttributes.SCOPE_REQUEST);
        return value != null ? value.toString() : null;
    }
}
