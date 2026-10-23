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
        String doctorSystemId = userContext.getCurrentUserSystemId();
        // Query only patients assigned to this doctor
        return ResponseEntity.ok().body("Patients assigned to doctor " + doctorSystemId);
    }

    /**
     * POST /api/doctors/prescriptions
     * Only DOCTOR role can create prescriptions
     */
    @PostMapping("/prescriptions")
    @RequireRole("DOCTOR")
    public ResponseEntity<?> createPrescription(@RequestBody Object prescriptionData) {
        String doctorSystemId = userContext.getCurrentUserSystemId();
        // Create prescription with doctorSystemId
        return ResponseEntity.ok().body("Prescription created by doctor " + doctorSystemId);
    }

    /**
     * GET /api/doctors/{patientSystemId}/patient-history
     * Only DOCTOR role
     * Verify doctor is assigned to patient before returning history
     */
    @GetMapping("/{patientSystemId}/patient-history")
    @RequireRole("DOCTOR")
    public ResponseEntity<?> getPatientHistory(@PathVariable String patientSystemId) {
        String doctorSystemId = userContext.getCurrentUserSystemId();

        // Check if doctor is assigned to this patient
        authorizationService.authorizePatientDataAccess(patientSystemId, "MEDICAL_HISTORY", null);

        return ResponseEntity.ok().body("History for patient " + patientSystemId);
    }

    /**
     * PUT /api/doctors/{doctorSystemId}/schedule
     * Doctor can only update their own schedule
     */
    @PutMapping("/{doctorSystemId}/schedule")
    public ResponseEntity<?> updateSchedule(@PathVariable String doctorSystemId, @RequestBody Object scheduleData) {
        String currentDoctorSystemId = userContext.getCurrentUserSystemId();

        // Check ownership: doctor can only update their own schedule
        authorizationService.requireOwnership(doctorSystemId, "SCHEDULE", null);

        return ResponseEntity.ok().body("Schedule updated for doctor " + doctorSystemId);
    }
}
