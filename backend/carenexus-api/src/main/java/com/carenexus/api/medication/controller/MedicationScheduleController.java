package com.carenexus.api.medication.controller;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.ValidationPatterns;
import com.carenexus.api.medication.dto.response.MedicationAdherenceResponse;
import com.carenexus.api.medication.dto.response.MedicationDoseResponse;
import com.carenexus.api.medication.service.MedicationScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Medication doses (one row per medication, clinic day and time of day), created by the server from each
 * prescription's daily times.
 *
 * - GET   /api/medication-schedules/me?date=                                   the calling patient's day
 * - GET   /api/medication-schedules/patient/{patientSystemId}?date=            one patient's day
 * - GET   /api/medication-schedules/patient/{patientSystemId}/adherence?days=  taken / missed / pending counts
 * - PATCH /api/medication-schedules/{id}/taken | /missed | /undo               mark a dose
 */
@RestController
@RequestMapping("/api/medication-schedules")
@RequiredArgsConstructor
@Validated
@Tag(name = "Medication schedules", description = "Daily medication doses of patients and their taken / missed status")
public class MedicationScheduleController {

    private static final String SYSTEM_ID_MESSAGE = "must be a valid system id";
    private static final String DAYS_MESSAGE = "must be between 1 and 90";

    private final MedicationScheduleService scheduleService;

    @GetMapping("/me")
    @Operation(summary = "List my doses of one day",
            description = "PATIENT only. The caller's doses of one clinic day (default: the clinic's today), sorted by "
                    + "time then id; a plain array, hard cap 200. Doses exist up to the clinic's tomorrow; later days "
                    + "are empty. Writes one READ MEDICATION_SCHEDULE audit row.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The day's doses (possibly empty)"),
            @ApiResponse(responseCode = "400", description = "date is not a valid yyyy-MM-dd date",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not a patient",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<List<MedicationDoseResponse>> myDay(
            @Parameter(description = "Clinic calendar day, yyyy-MM-dd; default the clinic's today")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(scheduleService.myDay(date));
    }

    @GetMapping("/patient/{patientSystemId}")
    @Operation(summary = "List a patient's doses of one day",
            description = "The patient, a doctor related to the patient, a caregiver whose grant gives MEDICATIONS, "
                    + "or an admin. Same output as /me. Writes one READ MEDICATION_SCHEDULE audit row.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The day's doses (possibly empty)"),
            @ApiResponse(responseCode = "400", description = "Invalid patientSystemId (code VALIDATION_FAILED) or date",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "No read access to this patient's medications",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<List<MedicationDoseResponse>> patientDay(
            @Parameter(description = "Patient system id, for example PAT-00001")
            @PathVariable @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = SYSTEM_ID_MESSAGE) String patientSystemId,
            @Parameter(description = "Clinic calendar day, yyyy-MM-dd; default the clinic's today")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(scheduleService.patientDay(patientSystemId, date));
    }

    @GetMapping("/patient/{patientSystemId}/adherence")
    @Operation(summary = "Count a patient's taken, missed and pending doses",
            description = "Read access as for the patient's day list (a patient passes their own id). Counts the doses "
                    + "of the last `days` clinic days, today included, across all prescriptions; adherenceRate is "
                    + "round(100 * taken / (taken + missed)) or null. Writes one READ MEDICATION_SCHEDULE audit row.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The counts"),
            @ApiResponse(responseCode = "400", description = "Invalid patientSystemId or days outside 1..90 "
                    + "(code VALIDATION_FAILED), or days not an integer",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "No read access to this patient's medications",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<MedicationAdherenceResponse> adherence(
            @Parameter(description = "Patient system id, for example PAT-00001")
            @PathVariable @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = SYSTEM_ID_MESSAGE) String patientSystemId,
            @Parameter(description = "Window length in clinic days, 1 to 90 (default 30)")
            @RequestParam(defaultValue = "30") @Min(value = 1, message = DAYS_MESSAGE)
            @Max(value = 90, message = DAYS_MESSAGE) int days) {
        return ResponseEntity.ok(scheduleService.adherence(patientSystemId, days));
    }

    @PatchMapping("/{id}/taken")
    @Operation(summary = "Mark a dose taken",
            description = "The patient (own doses) or a caregiver whose grant gives MEDICATIONS and MANAGE_CARE_PLAN; "
                    + "doctors and admins cannot. Only doses of the clinic's today or yesterday. From PENDING or "
                    + "MISSED; takenAt = now, markedBySystemId = caller. Already TAKEN: no-op. A real change writes "
                    + "an UPDATE MEDICATION_SCHEDULE audit row. No request body.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The dose after the change"),
            @ApiResponse(responseCode = "400", description = "id is not an integer",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller may not mark this dose",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No dose with this id",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "The dose is not of today or yesterday",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<MedicationDoseResponse> markTaken(
            @Parameter(description = "Dose id (scheduleId)") @PathVariable Integer id) {
        return ResponseEntity.ok(scheduleService.markTaken(id));
    }

    @PatchMapping("/{id}/missed")
    @Operation(summary = "Mark a dose missed",
            description = "Who and when as for /taken. From PENDING or TAKEN; takenAt = null, markedBySystemId = "
                    + "caller. Already MISSED: no-op. A real change writes an UPDATE MEDICATION_SCHEDULE audit row "
                    + "and publishes MedicationDoseMissedEvent. No request body.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The dose after the change"),
            @ApiResponse(responseCode = "400", description = "id is not an integer",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller may not mark this dose",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No dose with this id",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "The dose is not of today or yesterday",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<MedicationDoseResponse> markMissed(
            @Parameter(description = "Dose id (scheduleId)") @PathVariable Integer id) {
        return ResponseEntity.ok(scheduleService.markMissed(id));
    }

    @PatchMapping("/{id}/undo")
    @Operation(summary = "Put a dose back to pending",
            description = "Who and when as for /taken. From TAKEN or MISSED; takenAt and markedBySystemId become "
                    + "null. Already PENDING: no-op. A real change writes an UPDATE MEDICATION_SCHEDULE audit row. "
                    + "A pending dose more than the grace period overdue is marked MISSED again by the next sweep.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The dose after the change"),
            @ApiResponse(responseCode = "400", description = "id is not an integer",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller may not mark this dose",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No dose with this id",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "The dose is not of today or yesterday",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<MedicationDoseResponse> undo(
            @Parameter(description = "Dose id (scheduleId)") @PathVariable Integer id) {
        return ResponseEntity.ok(scheduleService.undo(id));
    }
}
