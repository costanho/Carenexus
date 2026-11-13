package com.carenexus.api.medication.controller;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.medication.dto.request.UpdateScheduleTimesRequest;
import com.carenexus.api.medication.dto.response.MedicationSchedulePlanResponse;
import com.carenexus.api.medication.service.MedicationPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Daily dose times of a prescription (owned by the medication module; the prescriptions module maps nothing under
 * {@code /api/prescriptions/{id}/schedule}).
 *
 * - GET /api/prescriptions/{prescriptionId}/schedule   read the times
 * - PUT /api/prescriptions/{prescriptionId}/schedule   replace the times (author doctor only)
 */
@RestController
@RequestMapping("/api/prescriptions")
@RequiredArgsConstructor
@Tag(name = "Medication schedules", description = "Daily medication doses of patients and their taken / missed status")
public class PrescriptionScheduleController {

    private final MedicationPlanService planService;

    @GetMapping("/{prescriptionId}/schedule")
    @Operation(summary = "Read the daily dose times of a prescription",
            description = "The patient, a doctor related to the patient, a caregiver whose grant gives MEDICATIONS, "
                    + "or an admin. A prescription without stored times answers times = [] with null author and "
                    + "time. Writes a READ MEDICATION_SCHEDULE audit row with the prescription id.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The plan"),
            @ApiResponse(responseCode = "400", description = "prescriptionId is not an integer",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "No read access to this patient's medications",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No prescription with this id",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<MedicationSchedulePlanResponse> getSchedule(
            @Parameter(description = "Prescription id") @PathVariable Integer prescriptionId) {
        return ResponseEntity.ok(planService.getPlan(prescriptionId));
    }

    @PutMapping("/{prescriptionId}/schedule")
    @Operation(summary = "Replace the daily dose times of a prescription",
            description = "DOCTOR who wrote the prescription only. Times are de-duplicated and sorted; [] removes the "
                    + "schedule. PENDING doses after now are deleted and, while the prescription is ACTIVE, today's "
                    + "remaining and tomorrow's doses are created from the new times (ON_HOLD: on resume). Sending "
                    + "the stored times again is a no-op. A real change writes an UPDATE MEDICATION_SCHEDULE audit "
                    + "row with the prescription id.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The plan after the change"),
            @ApiResponse(responseCode = "400", description = "Malformed body, or invalid times (code VALIDATION_FAILED)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Not a doctor, or not the prescription's author",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No prescription with this id",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "The prescription is completed, cancelled or expired",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<MedicationSchedulePlanResponse> updateSchedule(
            @Parameter(description = "Prescription id") @PathVariable Integer prescriptionId,
            @Valid @RequestBody UpdateScheduleTimesRequest request) {
        return ResponseEntity.ok(planService.updatePlan(prescriptionId, request.times()));
    }
}
