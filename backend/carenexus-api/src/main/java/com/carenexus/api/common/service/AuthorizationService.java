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
     * @param ownerSystemId The systemId of the resource owner
     * @param resourceType Type of resource (for audit logging)
     * @param resourceId ID of the resource (for audit logging)
     */
    public void requireOwnership(String ownerSystemId, String resourceType, Integer resourceId) {
        String userSystemId = userContext.getCurrentUserSystemId();
        String ipAddress = "0.0.0.0"; // Would be extracted from request in real scenario

        if (!userSystemId.equals(ownerSystemId)) {
            auditService.logAccessDenied(
                    userSystemId,
                    resourceType,
                    resourceId,
                    ipAddress,
                    "Resource ownership check failed: user " + userSystemId + " is not owner of " + ownerSystemId
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
    public void requireOwnershipOrRole(String ownerSystemId, String requiredRole, String resourceType, Integer resourceId) {
        String userSystemId = userContext.getCurrentUserSystemId();
        String userRole = userContext.getCurrentUserRole();

        boolean hasAccess = userSystemId.equals(ownerSystemId) || requiredRole.equals(userRole);

        if (!hasAccess) {
            throw new AuthorizationException(
                    "You don't have permission to access this resource",
                    resourceType,
                    resourceId
            );
        }
    }

    /**
     * Get current user's systemId from context
     */
    public String getCurrentUserSystemId() {
        return userContext.getCurrentUserSystemId();
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
     * @param doctorSystemId The doctor's systemId
     * @param patientSystemId The patient's systemId
     * @return true if doctor is assigned to patient
     */
    public boolean isDoctorAssignedToPatient(String doctorSystemId, String patientSystemId) {
        return relationshipService.isDoctorAssignedToPatient(doctorSystemId, patientSystemId);
    }

    /**
     * Check if caregiver is assigned to dependent
     * Checks both care_team (formal caregivers) and dependent_access (family/informal)
     *
     * @param caregiverSystemId The caregiver's systemId
     * @param dependentSystemId The dependent patient's systemId
     * @return true if caregiver is assigned to dependent
     */
    public boolean isCaregiverAssignedToDependent(String caregiverSystemId, String dependentSystemId) {
        return relationshipService.isCaregiverAssignedToPatient(caregiverSystemId, dependentSystemId) ||
               relationshipService.hasAccessToDependent(caregiverSystemId, dependentSystemId);
    }

    /**
     * Comprehensive authorization check for viewing patient data
     * Used by endpoints that return patient medical records, etc.
     */
    public void authorizePatientDataAccess(String targetPatientSystemId, String resourceType, Integer resourceId) {
        String userSystemId = userContext.getCurrentUserSystemId();
        String userRole = userContext.getCurrentUserRole();
        String ipAddress = "0.0.0.0";

        // PATIENT can only access their own data
        if ("PATIENT".equals(userRole)) {
            if (!userSystemId.equals(targetPatientSystemId)) {
                auditService.logAccessDenied(userSystemId, resourceType, resourceId, ipAddress, "Patient accessing other patient's data");
                throw new AuthorizationException("Patient can only access their own data", resourceType, resourceId);
            }
            return;
        }

        // DOCTOR can access assigned patients' data
        if ("DOCTOR".equals(userRole)) {
            // TODO: Implement doctor-patient relationship check using systemIds
            if (!isDoctorAssignedToPatient(userSystemId, targetPatientSystemId)) {
                auditService.logAccessDenied(userSystemId, resourceType, resourceId, ipAddress, "Doctor not assigned to patient");
                throw new AuthorizationException("Doctor is not assigned to this patient", resourceType, resourceId);
            }
            return;
        }

        // CAREGIVER can access assigned dependents' data
        if ("CAREGIVER".equals(userRole)) {
            // TODO: Implement caregiver-dependent relationship check using systemIds
            if (!isCaregiverAssignedToDependent(userSystemId, targetPatientSystemId)) {
                auditService.logAccessDenied(userSystemId, resourceType, resourceId, ipAddress, "Caregiver not assigned to dependent");
                throw new AuthorizationException("Caregiver is not assigned to this dependent", resourceType, resourceId);
            }
            return;
        }

        // ADMIN can access all data
        if ("ADMIN".equals(userRole)) {
            return;
        }

        // Deny by default
        auditService.logAccessDenied(userSystemId, resourceType, resourceId, ipAddress, "Unauthorized role: " + userRole);
        throw new AuthorizationException("You don't have permission to access this resource", resourceType, resourceId);
    }
}
