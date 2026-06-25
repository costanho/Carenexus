package com.auth.server.service;

import com.auth.server.config.UserContext;
import com.auth.server.exception.AuthorizationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Example service showing proper authorization patterns
 * Every method checks authorization before accessing data
 */
@Service
@RequiredArgsConstructor
public class PatientDataService {

    private final UserContext userContext;
    private final AuthorizationService authorizationService;
    private final RelationshipService relationshipService;
    private final AuditService auditService;

    /**
     * Get patient's health metrics
     * Multiple access rules:
     * - Patient can view own metrics
     * - Doctor assigned to patient can view
     * - Caregiver assigned to patient can view
     * - ADMIN can view all
     */
    public Object getPatientHealthMetrics(Integer patientId) {
        Integer userId = userContext.getCurrentUserId();
        String userRole = userContext.getCurrentUserRole();
        String ipAddress = "0.0.0.0";

        // Use comprehensive authorization check
        authorizationService.authorizePatientDataAccess(patientId, "HEALTH_METRICS", patientId);

        // If we reach here, authorization passed
        auditService.logResourceAccess(userId, "HEALTH_METRICS", patientId, ipAddress);

        // Fetch and return data (mock)
        return "Health metrics for patient " + patientId;
    }

    /**
     * Get patient's medical records
     * Same authorization as health metrics
     */
    public Object getPatientMedicalRecords(Integer patientId) {
        Integer userId = userContext.getCurrentUserId();
        authorizationService.authorizePatientDataAccess(patientId, "MEDICAL_RECORDS", patientId);
        auditService.logResourceAccess(userId, "MEDICAL_RECORDS", patientId, "0.0.0.0");
        return "Medical records for patient " + patientId;
    }

    /**
     * Get doctor's assigned patients
     * Only callable by doctors
     * Returns list of patients this specific doctor can access
     */
    public Object getDoctorAssignedPatients(Integer doctorId) {
        Integer currentUserId = userContext.getCurrentUserId();
        String userRole = userContext.getCurrentUserRole();

        // Only DOCTOR role can call this
        authorizationService.requireRole("DOCTOR");

        // Doctor can only view their own assignments
        authorizationService.requireOwnership(doctorId, "DOCTOR_ASSIGNMENTS", doctorId);

        // Get all patients assigned to this doctor
        var patientIds = relationshipService.getPatientsForDoctor(doctorId);

        auditService.logResourceAccess(currentUserId, "DOCTOR_ASSIGNMENTS", doctorId, "0.0.0.0");
        return "Patients assigned to doctor " + doctorId + ": " + patientIds;
    }

    /**
     * Get caregiver's assigned dependents
     * Only callable by caregivers
     * Returns list of dependents this caregiver can access
     */
    public Object getCaregiverAssignedDependents(Integer caregiverId) {
        Integer currentUserId = userContext.getCurrentUserId();
        String userRole = userContext.getCurrentUserRole();

        // Only CAREGIVER role can call this
        authorizationService.requireRole("CAREGIVER");

        // Caregiver can only view their own assignments
        authorizationService.requireOwnership(caregiverId, "CAREGIVER_ASSIGNMENTS", caregiverId);

        // Get all dependents assigned to this caregiver (from both care_team and dependent_access)
        var dependentsFromCareTeam = relationshipService.getPatientsForCaregiver(caregiverId);
        var dependentsFromAccess = relationshipService.getDependentsForCaregiver(caregiverId);

        auditService.logResourceAccess(currentUserId, "CAREGIVER_ASSIGNMENTS", caregiverId, "0.0.0.0");
        return "Dependents assigned to caregiver " + caregiverId;
    }

    /**
     * Get all people with access to patient's data
     * Only ADMIN or the patient themselves
     */
    public Object getPatientAccessList(Integer patientId) {
        Integer userId = userContext.getCurrentUserId();
        String userRole = userContext.getCurrentUserRole();

        // Either patient is viewing own access list, or admin viewing any
        if (!userId.equals(patientId) && !"ADMIN".equals(userRole)) {
            throw new AuthorizationException("Only patient or admin can view access list");
        }

        // Get all with access
        var doctors = relationshipService.getDoctorsForPatient(patientId);
        var caregivers = relationshipService.getCaregiversForPatient(patientId);

        auditService.logResourceAccess(userId, "ACCESS_LIST", patientId, "0.0.0.0");
        return "Access list for patient " + patientId;
    }

    /**
     * Revoke doctor access (admin only)
     * Critical operation - must audit
     */
    public void revokeDoctorAccess(Integer doctorId, Integer patientId) {
        Integer userId = userContext.getCurrentUserId();

        // Only ADMIN can revoke
        authorizationService.requireAdmin();

        // Perform revocation
        relationshipService.revokeDoctorAccessToPatient(doctorId, patientId);

        // Log critical action
        auditService.logResourceModification(userId, "UPDATE", "CARE_TEAM", patientId, "0.0.0.0");
    }

    /**
     * Comprehensive example: Get patient data with all checks
     * Shows the layered security approach
     */
    public Object getPatientComprehensiveData(Integer patientId) {
        Integer userId = userContext.getCurrentUserId();
        String userRole = userContext.getCurrentUserRole();
        String ipAddress = "0.0.0.0";

        // Layer 1: Is user authenticated?
        if (userId == null) {
            throw new AuthorizationException("User not authenticated");
        }

        // Layer 2: Does user have required role?
        authorizationService.requireAnyRole("PATIENT", "DOCTOR", "CAREGIVER", "ADMIN");

        // Layer 3: Does user have access to this specific patient?
        authorizationService.authorizePatientDataAccess(patientId, "COMPREHENSIVE_DATA", patientId);

        // Layer 4: Role-specific data filtering
        Object data = null;
        if ("PATIENT".equals(userRole)) {
            // Patient sees only their own data
            data = "Patient comprehensive data: " + patientId;
        } else if ("DOCTOR".equals(userRole)) {
            // Doctor sees patient data + treatment history
            data = "Doctor view of patient " + patientId;
        } else if ("CAREGIVER".equals(userRole)) {
            // Caregiver sees limited data based on permission type
            data = "Caregiver view of patient " + patientId;
        } else if ("ADMIN".equals(userRole)) {
            // Admin sees everything
            data = "Admin comprehensive view of patient " + patientId;
        }

        // Layer 5: Audit the access
        auditService.logResourceAccess(userId, "COMPREHENSIVE_DATA", patientId, ipAddress);

        return data;
    }
}
