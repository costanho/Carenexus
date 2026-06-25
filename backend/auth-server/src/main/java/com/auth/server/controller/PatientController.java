package com.auth.server.controller;

import com.auth.server.annotation.RequireOwnership;
import com.auth.server.annotation.RequireRole;
import com.auth.server.config.UserContext;
import com.auth.server.service.AuthorizationService;
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
     * GET /api/patients/{patientId}/health-metrics
     * Patient can view their own health metrics
     * Doctor can view assigned patient's health metrics
     * Requires: PATIENT role (owns data) OR DOCTOR role (assigned to patient)
     */
    @GetMapping("/{patientId}/health-metrics")
    public ResponseEntity<?> getHealthMetrics(@PathVariable Integer patientId) {
        // Check authorization: must be owner (patient) or have DOCTOR role
        authorizationService.authorizePatientDataAccess(patientId, "HEALTH_METRICS", null);

        // Now safe to return data
        return ResponseEntity.ok().body("Health metrics for patient " + patientId);
    }

    /**
     * GET /api/patients/{patientId}/medical-records
     * Comprehensive check:
     * - PATIENT can only see their own records
     * - DOCTOR can see assigned patients
     * - CAREGIVER can see dependents
     * - ADMIN can see all
     */
    @GetMapping("/{patientId}/medical-records")
    public ResponseEntity<?> getMedicalRecords(@PathVariable Integer patientId) {
        authorizationService.authorizePatientDataAccess(patientId, "MEDICAL_RECORDS", null);
        return ResponseEntity.ok().body("Medical records for patient " + patientId);
    }

    /**
     * POST /api/patients/{patientId}/appointments
     * Only the patient can create appointments for themselves
     */
    @PostMapping("/{patientId}/appointments")
    @RequireOwnership(ownerIdParam = "patientId", resourceType = "APPOINTMENT")
    public ResponseEntity<?> createAppointment(@PathVariable Integer patientId, @RequestBody Object appointmentData) {
        // Authorization check happens via @RequireOwnership annotation
        // User must be the owner (patient) of this patientId
        return ResponseEntity.ok().body("Appointment created for patient " + patientId);
    }

    /**
     * PUT /api/patients/{patientId}
     * Only the patient can update their own profile
     */
    @PutMapping("/{patientId}")
    @RequireOwnership(ownerIdParam = "patientId", resourceType = "PATIENT_PROFILE")
    public ResponseEntity<?> updatePatientProfile(@PathVariable Integer patientId, @RequestBody Object profileData) {
        // Only the patient themselves can update their profile
        return ResponseEntity.ok().body("Patient profile updated");
    }
}
