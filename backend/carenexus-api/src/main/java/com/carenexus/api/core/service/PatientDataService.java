package com.carenexus.api.core.service;

import com.carenexus.api.common.config.UserContext;
import com.carenexus.api.common.exception.AuthorizationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.AuditService;

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
    public Object getPatientHealthMetrics(String patientSystemId) {
        String userSystemId = userContext.getCurrentUserSystemId();
        String userRole = userContext.getCurrentUserRole();
        String ipAddress = "0.0.0.0";

        // Use comprehensive authorization check
        authorizationService.authorizePatientDataAccess(patientSystemId, "HEALTH_METRICS", 0);

        // If we reach here, authorization passed
        auditService.logResourceAccess(userSystemId, "HEALTH_METRICS", 0, ipAddress);

        // Fetch and return data (mock)
        return "Health metrics for patient " + patientSystemId;
    }

    /**
     * Get patient's medical records
     * Same authorization as health metrics
     */
    public Object getPatientMedicalRecords(String patientSystemId) {
        String userSystemId = userContext.getCurrentUserSystemId();
        authorizationService.authorizePatientDataAccess(patientSystemId, "MEDICAL_RECORDS", 0);
        auditService.logResourceAccess(userSystemId, "MEDICAL_RECORDS", 0, "0.0.0.0");
        return "Medical records for patient " + patientSystemId;
    }

    /**
     * Get doctor's assigned patients
     * Only callable by doctors
     * Returns list of patients this specific doctor can access
     */
    public Object getDoctorAssignedPatients(String doctorSystemId) {
        String currentUserSystemId = userContext.getCurrentUserSystemId();
        String userRole = userContext.getCurrentUserRole();

        // Only DOCTOR role can call this
        authorizationService.requireRole("DOCTOR");

        // Doctor can only view their own assignments
        authorizationService.requireOwnership(doctorSystemId, "DOCTOR_ASSIGNMENTS", 0);

        // Get all patients assigned to this doctor
        var patientSystemIds = relationshipService.getPatientsForDoctor(doctorSystemId);

        auditService.logResourceAccess(currentUserSystemId, "DOCTOR_ASSIGNMENTS", 0, "0.0.0.0");
        return "Patients assigned to doctor " + doctorSystemId + ": " + patientSystemIds;
    }

    /**
     * Get caregiver's assigned dependents
     * Only callable by caregivers
     * Returns list of dependents this caregiver can access
     */
    public Object getCaregiverAssignedDependents(String caregiverSystemId) {
        String currentUserSystemId = userContext.getCurrentUserSystemId();
        String userRole = userContext.getCurrentUserRole();

        // Only CAREGIVER role can call this
        authorizationService.requireRole("CAREGIVER");

        // Caregiver can only view their own assignments
        authorizationService.requireOwnership(caregiverSystemId, "CAREGIVER_ASSIGNMENTS", 0);

        // Get all dependents assigned to this caregiver (from both care_team and dependent_access)
        var dependentsFromCareTeam = relationshipService.getPatientsForCaregiver(caregiverSystemId);
        var dependentsFromAccess = relationshipService.getDependentsForCaregiver(caregiverSystemId);

        auditService.logResourceAccess(currentUserSystemId, "CAREGIVER_ASSIGNMENTS", 0, "0.0.0.0");
        return "Dependents assigned to caregiver " + caregiverSystemId;
    }

    /**
     * Get all people with access to patient's data
     * Only ADMIN or the patient themselves
     */
    public Object getPatientAccessList(String patientSystemId) {
        String userSystemId = userContext.getCurrentUserSystemId();
        String userRole = userContext.getCurrentUserRole();

        // Either patient is viewing own access list, or admin viewing any
        if (!userSystemId.equals(patientSystemId) && !"ADMIN".equals(userRole)) {
            throw new AuthorizationException("Only patient or admin can view access list");
        }

        // Get all with access
        var doctors = relationshipService.getDoctorsForPatient(patientSystemId);
        var caregivers = relationshipService.getCaregiversForPatient(patientSystemId);

        auditService.logResourceAccess(userSystemId, "ACCESS_LIST", 0, "0.0.0.0");
        return "Access list for patient " + patientSystemId;
    }

    /**
     * Revoke doctor access (admin only)
     * Critical operation - must audit
     */
    public void revokeDoctorAccess(String doctorSystemId, String patientSystemId) {
        String userSystemId = userContext.getCurrentUserSystemId();

        // Only ADMIN can revoke
        authorizationService.requireAdmin();

        // Perform revocation
        relationshipService.revokeDoctorAccessToPatient(doctorSystemId, patientSystemId);

        // Log critical action
        auditService.logResourceModification(userSystemId, "UPDATE", "CARE_TEAM", 0, "0.0.0.0");
    }

    /**
     * Comprehensive example: Get patient data with all checks
     * Shows the layered security approach
     */
    public Object getPatientComprehensiveData(String patientSystemId) {
        String userSystemId = userContext.getCurrentUserSystemId();
        String userRole = userContext.getCurrentUserRole();
        String ipAddress = "0.0.0.0";

        // Layer 1: Is user authenticated?
        if (userSystemId == null) {
            throw new AuthorizationException("User not authenticated");
        }

        // Layer 2: Does user have required role?
        authorizationService.requireAnyRole("PATIENT", "DOCTOR", "CAREGIVER", "ADMIN");

        // Layer 3: Does user have access to this specific patient?
        authorizationService.authorizePatientDataAccess(patientSystemId, "COMPREHENSIVE_DATA", 0);

        // Layer 4: Role-specific data filtering
        Object data = null;
        if ("PATIENT".equals(userRole)) {
            // Patient sees only their own data
            data = "Patient comprehensive data: " + patientSystemId;
        } else if ("DOCTOR".equals(userRole)) {
            // Doctor sees patient data + treatment history
            data = "Doctor view of patient " + patientSystemId;
        } else if ("CAREGIVER".equals(userRole)) {
            // Caregiver sees limited data based on permission type
            data = "Caregiver view of patient " + patientSystemId;
        } else if ("ADMIN".equals(userRole)) {
            // Admin sees everything
            data = "Admin comprehensive view of patient " + patientSystemId;
        }

        // Layer 5: Audit the access
        auditService.logResourceAccess(userSystemId, "COMPREHENSIVE_DATA", 0, ipAddress);

        return data;
    }
}
