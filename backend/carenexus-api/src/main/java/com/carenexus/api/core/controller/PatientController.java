package com.carenexus.api.core.controller;

import com.carenexus.api.common.annotation.RequireOwnership;
import com.carenexus.api.common.annotation.RequireRole;
import com.carenexus.api.common.config.UserContext;
import com.carenexus.api.common.service.AuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/patients")
@RequiredArgsConstructor
public class PatientController {

    private final UserContext userContext;
    private final AuthorizationService authorizationService;

    /**
     * GET /api/patients/{patientSystemId}/health-metrics
     * Patient can view their own health metrics
     * Doctor can view assigned patient's health metrics
     * Requires: PATIENT role (owns data) OR DOCTOR role (assigned to patient)
     */
    @GetMapping("/{patientSystemId}/health-metrics")
    public ResponseEntity<?> getHealthMetrics(@PathVariable String patientSystemId) {
        // Check authorization: must be owner (patient) or have DOCTOR role
        authorizationService.authorizePatientDataAccess(patientSystemId, "HEALTH_METRICS", null);

        // Now safe to return data
        return ResponseEntity.ok().body("Health metrics for patient " + patientSystemId);
    }

    /**
     * GET /api/patients/{patientSystemId}/medical-records
     * Comprehensive check:
     * - PATIENT can only see their own records
     * - DOCTOR can see assigned patients
     * - CAREGIVER can see dependents
     * - ADMIN can see all
     */
    @GetMapping("/{patientSystemId}/medical-records")
    public ResponseEntity<?> getMedicalRecords(@PathVariable String patientSystemId) {
        authorizationService.authorizePatientDataAccess(patientSystemId, "MEDICAL_RECORDS", null);
        return ResponseEntity.ok().body("Medical records for patient " + patientSystemId);
    }

    /**
     * POST /api/patients/{patientSystemId}/appointments
     * Only the patient can create appointments for themselves
     */
    @PostMapping("/{patientSystemId}/appointments")
    @RequireOwnership(ownerIdParam = "patientSystemId", resourceType = "APPOINTMENT")
    public ResponseEntity<?> createAppointment(@PathVariable String patientSystemId, @RequestBody Object appointmentData) {
        // Authorization check happens via @RequireOwnership annotation
        // User must be the owner (patient) of this patientSystemId
        return ResponseEntity.ok().body("Appointment created for patient " + patientSystemId);
    }

    /**
     * PUT /api/patients/{patientSystemId}
     * Only the patient can update their own profile
     */
    @PutMapping("/{patientSystemId}")
    @RequireOwnership(ownerIdParam = "patientSystemId", resourceType = "PATIENT_PROFILE")
    public ResponseEntity<?> updatePatientProfile(@PathVariable String patientSystemId, @RequestBody Object profileData) {
        // Only the patient themselves can update their profile
        return ResponseEntity.ok().body("Patient profile updated");
    }
}
