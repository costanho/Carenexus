package com.carenexus.api.common.service;

import com.carenexus.api.common.config.UserContext;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.carenexus.api.core.service.RelationshipService;

@Service
@RequiredArgsConstructor
public class AuthorizationService {

    private final UserContext userContext;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final RelationshipService relationshipService;

    /**
     * Check if current user has required role
     * Throws AuthorizationException if not
     */
    public void requireRole(String requiredRole) {
        String userRole = userContext.getCurrentUserRole();
        if (userRole == null || !userRole.equals(requiredRole)) {
            throw new AuthorizationException("User role " + userRole + " is not authorized to perform this action");
        }
    }

    /**
     * Check if current user has any of the required roles
     */
    public void requireAnyRole(String... requiredRoles) {
        String userRole = userContext.getCurrentUserRole();
        boolean authorized = false;
        for (String role : requiredRoles) {
            if (role.equals(userRole)) {
                authorized = true;
                break;
            }
        }
        if (!authorized) {
            throw new AuthorizationException("User role " + userRole + " is not authorized to perform this action");
        }
    }

    /**
     * Check if current user OWNS the resource
     * e.g., Patient can only access their own health records
     *
     * @param ownerId The ID of the resource owner
     * @param resourceType Type of resource (for audit logging)
     * @param resourceId ID of the resource (for audit logging)
     */
    public void requireOwnership(Integer ownerId, String resourceType, Integer resourceId) {
        Integer userId = userContext.getCurrentUserId();
        String ipAddress = "0.0.0.0"; // Would be extracted from request in real scenario

        if (!userId.equals(ownerId)) {
            auditService.logAccessDenied(
                    userId,
                    resourceType,
                    resourceId,
                    ipAddress,
                    "Resource ownership check failed: user " + userId + " is not owner of " + ownerId
            );
            throw new AuthorizationException(
                    "You don't have permission to access this resource",
                    resourceType,
                    resourceId
            );
        }
    }

    /**
     * Check if user is either the owner or has a specific role
     * e.g., Patient owns their data, Doctor can access with DOCTOR role
     */
    public void requireOwnershipOrRole(Integer ownerId, String requiredRole, String resourceType, Integer resourceId) {
        Integer userId = userContext.getCurrentUserId();
        String userRole = userContext.getCurrentUserRole();

        boolean hasAccess = userId.equals(ownerId) || requiredRole.equals(userRole);

        if (!hasAccess) {
            throw new AuthorizationException(
                    "You don't have permission to access this resource",
                    resourceType,
                    resourceId
            );
        }
    }

    /**
     * Check if user is ADMIN
     */
    public void requireAdmin() {
        String userRole = userContext.getCurrentUserRole();
        if (userRole == null || !userRole.equals("ADMIN")) {
            throw new AuthorizationException("Admin role required");
        }
    }

    /**
     * Check if user is ADMIN or FACILITY_ADMIN
     */
    public void requireAdminAccess() {
        String userRole = userContext.getCurrentUserRole();
        if (userRole == null || (!userRole.equals("ADMIN") && !userRole.equals("FACILITY_ADMIN"))) {
            throw new AuthorizationException("Admin or Facility Admin role required");
        }
    }

    /**
     * Check if doctor is assigned to patient
     * Used before returning patient data to doctor
     *
     * @param doctorId The doctor's user ID
     * @param patientId The patient's user ID
     * @return true if doctor is assigned to patient
     */
    public boolean isDoctorAssignedToPatient(Integer doctorId, Integer patientId) {
        return relationshipService.isDoctorAssignedToPatient(doctorId, patientId);
    }

    /**
     * Check if caregiver is assigned to dependent
     * Checks both care_team (formal caregivers) and dependent_access (family/informal)
     *
     * @param caregiverId The caregiver's user ID
     * @param dependentId The dependent patient's user ID
     * @return true if caregiver is assigned to dependent
     */
    public boolean isCaregiverAssignedToDependent(Integer caregiverId, Integer dependentId) {
        // Check formal care_team relationship
        if (relationshipService.isCaregiverAssignedToPatient(caregiverId, dependentId)) {
            return true;
        }
        // Check informal dependent_access (family member, parent, etc.)
        return relationshipService.hasAccessToDependent(caregiverId, dependentId);
    }

    /**
     * Comprehensive authorization check for viewing patient data
     * Used by endpoints that return patient medical records, etc.
     */
    public void authorizePatientDataAccess(Integer targetPatientId, String resourceType, Integer resourceId) {
        Integer userId = userContext.getCurrentUserId();
        String userRole = userContext.getCurrentUserRole();
        String ipAddress = "0.0.0.0";

        // PATIENT can only access their own data
        if ("PATIENT".equals(userRole)) {
            if (!userId.equals(targetPatientId)) {
                auditService.logAccessDenied(userId, resourceType, resourceId, ipAddress, "Patient accessing other patient's data");
                throw new AuthorizationException("Patient can only access their own data", resourceType, resourceId);
            }
            return;
        }

        // DOCTOR can access assigned patients' data
        if ("DOCTOR".equals(userRole)) {
            if (!isDoctorAssignedToPatient(userId, targetPatientId)) {
                auditService.logAccessDenied(userId, resourceType, resourceId, ipAddress, "Doctor not assigned to patient");
                throw new AuthorizationException("Doctor is not assigned to this patient", resourceType, resourceId);
            }
            return;
        }

        // CAREGIVER can access assigned dependents' data
        if ("CAREGIVER".equals(userRole)) {
            if (!isCaregiverAssignedToDependent(userId, targetPatientId)) {
                auditService.logAccessDenied(userId, resourceType, resourceId, ipAddress, "Caregiver not assigned to dependent");
                throw new AuthorizationException("Caregiver is not assigned to this dependent", resourceType, resourceId);
            }
            return;
        }

        // ADMIN can access all data
        if ("ADMIN".equals(userRole)) {
            return;
        }

        // Deny by default
        auditService.logAccessDenied(userId, resourceType, resourceId, ipAddress, "Unauthorized role: " + userRole);
        throw new AuthorizationException("You don't have permission to access this resource", resourceType, resourceId);
    }
}
