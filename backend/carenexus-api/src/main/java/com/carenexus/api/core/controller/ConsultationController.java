package com.carenexus.api.core.controller;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.dto.StandardErrors;
import com.carenexus.api.common.idempotency.IdempotencyService;
import com.carenexus.api.common.idempotency.IdempotentResult;
import com.carenexus.api.core.dto.request.CreateConsultationRequest;
import com.carenexus.api.core.dto.request.UpdateConsultationRequest;
import com.carenexus.api.core.dto.response.ConsultationResponse;
import com.carenexus.api.core.service.ConsultationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Consultations: the canonical routes (create, paged list, read, update, status, delete, per appointment) and the
 * deprecated array lists kept for existing callers (rule 0.8: newest 200, same shape). Every rule is enforced in
 * {@link ConsultationService}; the route itself only requires a signed-in user.
 */
@RestController
@RequestMapping("/api/consultations")
@RequiredArgsConstructor
@Tag(name = "Consultations", description = "Consultation notes documenting an appointment: lifecycle DRAFT, "
        + "IN_PROGRESS, COMPLETED (terminal), CANCELLED (terminal)")
@StandardErrors
public class ConsultationController {

    static final String CREATE_SCOPE = "POST /api/consultations";

    private final ConsultationService consultationService;
    private final IdempotencyService idempotencyService;

    @PostMapping
    @Operation(summary = "Open a consultation for one of the caller's appointments",
            description = "DOCTOR only, for their own appointment that is SCHEDULED, RESCHEDULED or COMPLETED and has "
                    + "started (clinic time). Status DRAFT (default) or IN_PROGRESS. One non-cancelled consultation "
                    + "per appointment (409 otherwise). Accepts an Idempotency-Key: a replay answers 200 with the "
                    + "stored body. Audited as CREATE CONSULTATION.",
            parameters = @Parameter(name = IdempotencyService.HEADER, in = ParameterIn.HEADER, required = false,
                    description = "8 to 64 characters of [A-Za-z0-9_-]; one key per editor open",
                    schema = @Schema(type = "string")))
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The consultation was created"),
            @ApiResponse(responseCode = "200", description = "Replay of an earlier call with the same Idempotency-Key")
    })
    public ResponseEntity<ConsultationResponse> createConsultation(
            @Valid @RequestBody CreateConsultationRequest request,
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey) {
        IdempotentResult<ConsultationResponse> result = idempotencyService.execute(CREATE_SCOPE, idempotencyKey,
                ConsultationResponse.class, () -> consultationService.createConsultation(request));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.body());
    }

    @GetMapping
    @Operation(summary = "List consultations, paged and filtered",
            description = "PATIENT: own consultations (COMPLETED and CANCELLED only). CAREGIVER: patientSystemId "
                    + "required, proxy access with the consultations permission (COMPLETED and CANCELLED only). "
                    + "DOCTOR: own consultations by default; with patientSystemId and no doctorSystemId = caller, "
                    + "that patient's consultations by any doctor (care relationship required). ADMIN: everything. "
                    + "Sorted newest first (createdAt, then id). Size is clamped to 1..100. Audited per patient.")
    @ApiResponse(responseCode = "200", description = "One page of consultation rows")
    public ResponseEntity<PageResponse<ConsultationResponse>> listConsultations(
            @Parameter(description = "Filter by patient; defaults to the caller for a patient")
            @RequestParam(required = false) String patientSystemId,
            @Parameter(description = "Filter by authoring doctor; defaults to the caller for a doctor without patientSystemId")
            @RequestParam(required = false) String doctorSystemId,
            @Parameter(description = "One of DRAFT, IN_PROGRESS, COMPLETED, CANCELLED (case-insensitive)")
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(consultationService.listConsultations(patientSystemId, doctorSystemId, status, page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one consultation",
            description = "The patient, a doctor with a care relationship, a caregiver with the consultations "
                    + "permission, or an admin. An unfinished (DRAFT, IN_PROGRESS) consultation answers 404 to "
                    + "patients and caregivers. Audited as READ CONSULTATION.")
    @ApiResponse(responseCode = "200", description = "The consultation")
    public ResponseEntity<ConsultationResponse> getConsultationById(@PathVariable Integer id) {
        return ResponseEntity.ok(consultationService.getConsultationById(id));
    }

    @GetMapping("/appointment/{appointmentId}")
    @Operation(summary = "List the consultations of one appointment",
            description = "Same access as the appointment's patient data. At most one non-cancelled consultation "
                    + "plus earlier cancelled ones, newest first, capped at 50. Patients and caregivers do not "
                    + "receive DRAFT or IN_PROGRESS rows. Audited as READ CONSULTATION.")
    @ApiResponse(responseCode = "200", description = "The consultation list (JSON array)")
    public ResponseEntity<List<ConsultationResponse>> getAppointmentConsultations(@PathVariable Integer appointmentId) {
        return ResponseEntity.ok(consultationService.getConsultationsByAppointmentId(appointmentId));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Save consultation notes (partial update)",
            description = "Authoring doctor only. Absent or null fields are unchanged, a blank text clears the field, "
                    + "followUpRequired=false clears followUpDate. An optional status is applied after the content "
                    + "with the transition rules of PATCH .../status. Refused with 409 once COMPLETED or CANCELLED. "
                    + "Completing publishes the consultation-completed event. Audited as UPDATE CONSULTATION.")
    @ApiResponse(responseCode = "200", description = "The updated consultation")
    public ResponseEntity<ConsultationResponse> updateConsultation(
            @PathVariable Integer id,
            @Valid @RequestBody UpdateConsultationRequest request) {
        return ResponseEntity.ok(consultationService.updateConsultation(id, request));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Change a consultation's status",
            description = "Authoring doctor only. DRAFT to IN_PROGRESS, COMPLETED or CANCELLED; IN_PROGRESS to "
                    + "COMPLETED or CANCELLED; COMPLETED and CANCELLED are terminal (409). The current status is a "
                    + "no-op. Completing requires chiefComplaint and diagnosis, sets completedAt once and publishes "
                    + "the consultation-completed event (appointment, medical record and invoice listeners run in "
                    + "the same transaction). Audited as UPDATE CONSULTATION on a real transition.")
    @ApiResponse(responseCode = "200", description = "The updated consultation")
    public ResponseEntity<ConsultationResponse> updateConsultationStatus(
            @PathVariable Integer id,
            @Parameter(description = "DRAFT, IN_PROGRESS, COMPLETED or CANCELLED (case-insensitive)")
            @RequestParam String status) {
        return ResponseEntity.ok(consultationService.updateConsultationStatus(id, status));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a draft consultation",
            description = "Authoring doctor or admin. Only a DRAFT can be deleted (the row is removed; rows linked "
                    + "with ON DELETE SET NULL are unlinked). Audited as DELETE CONSULTATION.")
    @ApiResponses(@ApiResponse(responseCode = "204", description = "The draft was deleted"))
    public ResponseEntity<Void> deleteConsultation(@PathVariable Integer id) {
        consultationService.deleteConsultation(id);
        return ResponseEntity.noContent().build();
    }

    // ==================== DEPRECATED ARRAY LISTS (newest 200) ====================

    /** @deprecated use {@code GET /api/consultations?patientSystemId=} */
    @Deprecated
    @GetMapping("/patient/{patientSystemId}")
    @Operation(deprecated = true, summary = "List a patient's consultations (array, newest 200)",
            description = "Deprecated: use GET /api/consultations?patientSystemId=. Same access as the patient's "
                    + "data; patients and caregivers get COMPLETED and CANCELLED rows only.")
    @ApiResponse(responseCode = "200", description = "The consultation list (JSON array)")
    public ResponseEntity<List<ConsultationResponse>> getPatientConsultations(@PathVariable String patientSystemId) {
        return ResponseEntity.ok(consultationService.getConsultationsByPatientSystemId(patientSystemId));
    }

    /** @deprecated use {@code GET /api/consultations?doctorSystemId=} */
    @Deprecated
    @GetMapping("/doctor/{doctorSystemId}")
    @Operation(deprecated = true, summary = "List a doctor's consultations (array, newest 200)",
            description = "Deprecated: use GET /api/consultations?doctorSystemId=. That doctor or an admin.")
    @ApiResponse(responseCode = "200", description = "The consultation list (JSON array)")
    public ResponseEntity<List<ConsultationResponse>> getDoctorConsultations(@PathVariable String doctorSystemId) {
        return ResponseEntity.ok(consultationService.getConsultationsByDoctorSystemId(doctorSystemId));
    }

    /** @deprecated use {@code GET /api/consultations?status=COMPLETED} */
    @Deprecated
    @GetMapping("/me/completed")
    @Operation(deprecated = true, summary = "List the caller's completed consultations (array, newest 200)",
            description = "Deprecated: use GET /api/consultations?status=COMPLETED. PATIENT or DOCTOR.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The consultation list (JSON array)"),
            @ApiResponse(responseCode = "400", description = "Unsupported user role (caregiver, admin)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))})
    public ResponseEntity<List<ConsultationResponse>> getMyCompletedConsultations(
            @RequestAttribute("systemId") String systemId,
            @RequestAttribute("userRole") String userRole) {
        return ResponseEntity.ok(consultationService.getCompletedForUser(systemId, userRole));
    }

    /** @deprecated use {@code GET /api/consultations?status=IN_PROGRESS} */
    @Deprecated
    @GetMapping("/me/in-progress")
    @Operation(deprecated = true, summary = "List the caller's consultations in progress (array, newest 200)",
            description = "Deprecated: use GET /api/consultations?status=IN_PROGRESS. PATIENT (always empty) or DOCTOR.")
    @ApiResponse(responseCode = "200", description = "The consultation list (JSON array)")
    public ResponseEntity<List<ConsultationResponse>> getMyInProgressConsultations(
            @RequestAttribute("systemId") String systemId,
            @RequestAttribute("userRole") String userRole) {
        return ResponseEntity.ok(consultationService.getInProgressForUser(systemId, userRole));
    }

    /** @deprecated use {@code GET /api/consultations?status=CANCELLED} */
    @Deprecated
    @GetMapping("/me/cancelled")
    @Operation(deprecated = true, summary = "List the caller's cancelled consultations (array, newest 200)",
            description = "Deprecated: use GET /api/consultations?status=CANCELLED. PATIENT or DOCTOR.")
    @ApiResponse(responseCode = "200", description = "The consultation list (JSON array)")
    public ResponseEntity<List<ConsultationResponse>> getMyCancelledConsultations(
            @RequestAttribute("systemId") String systemId,
            @RequestAttribute("userRole") String userRole) {
        return ResponseEntity.ok(consultationService.getCancelledForUser(systemId, userRole));
    }

    /** @deprecated use {@code GET /api/consultations?status=DRAFT} */
    @Deprecated
    @GetMapping("/me/draft")
    @Operation(deprecated = true, summary = "List the caller's draft consultations (array, newest 200)",
            description = "Deprecated: use GET /api/consultations?status=DRAFT. PATIENT (always empty) or DOCTOR.")
    @ApiResponse(responseCode = "200", description = "The consultation list (JSON array)")
    public ResponseEntity<List<ConsultationResponse>> getMyDraftConsultations(
            @RequestAttribute("systemId") String systemId,
            @RequestAttribute("userRole") String userRole) {
        return ResponseEntity.ok(consultationService.getDraftForUser(systemId, userRole));
    }
}
