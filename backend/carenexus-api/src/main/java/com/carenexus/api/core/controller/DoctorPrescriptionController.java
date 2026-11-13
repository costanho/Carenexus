package com.carenexus.api.core.controller;

import com.carenexus.api.common.dto.StandardErrors;
import com.carenexus.api.core.dto.response.PrescriptionResponse;
import com.carenexus.api.core.model.Prescription;
import com.carenexus.api.core.service.PrescriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Doctor prescription actions ({@code /api/doctors/prescriptions}, DOCTOR or ADMIN by route). Authorization lives in
 * {@link PrescriptionService}: status changes require the prescribing doctor.
 *
 * <pre>
 * GET   /api/doctors/prescriptions?status=&patientSystemId=           deprecated array of the caller's prescriptions
 * PATCH /api/doctors/prescriptions/{prescriptionId}/mark-active        ON_HOLD -> ACTIVE (Resume)
 * PATCH /api/doctors/prescriptions/{prescriptionId}/mark-on-hold       ACTIVE -> ON_HOLD (Hold)
 * PATCH /api/doctors/prescriptions/{prescriptionId}/mark-completed     -> COMPLETED
 * PATCH /api/doctors/prescriptions/{prescriptionId}/mark-cancelled     -> CANCELLED
 * PATCH /api/doctors/prescriptions/{prescriptionId}/mark-expired       -> EXPIRED
 * PATCH /api/doctors/prescriptions/{prescriptionId}/status?status=     alias of PATCH /api/prescriptions/{id}/status
 * </pre>
 * Lists for doctors are {@code GET /api/prescriptions?doctorSystemId=<caller>}.
 */
@RestController
@RequestMapping("/api/doctors/prescriptions")
@RequiredArgsConstructor
@Validated
@Tag(name = "Doctor prescriptions", description = "Status actions on prescriptions for the prescribing doctor")
public class DoctorPrescriptionController {

    private static final String STATUS_DESCRIPTION = "Prescribing doctor only (administrators included in the refusal). "
            + "ACTIVE and ON_HOLD interchange; either can become COMPLETED, CANCELLED or EXPIRED, which are terminal "
            + "(409). The current status is a no-op. Side effects on a change: audit UPDATE, "
            + "PrescriptionStatusChangedEvent.";

    private final PrescriptionService prescriptionService;

    /** @deprecated use {@code GET /api/prescriptions?doctorSystemId=<caller>} (paged). */
    @Deprecated
    @GetMapping
    @Operation(summary = "List the caller's prescriptions (deprecated array)", deprecated = true,
            description = "Use GET /api/prescriptions?doctorSystemId=<caller>. Prescriptions the calling doctor wrote, "
                    + "optionally filtered by effective status and patient; at most 200, newest first. An "
                    + "administrator gets an empty array. Side effect: audit READ per patient.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "Prescriptions, possibly empty")
    public ResponseEntity<List<PrescriptionResponse>> getMyPrescriptions(
            @Parameter(description = "Effective status: ACTIVE, ON_HOLD, COMPLETED, CANCELLED or EXPIRED (case-insensitive)")
            @RequestParam(required = false)
            @Pattern(regexp = PrescriptionController.STATUS_OR_BLANK, flags = Pattern.Flag.CASE_INSENSITIVE,
                    message = PrescriptionService.STATUS_MESSAGE) String status,
            @Parameter(description = "Patient filter (PAT-...)")
            @RequestParam(required = false)
            @Pattern(regexp = PrescriptionController.SYSTEM_ID_OR_BLANK, message = "must be a valid system id")
            String patientSystemId) {
        return ResponseEntity.ok(prescriptionService.getCallerDoctorPrescriptions(status, patientSystemId));
    }

    @PatchMapping("/{prescriptionId}/mark-completed")
    @Operation(summary = "Mark a prescription completed", description = STATUS_DESCRIPTION)
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The prescription after the change")
    public ResponseEntity<PrescriptionResponse> markPrescriptionCompleted(@PathVariable Integer prescriptionId) {
        return ResponseEntity.ok(prescriptionService.updatePrescriptionStatus(prescriptionId, Prescription.COMPLETED));
    }

    @PatchMapping("/{prescriptionId}/mark-cancelled")
    @Operation(summary = "Cancel a prescription", description = STATUS_DESCRIPTION)
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The prescription after the change")
    public ResponseEntity<PrescriptionResponse> markPrescriptionCancelled(@PathVariable Integer prescriptionId) {
        return ResponseEntity.ok(prescriptionService.updatePrescriptionStatus(prescriptionId, Prescription.CANCELLED));
    }

    @PatchMapping("/{prescriptionId}/mark-expired")
    @Operation(summary = "Mark a prescription expired", description = STATUS_DESCRIPTION
            + " The hourly expiry job does this automatically after the expiry date.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The prescription after the change")
    public ResponseEntity<PrescriptionResponse> markPrescriptionExpired(@PathVariable Integer prescriptionId) {
        return ResponseEntity.ok(prescriptionService.updatePrescriptionStatus(prescriptionId, Prescription.EXPIRED));
    }

    @PatchMapping("/{prescriptionId}/mark-on-hold")
    @Operation(summary = "Put a prescription on hold", description = STATUS_DESCRIPTION)
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The prescription after the change")
    public ResponseEntity<PrescriptionResponse> markPrescriptionOnHold(@PathVariable Integer prescriptionId) {
        return ResponseEntity.ok(prescriptionService.updatePrescriptionStatus(prescriptionId, Prescription.ON_HOLD));
    }

    @PatchMapping("/{prescriptionId}/mark-active")
    @Operation(summary = "Resume a prescription", description = STATUS_DESCRIPTION)
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The prescription after the change")
    public ResponseEntity<PrescriptionResponse> markPrescriptionActive(@PathVariable Integer prescriptionId) {
        return ResponseEntity.ok(prescriptionService.updatePrescriptionStatus(prescriptionId, Prescription.ACTIVE));
    }

    @PatchMapping("/{prescriptionId}/status")
    @Operation(summary = "Change the status of a prescription", description = STATUS_DESCRIPTION
            + " Same as PATCH /api/prescriptions/{id}/status.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The prescription after the change")
    public ResponseEntity<PrescriptionResponse> updatePrescriptionStatus(
            @PathVariable Integer prescriptionId,
            @Parameter(description = "ACTIVE, ON_HOLD, COMPLETED, CANCELLED or EXPIRED (case-insensitive)", required = true)
            @RequestParam @Pattern(regexp = PrescriptionController.STATUS_REQUIRED, flags = Pattern.Flag.CASE_INSENSITIVE,
                    message = PrescriptionService.STATUS_MESSAGE) String status) {
        return ResponseEntity.ok(prescriptionService.updatePrescriptionStatus(prescriptionId, status));
    }
}
