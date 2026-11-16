package com.carenexus.api.core.controller;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.dto.StandardErrors;
import com.carenexus.api.common.idempotency.IdempotencyService;
import com.carenexus.api.common.idempotency.IdempotentResult;
import com.carenexus.api.core.dto.request.CreateImagingResultRequest;
import com.carenexus.api.core.dto.request.ReviewResultRequest;
import com.carenexus.api.core.dto.request.UpdateImagingResultRequest;
import com.carenexus.api.core.dto.response.ImagingResultResponse;
import com.carenexus.api.core.service.ImagingResultService;
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
 * Imaging results. Thin: every access rule, transition rule, audit row and event lives in
 * {@link ImagingResultService}.
 *
 * - POST   /api/imaging-results                          record (treating doctor; Idempotency-Key)
 * - GET    /api/imaging-results                          canonical paged list with filters
 * - GET    /api/imaging-results/me/pending-review        the calling doctor's review queue (paged)
 * - GET    /api/imaging-results/{id}                     one result
 * - PUT    /api/imaging-results/{id}                     partial update (author doctor)
 * - PATCH  /api/imaging-results/{id}/status?status=      status change (doctor of the patient)
 * - POST   /api/imaging-results/{id}/review              sign-off (doctor of the patient)
 * - DELETE /api/imaging-results/{id}                     cancel (author doctor or admin)
 * - GET    /api/imaging-results/patient/{id}             deprecated array (capped at 200)
 */
@RestController
@RequestMapping("/api/imaging-results")
@RequiredArgsConstructor
@Validated
@Tag(name = "Imaging results", description = "Imaging results: recording, review workflow and lists")
public class ImagingResultController {

    static final String SCOPE_CREATE = "POST /api/imaging-results";

    private final ImagingResultService imagingResultService;
    private final IdempotencyService idempotencyService;

    @PostMapping
    @Operation(summary = "Record an imaging result",
            description = "DOCTOR with a care relationship to the patient (treating doctor); the author is always the "
                    + "caller. Status defaults to PENDING; a critical result is recorded with status CRITICAL. Accepts "
                    + "an Idempotency-Key header: a replay returns 200 with the first response. Writes a CREATE "
                    + "IMAGING_RESULT audit row and publishes ClinicalResultRecordedEvent.")
    @StandardErrors
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The recorded result"),
            @ApiResponse(responseCode = "200", description = "Replay of an earlier request with the same Idempotency-Key")
    })
    public ResponseEntity<ImagingResultResponse> createImagingResult(
            @Parameter(description = "Optional duplicate-request key: 8 to 64 characters of [A-Za-z0-9_-]")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateImagingResultRequest request) {
        IdempotentResult<ImagingResultResponse> result = idempotencyService.execute(SCOPE_CREATE, idempotencyKey,
                ImagingResultResponse.class, () -> imagingResultService.createImagingResult(request));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.body());
    }

    @GetMapping
    @Operation(summary = "List imaging results (paged, newest first)",
            description = "PATIENT: own results (patientSystemId defaults to the caller). CAREGIVER: patientSystemId "
                    + "required and an active grant with imaging. DOCTOR: with patientSystemId the results of a patient "
                    + "they are related to, otherwise their own authored results. ADMIN: any filter. critical=true "
                    + "returns CRITICAL results, false every other status. CANCELLED rows only with status=CANCELLED. "
                    + "Writes one READ IMAGING_RESULT audit row per patient.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "One page of results")
    public ResponseEntity<PageResponse<ImagingResultResponse>> listImagingResults(
            @RequestParam(required = false) String patientSystemId,
            @RequestParam(required = false) String doctorSystemId,
            @Parameter(description = "PENDING, REQUIRES_REVIEW, COMPLETED, CRITICAL or CANCELLED")
            @RequestParam(required = false)
            @Pattern(regexp = LabResultController.STATUS_FILTER,
                    message = "must be one of PENDING, REQUIRES_REVIEW, COMPLETED, CRITICAL, CANCELLED")
            String status,
            @Parameter(description = "Imaging type: XRAY, MRI, CT_SCAN, ULTRASOUND, PET, MAMMOGRAPHY, DEXA or ECHO")
            @RequestParam(required = false)
            @Pattern(regexp = "(?i)^(XRAY|MRI|CT_SCAN|ULTRASOUND|PET|MAMMOGRAPHY|DEXA|ECHO)?$",
                    message = "must be one of XRAY, MRI, CT_SCAN, ULTRASOUND, PET, MAMMOGRAPHY, DEXA, ECHO")
            String type,
            @Parameter(description = "true: only CRITICAL results; false: every other status")
            @RequestParam(required = false) Boolean critical,
            @RequestParam(required = false) Integer consultationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(imagingResultService.listImagingResults(patientSystemId, doctorSystemId, status, type,
                critical, consultationId, page, size));
    }

    @GetMapping("/me/pending-review")
    @Operation(summary = "List the calling doctor's imaging results awaiting review",
            description = "DOCTOR only. Unreviewed REQUIRES_REVIEW or CRITICAL results the caller authored or of "
                    + "patients on the caller's active care team, newest first; totalElements is the queue size. "
                    + "Writes one READ IMAGING_RESULT audit row per patient in the page.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "One page of the queue")
    public ResponseEntity<PageResponse<ImagingResultResponse>> getMyPendingReview(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(imagingResultService.getMyPendingReview(page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one imaging result",
            description = "The patient, a related doctor, a caregiver whose active grant allows imaging, or an "
                    + "administrator. A cancelled result is returned too. Writes a READ IMAGING_RESULT audit row.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The result")
    public ResponseEntity<ImagingResultResponse> getImagingResultById(@PathVariable Integer id) {
        return ResponseEntity.ok(imagingResultService.getImagingResultById(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Edit an imaging result",
            description = "The author doctor only. Partial update: absent fields are unchanged, blank optional text "
                    + "clears the field. Cancelled results cannot change and a recorded result cannot go back to "
                    + "PENDING (409). Writes an UPDATE IMAGING_RESULT audit row and publishes ClinicalResultRecordedEvent.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The updated result")
    public ResponseEntity<ImagingResultResponse> updateImagingResult(@PathVariable Integer id,
                                                                     @Valid @RequestBody UpdateImagingResultRequest request) {
        return ResponseEntity.ok(imagingResultService.updateImagingResult(id, request));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Change the status of an imaging result",
            description = "A DOCTOR with a care relationship to the patient. The current status is a no-op. Writes an "
                    + "UPDATE IMAGING_RESULT audit row and publishes ClinicalResultRecordedEvent.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The result after the change")
    public ResponseEntity<ImagingResultResponse> updateImagingResultStatus(
            @PathVariable Integer id,
            @Parameter(description = "PENDING, REQUIRES_REVIEW, COMPLETED or CRITICAL")
            @RequestParam("status")
            @Pattern(regexp = LabResultController.WRITABLE_STATUS,
                    message = "must be one of PENDING, REQUIRES_REVIEW, COMPLETED, CRITICAL")
            String status) {
        return ResponseEntity.ok(imagingResultService.updateImagingResultStatus(id, status));
    }

    @PostMapping("/{id}/review")
    @Operation(summary = "Review (sign off) an imaging result",
            description = "A DOCTOR with a care relationship to the patient; the reviewer is always the caller. Sets "
                    + "status COMPLETED or CRITICAL, stamps reviewer and time and stores the notes, also when already "
                    + "reviewed. Cancelled results cannot be reviewed (409). Writes an UPDATE IMAGING_RESULT audit row "
                    + "and publishes ClinicalResultRecordedEvent.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The reviewed result")
    public ResponseEntity<ImagingResultResponse> reviewImagingResult(@PathVariable Integer id,
                                                                     @Valid @RequestBody ReviewResultRequest request) {
        return ResponseEntity.ok(imagingResultService.reviewImagingResult(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Cancel an imaging result",
            description = "The author doctor or an administrator. No row is deleted: the status becomes CANCELLED and "
                    + "the result leaves every list; repeating is a no-op. Writes a DELETE IMAGING_RESULT audit row and "
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
    public ResponseEntity<Void> deleteImagingResult(@PathVariable Integer id) {
        imagingResultService.deleteImagingResult(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * @deprecated use {@code GET /api/imaging-results?patientSystemId=}; kept while web and e2e code calls it.
     */
    @Deprecated
    @GetMapping("/patient/{patientSystemId}")
    @Operation(summary = "List a patient's imaging results (deprecated array)", deprecated = true,
            description = "Replaced by GET /api/imaging-results?patientSystemId=. Same access as reading one result. "
                    + "Newest first, at most 200 rows, without cancelled results. Writes a READ IMAGING_RESULT audit row.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The results")
    public ResponseEntity<List<ImagingResultResponse>> getPatientImagingResults(@PathVariable String patientSystemId) {
        return ResponseEntity.ok(imagingResultService.getPatientImagingResults(patientSystemId));
    }
}
