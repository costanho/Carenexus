package com.carenexus.api.corrections.controller;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.dto.StandardErrors;
import com.carenexus.api.common.dto.ValidationPatterns;
import com.carenexus.api.common.idempotency.IdempotencyService;
import com.carenexus.api.common.idempotency.IdempotentResult;
import com.carenexus.api.corrections.dto.request.CreateCorrectionRequest;
import com.carenexus.api.corrections.dto.request.ResolveCorrectionRequest;
import com.carenexus.api.corrections.dto.response.CorrectionRequestResponse;
import com.carenexus.api.corrections.service.CorrectionRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Correction requests for clinical records (patients, caregivers, doctors, administrators).
 *
 * - POST /api/correction-requests                               file a request (PATIENT, CAREGIVER with MANAGE_CARE_PLAN)
 * - GET  /api/correction-requests/patient/{patientSystemId}     requests about one patient (RECORDS access)
 * - GET  /api/correction-requests/assigned                      the calling doctor's review queue
 * - GET  /api/correction-requests/{id}                          one request (assignee or RECORDS access)
 * - POST /api/correction-requests/{id}/resolve                  accept or reject (assignee or ADMIN)
 *
 * Administrator routes live in {@link CorrectionAdminController}.
 */
@RestController
@RequestMapping("/api/correction-requests")
@RequiredArgsConstructor
@Validated
@Tag(name = "Correction requests", description = "Patient requests to correct a clinical record, and their resolution")
public class CorrectionRequestController {

    static final String CREATE_SCOPE = "POST /api/correction-requests";

    private final CorrectionRequestService correctionRequestService;
    private final IdempotencyService idempotencyService;

    @PostMapping
    @Operation(summary = "Request a correction of a clinical record",
            description = "PATIENT for their own record, or CAREGIVER whose grant allows managing the care plan "
                    + "(patientSystemId required). The record must belong to the patient. The authoring doctor is "
                    + "assigned and notified; when that doctor is not an active account the request is unassigned "
                    + "and every active administrator is notified. One OPEN request per record. Accepts an "
                    + "Idempotency-Key header: a repeat returns the stored body with 200. Rate limited (bucket "
                    + "correction-request). Audited as CREATE CORRECTION_REQUEST.")
    @StandardErrors
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The request was filed"),
            @ApiResponse(responseCode = "200", description = "Idempotent replay: the body of the first call"),
            @ApiResponse(responseCode = "429", description = "Too many correction requests (Retry-After header, code RATE_LIMITED)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<CorrectionRequestResponse> create(
            @Parameter(description = "Optional key (8 to 64 characters of [A-Za-z0-9_-]) that makes a retry safe")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateCorrectionRequest request) {
        IdempotentResult<CorrectionRequestResponse> result = idempotencyService.execute(
                CREATE_SCOPE, idempotencyKey, CorrectionRequestResponse.class,
                () -> correctionRequestService.create(request));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.body());
    }

    @GetMapping("/patient/{patientSystemId}")
    @Operation(summary = "List the correction requests about one patient",
            description = "The patient, a caregiver whose grant allows viewing medical records, a related doctor, or "
                    + "an administrator. All requests, whoever filed them and whoever is assigned; newest first. "
                    + "Optional status filter (OPEN, ACCEPTED, REJECTED; case-insensitive). Audited as a list read.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "One page of requests")
    public ResponseEntity<PageResponse<CorrectionRequestResponse>> listForPatient(
            @Parameter(description = "Patient system id", example = "PAT-00001")
            @PathVariable @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = "must be a valid system id")
            String patientSystemId,
            @Parameter(description = "OPEN, ACCEPTED or REJECTED; blank = every status")
            @RequestParam(required = false) String status,
            @Parameter(description = "Zero-based page; negative becomes 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(correctionRequestService.listForPatient(patientSystemId, status, page, size));
    }

    @GetMapping("/assigned")
    @Operation(summary = "List the correction requests assigned to me",
            description = "DOCTOR only: requests whose assigned doctor is the caller, newest first. Optional status "
                    + "filter (the web queue asks for OPEN; totalElements is the open-queue count). Audited as a "
                    + "list read.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "One page of requests")
    public ResponseEntity<PageResponse<CorrectionRequestResponse>> listAssigned(
            @Parameter(description = "OPEN, ACCEPTED or REJECTED; blank = every status")
            @RequestParam(required = false) String status,
            @Parameter(description = "Zero-based page; negative becomes 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(correctionRequestService.listAssigned(status, page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one correction request",
            description = "The assigned doctor, or anyone allowed to view the patient's medical records (the patient, "
                    + "a caregiver with that permission, a related doctor, an administrator). Audited as READ "
                    + "CORRECTION_REQUEST.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The request")
    public ResponseEntity<CorrectionRequestResponse> get(
            @Parameter(description = "Correction request id", example = "41") @PathVariable Integer id) {
        return ResponseEntity.ok(correctionRequestService.get(id));
    }

    @PostMapping("/{id}/resolve")
    @Operation(summary = "Accept or reject a correction request",
            description = "The assigned doctor or an administrator. Records the decision and the note (required for "
                    + "both decisions) and notifies the requester; it changes no clinical row (the record is "
                    + "corrected through its own authoring endpoints). A request that is no longer OPEN answers 409. "
                    + "Audited as UPDATE CORRECTION_REQUEST.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The request with its decision")
    public ResponseEntity<CorrectionRequestResponse> resolve(
            @Parameter(description = "Correction request id", example = "41") @PathVariable Integer id,
            @Valid @RequestBody ResolveCorrectionRequest request) {
        return ResponseEntity.ok(correctionRequestService.resolve(id, request));
    }
}
