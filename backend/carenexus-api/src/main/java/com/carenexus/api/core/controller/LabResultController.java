package com.carenexus.api.core.controller;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.dto.StandardErrors;
import com.carenexus.api.common.idempotency.IdempotencyService;
import com.carenexus.api.common.idempotency.IdempotentResult;
import com.carenexus.api.core.dto.request.CreateLabResultRequest;
import com.carenexus.api.core.dto.request.ReviewResultRequest;
import com.carenexus.api.core.dto.request.UpdateLabResultRequest;
import com.carenexus.api.core.dto.response.LabResultResponse;
import com.carenexus.api.core.service.LabResultService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Lab results. Thin: every access rule, transition rule, audit row and event lives in {@link LabResultService}.
 *
 * - POST   /api/lab-results                          record (treating doctor; Idempotency-Key)
 * - GET    /api/lab-results                          canonical paged list with filters
 * - GET    /api/lab-results/me/pending-review        the calling doctor's review queue (paged)
 * - GET    /api/lab-results/{id}                     one result
 * - PUT    /api/lab-results/{id}                     partial update (author doctor)
 * - PATCH  /api/lab-results/{id}/status?status=      status change (doctor of the patient)
 * - POST   /api/lab-results/{id}/review              sign-off (doctor of the patient)
 * - DELETE /api/lab-results/{id}                     cancel (author doctor or admin)
 * - GET    /api/lab-results/patient/{id}, /doctor/{id}  deprecated arrays (capped at 200)
 */
@RestController
@RequestMapping("/api/lab-results")
@RequiredArgsConstructor
@Validated
@Tag(name = "Lab results", description = "Laboratory results: recording, review workflow and lists")
public class LabResultController {

    static final String SCOPE_CREATE = "POST /api/lab-results";
    static final String WRITABLE_STATUS = "(?i)^(PENDING|REQUIRES_REVIEW|COMPLETED|CRITICAL)$";
    static final String STATUS_FILTER = "(?i)^(PENDING|REQUIRES_REVIEW|COMPLETED|CRITICAL|CANCELLED)?$";

    private final LabResultService labResultService;
    private final IdempotencyService idempotencyService;

    @PostMapping
    @Operation(summary = "Record a lab result",
            description = "DOCTOR with a care relationship to the patient (treating doctor); the author is always the "
                    + "caller. Status defaults to PENDING and is normalised with isCritical (COMPLETED + true becomes "
                    + "CRITICAL). Accepts an Idempotency-Key header: a replay returns 200 with the first response. "
                    + "Writes a CREATE LAB_RESULT audit row and publishes ClinicalResultRecordedEvent.")
    @StandardErrors
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The recorded result"),
            @ApiResponse(responseCode = "200", description = "Replay of an earlier request with the same Idempotency-Key")
    })
    public ResponseEntity<LabResultResponse> createLabResult(
            @Parameter(description = "Optional duplicate-request key: 8 to 64 characters of [A-Za-z0-9_-]")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateLabResultRequest request) {
        IdempotentResult<LabResultResponse> result = idempotencyService.execute(SCOPE_CREATE, idempotencyKey,
                LabResultResponse.class, () -> labResultService.createLabResult(request));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.body());
    }

    @GetMapping
    @Operation(summary = "List lab results (paged, newest first)",
            description = "PATIENT: own results (patientSystemId defaults to the caller). CAREGIVER: patientSystemId "
                    + "required and an active grant with lab results. DOCTOR: with patientSystemId the results of a "
                    + "patient they are related to, otherwise their own authored results. ADMIN: any filter. CANCELLED "
                    + "rows only with status=CANCELLED. Writes one READ LAB_RESULT audit row per patient.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "One page of results")
    public ResponseEntity<PageResponse<LabResultResponse>> listLabResults(
            @RequestParam(required = false) String patientSystemId,
            @RequestParam(required = false) String doctorSystemId,
            @Parameter(description = "PENDING, REQUIRES_REVIEW, COMPLETED, CRITICAL or CANCELLED")
            @RequestParam(required = false)
            @Pattern(regexp = STATUS_FILTER, message = "must be one of PENDING, REQUIRES_REVIEW, COMPLETED, CRITICAL, CANCELLED")
            String status,
            @Parameter(description = "Test type: BLOOD, URINE, STOOL, CULTURE, BIOPSY, GENETIC, SWAB or OTHER")
            @RequestParam(required = false)
            @Pattern(regexp = "(?i)^(BLOOD|URINE|STOOL|CULTURE|BIOPSY|GENETIC|SWAB|OTHER)?$",
                    message = "must be one of BLOOD, URINE, STOOL, CULTURE, BIOPSY, GENETIC, SWAB, OTHER")
            String type,
            @Parameter(description = "true: only flagged results; false: only unflagged results")
            @RequestParam(required = false) Boolean critical,
            @RequestParam(required = false) Integer consultationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(labResultService.listLabResults(patientSystemId, doctorSystemId, status, type,
                critical, consultationId, page, size));
    }

    @GetMapping("/me/pending-review")
    @Operation(summary = "List the calling doctor's lab results awaiting review",
            description = "DOCTOR only. Unreviewed REQUIRES_REVIEW or CRITICAL results the caller authored or of "
                    + "patients on the caller's active care team, newest first; totalElements is the queue size. "
                    + "Writes one READ LAB_RESULT audit row per patient in the page.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "One page of the queue")
    public ResponseEntity<PageResponse<LabResultResponse>> getMyPendingReview(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(labResultService.getMyPendingReview(page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one lab result",
            description = "The patient, a related doctor, a caregiver whose active grant allows lab results, or an "
                    + "administrator. A cancelled result is returned too. Writes a READ LAB_RESULT audit row.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The result")
    public ResponseEntity<LabResultResponse> getLabResultById(@PathVariable Integer id) {
        return ResponseEntity.ok(labResultService.getLabResultById(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Edit a lab result",
            description = "The author doctor only. Partial update: absent fields are unchanged, blank optional text "
                    + "clears the field. Cancelled results cannot change and a recorded result cannot go back to "
                    + "PENDING (409). Writes an UPDATE LAB_RESULT audit row and publishes ClinicalResultRecordedEvent.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The updated result")
    public ResponseEntity<LabResultResponse> updateLabResult(@PathVariable Integer id,
                                                             @Valid @RequestBody UpdateLabResultRequest request) {
        return ResponseEntity.ok(labResultService.updateLabResult(id, request));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Change the status of a lab result",
            description = "A DOCTOR with a care relationship to the patient. The current status is a no-op. The "
                    + "returned status can differ from the requested one (a flagged result set to COMPLETED becomes "
                    + "CRITICAL). Writes an UPDATE LAB_RESULT audit row and publishes ClinicalResultRecordedEvent.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The result after the change")
    public ResponseEntity<LabResultResponse> updateLabResultStatus(
            @PathVariable Integer id,
            @Parameter(description = "PENDING, REQUIRES_REVIEW, COMPLETED or CRITICAL")
            @RequestParam("status")
            @Pattern(regexp = WRITABLE_STATUS, message = "must be one of PENDING, REQUIRES_REVIEW, COMPLETED, CRITICAL")
            String status) {
        return ResponseEntity.ok(labResultService.updateLabResultStatus(id, status));
    }

    @PostMapping("/{id}/review")
    @Operation(summary = "Review (sign off) a lab result",
            description = "A DOCTOR with a care relationship to the patient; the reviewer is always the caller. Sets "
                    + "status COMPLETED or CRITICAL (the flag follows), stamps reviewer and time and stores the notes, "
                    + "also when already reviewed. Cancelled results cannot be reviewed (409). Writes an UPDATE "
                    + "LAB_RESULT audit row and publishes ClinicalResultRecordedEvent.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The reviewed result")
    public ResponseEntity<LabResultResponse> reviewLabResult(@PathVariable Integer id,
                                                             @Valid @RequestBody ReviewResultRequest request) {
        return ResponseEntity.ok(labResultService.reviewLabResult(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Cancel a lab result",
            description = "The author doctor or an administrator. No row is deleted: the status becomes CANCELLED and "
                    + "the result leaves every list; repeating is a no-op. Writes a DELETE LAB_RESULT audit row and "
                    + "publishes ClinicalResultRecordedEvent.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Cancelled"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Not the author doctor or an administrator",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such result",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> deleteLabResult(@PathVariable Integer id) {
        labResultService.deleteLabResult(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * @deprecated use {@code GET /api/lab-results?patientSystemId=}; kept while web and e2e code calls it.
     */
    @Deprecated
    @GetMapping("/patient/{patientSystemId}")
    @Operation(summary = "List a patient's lab results (deprecated array)", deprecated = true,
            description = "Replaced by GET /api/lab-results?patientSystemId=. Same access as reading one result. "
                    + "Newest first, at most 200 rows, without cancelled results. Writes a READ LAB_RESULT audit row.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The results")
    public ResponseEntity<List<LabResultResponse>> getPatientLabResults(@PathVariable String patientSystemId) {
        return ResponseEntity.ok(labResultService.getPatientLabResults(patientSystemId));
    }

    /**
     * @deprecated use {@code GET /api/lab-results} (doctor) or {@code ?doctorSystemId=} (admin); kept while web and
     * e2e code calls it.
     */
    @Deprecated
    @GetMapping("/doctor/{doctorSystemId}")
    @Operation(summary = "List a doctor's authored lab results (deprecated array)", deprecated = true,
            description = "Replaced by GET /api/lab-results. The doctor themselves or an administrator. Newest first, "
                    + "at most 200 rows, without cancelled results. Writes one READ LAB_RESULT audit row per patient.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The results")
    public ResponseEntity<List<LabResultResponse>> getDoctorLabResults(@PathVariable String doctorSystemId) {
        return ResponseEntity.ok(labResultService.getDoctorLabResults(doctorSystemId));
    }
}
