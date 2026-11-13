package com.carenexus.api.core.controller;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.dto.StandardErrors;
import com.carenexus.api.common.idempotency.IdempotencyService;
import com.carenexus.api.common.idempotency.IdempotentResult;
import com.carenexus.api.core.dto.request.CreatePrescriptionRequest;
import com.carenexus.api.core.dto.request.UpdatePrescriptionRequest;
import com.carenexus.api.core.dto.response.PrescriptionResponse;
import com.carenexus.api.core.service.PrescriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Prescriptions ({@code /api/prescriptions}). Authorization and audit live in {@link PrescriptionService}.
 *
 * <pre>
 * POST   /api/prescriptions                              issue (DOCTOR with a care relationship; Idempotency-Key)
 * GET    /api/prescriptions?patientSystemId=&doctorSystemId=&status=&consultationId=&q=&page=&size=   paged, newest first
 * GET    /api/prescriptions/{id}                         one prescription
 * PUT    /api/prescriptions/{id}                         edit refills, expiry date, instructions (prescriber)
 * PATCH  /api/prescriptions/{id}/status?status=          status change (prescriber)
 * DELETE /api/prescriptions/{id}                         cancel (prescriber or admin); no row is deleted
 * GET    /api/prescriptions/patient/{patientSystemId}    deprecated array, at most 200
 * GET    /api/prescriptions/doctor/{doctorSystemId}      deprecated array, at most 200
 * </pre>
 * {@code /api/prescriptions/{id}/schedule} belongs to the medication module.
 */
@RestController
@RequestMapping("/api/prescriptions")
@RequiredArgsConstructor
@Validated
@Tag(name = "Prescriptions", description = "Issue, read, edit and change the status of medication prescriptions")
public class PrescriptionController {

    static final String CREATE_SCOPE = "POST /api/prescriptions";

    /** A system id, or blank (treated as absent). */
    public static final String SYSTEM_ID_OR_BLANK = "^\\s*$|^(PAT|DOC|CGV|ADM)-\\d{5,}$";
    /** One of the five statuses (trimmed, case-insensitive), or blank (treated as absent). */
    public static final String STATUS_OR_BLANK = "^\\s*((ACTIVE|ON_HOLD|COMPLETED|CANCELLED|EXPIRED)\\s*)?$";
    /** One of the five statuses (trimmed, case-insensitive). */
    public static final String STATUS_REQUIRED = "^\\s*(ACTIVE|ON_HOLD|COMPLETED|CANCELLED|EXPIRED)\\s*$";

    private final PrescriptionService prescriptionService;
    private final IdempotencyService idempotencyService;

    @PostMapping
    @Operation(summary = "Issue a prescription",
            description = "DOCTOR only: the doctor's account must be active and the doctor must have a care relationship "
                    + "with the patient; consultationId, when given, must belong to the patient. The prescriber is "
                    + "always the caller and a new prescription is always ACTIVE. scheduleTimes (HH:mm, at most 8) are "
                    + "de-duplicated, sorted and passed to the medication schedule; they are not returned. Optional "
                    + "Idempotency-Key header: a repeat returns the stored response with 200 and does nothing again. "
                    + "Side effects: audit CREATE, PrescriptionIssuedEvent.")
    @StandardErrors
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Issued"),
            @ApiResponse(responseCode = "200", description = "Idempotent replay: the response of the first call")
    })
    public ResponseEntity<PrescriptionResponse> createPrescription(
            @Valid @RequestBody CreatePrescriptionRequest request,
            @Parameter(description = "Optional duplicate-request key, 8 to 64 characters of letters, digits, '_' or '-'")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey) {
        IdempotentResult<PrescriptionResponse> result = idempotencyService.execute(CREATE_SCOPE, idempotencyKey,
                PrescriptionResponse.class, () -> prescriptionService.createPrescription(request));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.body());
    }

    @GetMapping
    @Operation(summary = "List prescriptions (paged, newest first)",
            description = "Filters combine with AND; status matches effectiveStatus. PATIENT: own prescriptions "
                    + "(patientSystemId defaults to the caller). CAREGIVER: patientSystemId is required and the grant "
                    + "must allow viewing medications. DOCTOR: doctorSystemId, when given, must be the caller (the "
                    + "caller's own prescriptions); with only patientSystemId a care relationship is required and every "
                    + "prescriber's rows are returned; with neither, the caller's own. ADMIN: unrestricted. Ordered "
                    + "prescribedDate then prescriptionId, descending. Side effect: audit READ per patient on the page.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "One page of prescriptions")
    public ResponseEntity<PageResponse<PrescriptionResponse>> listPrescriptions(
            @Parameter(description = "Patient filter (PAT-...)")
            @RequestParam(required = false)
            @Pattern(regexp = SYSTEM_ID_OR_BLANK, message = "must be a valid system id") String patientSystemId,
            @Parameter(description = "Prescriber filter (DOC-...); a doctor may only pass their own id")
            @RequestParam(required = false)
            @Pattern(regexp = SYSTEM_ID_OR_BLANK, message = "must be a valid system id") String doctorSystemId,
            @Parameter(description = "Effective status: ACTIVE, ON_HOLD, COMPLETED, CANCELLED or EXPIRED (case-insensitive)")
            @RequestParam(required = false)
            @Pattern(regexp = STATUS_OR_BLANK, flags = Pattern.Flag.CASE_INSENSITIVE,
                    message = PrescriptionService.STATUS_MESSAGE) String status,
            @Parameter(description = "Consultation filter; an unknown id gives an empty page")
            @RequestParam(required = false) @Positive(message = "must be a positive number") Integer consultationId,
            @Parameter(description = "Case-insensitive text in the medication or generic name (max 100)")
            @RequestParam(required = false) @Size(max = PrescriptionService.MAX_QUERY_LENGTH,
                    message = "must be at most 100 characters") String q,
            @Parameter(description = "Zero-based page; a negative value becomes 0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100")
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(prescriptionService.listPrescriptions(
                patientSystemId, doctorSystemId, status, consultationId, q, page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one prescription",
            description = "The patient, a doctor with a care relationship, a caregiver whose grant allows viewing "
                    + "medications, or an administrator. Side effect: audit READ.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The prescription")
    public ResponseEntity<PrescriptionResponse> getPrescriptionById(@PathVariable Integer id) {
        return ResponseEntity.ok(prescriptionService.getPrescriptionById(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Edit refills, expiry date and instructions",
            description = "Prescribing doctor only, while the stored status is ACTIVE or ON_HOLD (409 otherwise). The "
                    + "body is the complete editable state: all three fields are applied (null clears the expiry date "
                    + "and the instructions). Any other property is ignored; medication, dosage, frequency, route and "
                    + "quantity never change (cancel and reissue). Side effect: audit UPDATE.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The updated prescription")
    public ResponseEntity<PrescriptionResponse> updatePrescription(
            @PathVariable Integer id,
            @Valid @RequestBody UpdatePrescriptionRequest request) {
        return ResponseEntity.ok(prescriptionService.updatePrescription(id, request));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Change the status of a prescription",
            description = "Prescribing doctor only. ACTIVE and ON_HOLD interchange; either can become COMPLETED, "
                    + "CANCELLED or EXPIRED, which are terminal (409). The current status is a no-op. Side effects on "
                    + "a change: audit UPDATE, PrescriptionStatusChangedEvent.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The prescription after the change")
    public ResponseEntity<PrescriptionResponse> updatePrescriptionStatus(
            @PathVariable Integer id,
            @Parameter(description = "ACTIVE, ON_HOLD, COMPLETED, CANCELLED or EXPIRED (case-insensitive)", required = true)
            @RequestParam @Pattern(regexp = STATUS_REQUIRED, flags = Pattern.Flag.CASE_INSENSITIVE,
                    message = PrescriptionService.STATUS_MESSAGE) String status) {
        return ResponseEntity.ok(prescriptionService.updatePrescriptionStatus(id, status));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Cancel a prescription",
            description = "Prescribing doctor or administrator. Sets the status to CANCELLED; no row is ever deleted. "
                    + "An already cancelled prescription answers 204 and changes nothing; a completed or expired one "
                    + "answers 409. Side effects on a change: audit DELETE, PrescriptionStatusChangedEvent.")
    @StandardErrors
    @ApiResponse(responseCode = "204", description = "Cancelled (or already cancelled)")
    public ResponseEntity<Void> deletePrescription(@PathVariable Integer id) {
        prescriptionService.deletePrescription(id);
        return ResponseEntity.noContent().build();
    }

    /** @deprecated use {@code GET /api/prescriptions?patientSystemId=} (paged). */
    @Deprecated
    @GetMapping("/patient/{patientSystemId}")
    @Operation(summary = "List a patient's prescriptions (deprecated array)", deprecated = true,
            description = "Use GET /api/prescriptions?patientSystemId=. Read access to the patient's medications. At most "
                    + "200 rows, newest first. Side effect: audit READ.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Prescriptions, possibly empty"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller may not read this patient's medications",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<List<PrescriptionResponse>> getPatientPrescriptions(@PathVariable String patientSystemId) {
        return ResponseEntity.ok(prescriptionService.getPatientPrescriptions(patientSystemId));
    }

    /** @deprecated use {@code GET /api/prescriptions?doctorSystemId=} (paged). */
    @Deprecated
    @GetMapping("/doctor/{doctorSystemId}")
    @Operation(summary = "List a doctor's prescriptions (deprecated array)", deprecated = true,
            description = "Use GET /api/prescriptions?doctorSystemId=. The doctor themself or an administrator. At most "
                    + "200 rows, newest first. Side effect: audit READ per patient.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Prescriptions, possibly empty"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is neither this doctor nor an administrator",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<List<PrescriptionResponse>> getDoctorPrescriptions(@PathVariable String doctorSystemId) {
        return ResponseEntity.ok(prescriptionService.getDoctorPrescriptions(doctorSystemId));
    }
}
