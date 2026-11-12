package com.carenexus.api.health.controller;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.idempotency.IdempotencyService;
import com.carenexus.api.common.idempotency.IdempotentResult;
import com.carenexus.api.health.dto.request.CreateSymptomLogRequest;
import com.carenexus.api.health.dto.response.SymptomLogResponse;
import com.carenexus.api.health.service.SymptomLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Symptom logs. Every route needs a bearer token; access is decided in {@link SymptomLogService} with the health
 * reading permissions (patient self, related doctor, caregiver with the matching grant; administrators read only).
 *
 * - POST   /api/symptom-logs                             log symptoms
 * - GET    /api/symptom-logs/patient/{patientSystemId}   paged history
 * - GET    /api/symptom-logs/{symptomId}                 one log
 * - DELETE /api/symptom-logs/{symptomId}                 soft delete by the author within 24 hours
 */
@RestController
@RequestMapping("/api/symptom-logs")
@RequiredArgsConstructor
@Tag(name = "Symptom logs", description = "Symptoms reported by patients, their caregivers or their doctors")
public class SymptomLogController {

    static final String SCOPE_CREATE = "POST /api/symptom-logs";

    private final SymptomLogService symptomLogService;
    private final IdempotencyService idempotencyService;

    @PostMapping
    @Operation(summary = "Log symptoms",
            description = "PATIENT (self, stored as PATIENT_REPORTED), a DOCTOR related to the patient (DOCTOR_ENTERED) "
                    + "or a CAREGIVER whose grant allows recording health readings (PATIENT_REPORTED, author = the "
                    + "caregiver); ADMIN is refused. Publishes SymptomLoggedEvent; SEVERE and CRITICAL logs notify "
                    + "the patient's related doctors (except the author). Audited (CREATE / SYMPTOM_LOG). Optional "
                    + "Idempotency-Key header: a repeat answers 200 with the stored body.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The stored log"),
            @ApiResponse(responseCode = "200", description = "Replay of an earlier request with the same Idempotency-Key"),
            @ApiResponse(responseCode = "400", description = "Validation failed (code VALIDATION_FAILED), malformed body, "
                    + "or malformed Idempotency-Key",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller may not log symptoms for this patient",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "The same Idempotency-Key is still being processed "
                    + "(code IDEMPOTENCY_IN_PROGRESS)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<SymptomLogResponse> create(
            @Parameter(description = "Optional, 8 to 64 characters of [A-Za-z0-9_-]; one key per form submission")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateSymptomLogRequest request) {
        IdempotentResult<SymptomLogResponse> result = idempotencyService.execute(SCOPE_CREATE, idempotencyKey,
                SymptomLogResponse.class, () -> symptomLogService.create(request));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.body());
    }

    @GetMapping("/patient/{patientSystemId}")
    @Operation(summary = "List a patient's symptom logs",
            description = "Read access to the patient (patient self, related doctor, caregiver with can_view_records "
                    + "or FULL_ACCESS, ADMIN). Deleted logs are never returned. Newest first (loggedAt, then "
                    + "symptomId, descending). Filters: severity, from / to (clinic days, inclusive). Audited "
                    + "(READ / SYMPTOM_LOG).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page of symptom logs"),
            @ApiResponse(responseCode = "400", description = "Unknown severity, from after to, or a malformed parameter",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller may not view this patient's health readings",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PageResponse<SymptomLogResponse>> history(
            @Parameter(description = "Patient system id", example = "PAT-00001") @PathVariable String patientSystemId,
            @Parameter(description = "MILD, MODERATE, SEVERE or CRITICAL, case-insensitive")
            @RequestParam(required = false) String severity,
            @Parameter(description = "First clinic day (YYYY-MM-DD), inclusive")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Last clinic day (YYYY-MM-DD), inclusive")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Zero-based page; negative values become 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(symptomLogService.history(patientSystemId, severity, from, to, page, size));
    }

    @GetMapping("/{symptomId}")
    @Operation(summary = "Get one symptom log",
            description = "Read access to the log's patient (checked after the log was found). Deleted logs answer "
                    + "404. Audited (READ / SYMPTOM_LOG).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The symptom log"),
            @ApiResponse(responseCode = "400", description = "symptomId is not an integer",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller may not view this patient's health readings",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such log, or it was deleted",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<SymptomLogResponse> get(
            @Parameter(description = "Symptom log id", example = "57") @PathVariable Integer symptomId) {
        return ResponseEntity.ok(symptomLogService.get(symptomId));
    }

    @DeleteMapping("/{symptomId}")
    @Operation(summary = "Delete a symptom log",
            description = "Only the author, within 24 hours of loggedAt; a doctor or caregiver author must still hold "
                    + "write access to the patient. Soft delete: the log disappears from every read. A notification "
                    + "already sent is not withdrawn. Audited (DELETE / SYMPTOM_LOG). Checks run in the order 404, "
                    + "403, 409.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deleted"),
            @ApiResponse(responseCode = "400", description = "symptomId is not an integer",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Not the author, or the author lost write access",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such log, or it was already deleted",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "24 hours or more have passed since the log was written",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> delete(
            @Parameter(description = "Symptom log id", example = "57") @PathVariable Integer symptomId) {
        symptomLogService.delete(symptomId);
        return ResponseEntity.noContent().build();
    }
}
