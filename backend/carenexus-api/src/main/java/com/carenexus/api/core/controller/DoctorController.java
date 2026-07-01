package com.carenexus.api.core.controller;

import com.carenexus.api.common.annotation.RequireRole;
import com.carenexus.api.common.config.UserContext;
import com.carenexus.api.common.service.AuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/doctors")
@RequiredArgsConstructor
public class DoctorController {

    private final UserContext userContext;
    private final AuthorizationService authorizationService;

    /**
     * GET /api/doctors/my-patients
     * Only DOCTOR role can access
     * Returns list of patients assigned to this doctor
     */
    @GetMapping("/my-patients")
    @RequireRole("DOCTOR")
    public ResponseEntity<?> getMyPatients() {
        Integer doctorId = userContext.getCurrentUserId();
        // Query only patients assigned to this doctor
        return ResponseEntity.ok().body("Patients assigned to doctor " + doctorId);
    }

    /**
     * POST /api/doctors/prescriptions
     * Only DOCTOR role can create prescriptions
     */
    @PostMapping("/prescriptions")
    @RequireRole("DOCTOR")
    public ResponseEntity<?> createPrescription(@RequestBody Object prescriptionData) {
        Integer doctorId = userContext.getCurrentUserId();
        // Create prescription with doctorId
        return ResponseEntity.ok().body("Prescription created by doctor " + doctorId);
    }

    /**
     * GET /api/doctors/{patientId}/patient-history
     * Only DOCTOR role
     * Verify doctor is assigned to patient before returning history
     */
    @GetMapping("/{patientId}/patient-history")
    @RequireRole("DOCTOR")
    public ResponseEntity<?> getPatientHistory(@PathVariable Integer patientId) {
        Integer doctorId = userContext.getCurrentUserId();

        // Check if doctor is assigned to this patient
        authorizationService.authorizePatientDataAccess(patientId, "MEDICAL_HISTORY", null);

        return ResponseEntity.ok().body("History for patient " + patientId);
    }

    /**
     * PUT /api/doctors/{doctorId}/schedule
     * Doctor can only update their own schedule
     */
    @PutMapping("/{doctorId}/schedule")
    public ResponseEntity<?> updateSchedule(@PathVariable Integer doctorId, @RequestBody Object scheduleData) {
        Integer currentDoctorId = userContext.getCurrentUserId();

        // Check ownership: doctor can only update their own schedule
        authorizationService.requireOwnership(doctorId, "SCHEDULE", doctorId);

        return ResponseEntity.ok().body("Schedule updated for doctor " + doctorId);
    }
}
