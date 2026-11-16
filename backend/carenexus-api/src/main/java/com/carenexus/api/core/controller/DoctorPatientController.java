package com.carenexus.api.core.controller;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.StandardErrors;
import com.carenexus.api.core.dto.request.UpdateHealthStatusRequest;
import com.carenexus.api.core.dto.response.DoctorPatientHealthStatusResponse;
import com.carenexus.api.core.dto.response.DoctorPatientOptionResponse;
import com.carenexus.api.core.dto.response.DoctorPatientPageResponse;
import com.carenexus.api.core.dto.response.DoctorPatientResponse;
import com.carenexus.api.core.service.DoctorPatientService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The calling doctor's patients. Authorization lives in {@link DoctorPatientService}: the lists and the detail are
 * DOCTOR only (an administrator has no patient list); the health-status change also accepts an ADMIN. PATIENT and
 * CAREGIVER callers are stopped by the /api/doctors/** route rule before any controller runs.
 */
@RestController
@RequestMapping("/api/doctors/patients")
@RequiredArgsConstructor
@Validated
@Tag(name = "Doctor patients", description = "The calling doctor's patients: care-team members and patients with a "
        + "non-cancelled appointment or a consultation with the doctor")
public class DoctorPatientController {

    private final DoctorPatientService doctorPatientService;

    /**
     * @deprecated use GET /api/doctors/patients/search (screens) or /options (pickers and name maps).
     */
    @Deprecated
    @GetMapping
    @Operation(summary = "List my patients as an array (deprecated)", deprecated = true,
            description = "DOCTOR only. A bare array ordered by last name, first name and patient id, at most 200 rows, "
                    + "with the five most recent appointments of each patient. Audited: one READ / PROFILE row per "
                    + "patient returned. New code uses /search for screens and /options for pickers.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The doctor's patients (JSON array)")
    public ResponseEntity<List<DoctorPatientResponse>> getMyPatients() {
        return ResponseEntity.ok(doctorPatientService.getMyPatients());
    }

    @GetMapping("/search")
    @Operation(summary = "Search my patients (paged)",
            description = "DOCTOR only. Filters (AND) and ordering run in the database; every order ends with last "
                    + "name, first name and patient id. summary counts the doctor's whole patient set and ignores "
                    + "filters and paging. Audited: one READ / PROFILE row per patient of the page.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "One page of the doctor's patients with the summary counts")
    public ResponseEntity<DoctorPatientPageResponse> searchMyPatients(
            @Parameter(description = "Case-insensitive 'contains' on first name, last name, full name, email and "
                    + "patient id; plain 'contains' on phone. % and _ match literally")
            @RequestParam(required = false)
            @Size(max = 100, message = "Search text must be at most 100 characters") String query,
            @Parameter(description = "STABLE, MONITOR or CRITICAL (case-insensitive); blank or ALL = no filter")
            @RequestParam(required = false) String healthStatus,
            @Parameter(description = "MALE, FEMALE or OTHER (case-insensitive); blank or ALL = no filter")
            @RequestParam(required = false) String gender,
            @Parameter(description = "name (default), nextAppointment, healthStatus or lastVisit")
            @RequestParam(required = false) String sort,
            @Parameter(description = "Zero-based page; a negative value becomes 0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100")
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(doctorPatientService.searchMyPatients(query, healthStatus, gender, sort, page, size));
    }

    @GetMapping("/options")
    @Operation(summary = "List my patients' ids and names (pickers)",
            description = "DOCTOR only. A bare array of id and name ordered by last name, first name and patient id, "
                    + "at most 500 rows; narrow with query. Not audited (no clinical field).")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "Id and name of the doctor's patients (JSON array)")
    public ResponseEntity<List<DoctorPatientOptionResponse>> getMyPatientOptions(
            @Parameter(description = "Case-insensitive 'contains' on first name, last name, full name and patient id")
            @RequestParam(required = false)
            @Size(max = 100, message = "Search text must be at most 100 characters") String query) {
        return ResponseEntity.ok(doctorPatientService.getMyPatientOptions(query));
    }

    @GetMapping("/{patientSystemId}/detail")
    @Operation(summary = "Get one of my patients",
            description = "DOCTOR related to the patient (care team, non-cancelled appointment or consultation). "
                    + "Includes the five most recent appointments with the doctor and the latest health-status note. "
                    + "Any other doctor, and any unknown patient id, gets 403. Audited: READ / PROFILE.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The patient detail")
    public ResponseEntity<DoctorPatientResponse> getMyPatientDetail(
            @Parameter(description = "Patient system id", example = "PAT-00001") @PathVariable String patientSystemId) {
        return ResponseEntity.ok(doctorPatientService.getMyPatientDetail(patientSystemId));
    }

    @PatchMapping("/{patientSystemId}/health-status")
    @Operation(summary = "Set a patient's health status",
            description = "A DOCTOR related to the patient, or an ADMIN. A change of status stores the given note (or "
                    + "none) and is audited UPDATE / PROFILE; a change into CRITICAL notifies the patient and their "
                    + "caregivers holding the records permission, without clinical detail. The same status with a note "
                    + "replaces the note; the same status without a note changes nothing.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The health status after the call"),
            @ApiResponse(responseCode = "400", description = "Missing or unknown health status, note too long, malformed body",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Not a doctor related to the patient, and not an administrator",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Administrator and no patient with that id",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<DoctorPatientHealthStatusResponse> updateHealthStatus(
            @Parameter(description = "Patient system id", example = "PAT-00001") @PathVariable String patientSystemId,
            @Valid @RequestBody UpdateHealthStatusRequest request) {
        return ResponseEntity.ok(doctorPatientService.updateHealthStatus(patientSystemId, request));
    }
}
