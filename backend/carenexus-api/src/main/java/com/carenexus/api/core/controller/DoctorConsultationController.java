package com.carenexus.api.core.controller;

import com.carenexus.api.common.dto.StandardErrors;
import com.carenexus.api.core.dto.response.ConsultationResponse;
import com.carenexus.api.core.dto.response.ConsultationStatsResponse;
import com.carenexus.api.core.service.ConsultationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Doctor-facing consultation routes. The route filter limits {@code /api/doctors/**} to DOCTOR and ADMIN; the rest
 * is enforced in ConsultationService: every /{doctorSystemId}/... route requires the caller to be that doctor (or an
 * admin), the self routes use the caller's id (an admin gets empty lists and zero counts), and the status changes
 * require the authoring doctor.
 *
 * The mark-* routes and the stats are current; every list here is a deprecated array (newest 200, rule 0.8):
 * new code uses {@code GET /api/consultations?doctorSystemId=<caller>}.
 */
@RestController
@RequestMapping("/api/doctors/consultations")
@RequiredArgsConstructor
@Tag(name = "Doctor consultations", description = "Status changes and counts of the caller's consultations, plus "
        + "deprecated per-status lists")
@StandardErrors
public class DoctorConsultationController {

    private final ConsultationService consultationService;

    // ==================== STATUS CHANGES ====================

    @PatchMapping("/{consultationId}/mark-in-progress")
    @Operation(summary = "Start a draft consultation",
            description = "Authoring doctor only (admins included in the refusal). DRAFT to IN_PROGRESS, sets "
                    + "startedAt once; already IN_PROGRESS is a no-op; terminal states answer 409.")
    @ApiResponse(responseCode = "200", description = "The updated consultation")
    public ResponseEntity<ConsultationResponse> markConsultationInProgress(@PathVariable Integer consultationId) {
        return ResponseEntity.ok(consultationService.updateConsultationStatus(consultationId, "IN_PROGRESS"));
    }

    @PatchMapping("/{consultationId}/mark-completed")
    @Operation(summary = "Complete a consultation",
            description = "Authoring doctor only. DRAFT or IN_PROGRESS to COMPLETED; requires chiefComplaint and "
                    + "diagnosis; sets completedAt once and publishes the consultation-completed event (appointment, "
                    + "medical record and invoice listeners run in the same transaction; a failing listener rolls "
                    + "the completion back). Already COMPLETED is a no-op; CANCELLED answers 409.")
    @ApiResponse(responseCode = "200", description = "The updated consultation")
    public ResponseEntity<ConsultationResponse> markConsultationCompleted(@PathVariable Integer consultationId) {
        return ResponseEntity.ok(consultationService.updateConsultationStatus(consultationId, "COMPLETED"));
    }

    @PatchMapping("/{consultationId}/mark-cancelled")
    @Operation(summary = "Cancel a consultation",
            description = "Authoring doctor only. DRAFT or IN_PROGRESS to CANCELLED (a new consultation may then be "
                    + "opened for the appointment). Already CANCELLED is a no-op; COMPLETED answers 409.")
    @ApiResponse(responseCode = "200", description = "The updated consultation")
    public ResponseEntity<ConsultationResponse> markConsultationCancelled(@PathVariable Integer consultationId) {
        return ResponseEntity.ok(consultationService.updateConsultationStatus(consultationId, "CANCELLED"));
    }

    /** @deprecated duplicate of {@code PATCH /api/consultations/{id}/status} */
    @Deprecated
    @PatchMapping("/{consultationId}/status")
    @Operation(deprecated = true, summary = "Change a consultation's status",
            description = "Deprecated duplicate of PATCH /api/consultations/{id}/status (same rules and errors).")
    @ApiResponse(responseCode = "200", description = "The updated consultation")
    public ResponseEntity<ConsultationResponse> updateConsultationStatus(
            @PathVariable Integer consultationId,
            @Parameter(description = "DRAFT, IN_PROGRESS, COMPLETED or CANCELLED (case-insensitive)")
            @RequestParam String status) {
        return ResponseEntity.ok(consultationService.updateConsultationStatus(consultationId, status));
    }

    // ==================== STATS ====================

    @GetMapping("/stats")
    @Operation(summary = "Count the caller's consultations per status",
            description = "DOCTOR (own counts); an admin gets zeros. One GROUP BY query; not audited.")
    @ApiResponse(responseCode = "200", description = "The consultation stats")
    public ResponseEntity<ConsultationStatsResponse> getMyConsultationStats(@RequestAttribute("systemId") String systemId) {
        return ResponseEntity.ok(consultationService.getStatsForDoctor(systemId));
    }

    @GetMapping("/{doctorSystemId}/stats")
    @Operation(summary = "Count a doctor's consultations per status",
            description = "That doctor or an admin. An unknown doctor id gives zeros. Not audited.")
    @ApiResponse(responseCode = "200", description = "The consultation stats")
    public ResponseEntity<ConsultationStatsResponse> getDoctorConsultationStats(@PathVariable String doctorSystemId) {
        return ResponseEntity.ok(consultationService.getStatsForDoctor(doctorSystemId));
    }

    // ==================== DEPRECATED ARRAY LISTS (newest 200) ====================

    /** @deprecated use {@code GET /api/consultations?doctorSystemId=<caller>} */
    @Deprecated
    @GetMapping
    @Operation(deprecated = true, summary = "List the caller's active consultations (array)",
            description = "Deprecated: use GET /api/consultations?doctorSystemId=<caller>. The newest 200 "
                    + "non-cancelled consultations, then ordered IN_PROGRESS, DRAFT, COMPLETED. An admin gets [].")
    @ApiResponse(responseCode = "200", description = "The consultation list (JSON array)")
    public ResponseEntity<List<ConsultationResponse>> getMyConsultations(@RequestAttribute("systemId") String systemId) {
        return ResponseEntity.ok(consultationService.getActiveForDoctor(systemId));
    }

    /** @deprecated use {@code GET /api/consultations?doctorSystemId=<caller>&status=DRAFT} */
    @Deprecated
    @GetMapping("/draft")
    @Operation(deprecated = true, summary = "List the caller's draft consultations (array)",
            description = "Deprecated: use GET /api/consultations?doctorSystemId=<caller>&status=DRAFT.")
    @ApiResponse(responseCode = "200", description = "The consultation list (JSON array)")
    public ResponseEntity<List<ConsultationResponse>> getMyDraftConsultations(@RequestAttribute("systemId") String systemId) {
        return ResponseEntity.ok(consultationService.getDraftForUser(systemId, "DOCTOR"));
    }

    /** @deprecated use {@code GET /api/consultations?doctorSystemId=<caller>&status=IN_PROGRESS} */
    @Deprecated
    @GetMapping("/in-progress")
    @Operation(deprecated = true, summary = "List the caller's consultations in progress (array)",
            description = "Deprecated: use GET /api/consultations?doctorSystemId=<caller>&status=IN_PROGRESS.")
    @ApiResponse(responseCode = "200", description = "The consultation list (JSON array)")
    public ResponseEntity<List<ConsultationResponse>> getMyInProgressConsultations(@RequestAttribute("systemId") String systemId) {
        return ResponseEntity.ok(consultationService.getInProgressForUser(systemId, "DOCTOR"));
    }

    /** @deprecated use {@code GET /api/consultations?doctorSystemId=<caller>&status=COMPLETED} */
    @Deprecated
    @GetMapping("/completed")
    @Operation(deprecated = true, summary = "List the caller's completed consultations (array)",
            description = "Deprecated: use GET /api/consultations?doctorSystemId=<caller>&status=COMPLETED.")
    @ApiResponse(responseCode = "200", description = "The consultation list (JSON array)")
    public ResponseEntity<List<ConsultationResponse>> getMyCompletedConsultations(@RequestAttribute("systemId") String systemId) {
        return ResponseEntity.ok(consultationService.getCompletedForUser(systemId, "DOCTOR"));
    }

    /** @deprecated use {@code GET /api/consultations?doctorSystemId=<caller>&status=CANCELLED} */
    @Deprecated
    @GetMapping("/cancelled")
    @Operation(deprecated = true, summary = "List the caller's cancelled consultations (array)",
            description = "Deprecated: use GET /api/consultations?doctorSystemId=<caller>&status=CANCELLED.")
    @ApiResponse(responseCode = "200", description = "The consultation list (JSON array)")
    public ResponseEntity<List<ConsultationResponse>> getMyCancelledConsultations(@RequestAttribute("systemId") String systemId) {
        return ResponseEntity.ok(consultationService.getCancelledForUser(systemId, "DOCTOR"));
    }

    /** @deprecated use {@code GET /api/consultations?doctorSystemId=<caller>&patientSystemId=} */
    @Deprecated
    @GetMapping("/patient/{patientSystemId}")
    @Operation(deprecated = true, summary = "List the caller's active consultations with one patient (array)",
            description = "Deprecated: use GET /api/consultations?doctorSystemId=<caller>&patientSystemId=. "
                    + "Non-cancelled, ordered IN_PROGRESS, DRAFT, COMPLETED.")
    @ApiResponse(responseCode = "200", description = "The consultation list (JSON array)")
    public ResponseEntity<List<ConsultationResponse>> getMyConsultationsWithPatient(
            @RequestAttribute("systemId") String systemId,
            @PathVariable String patientSystemId) {
        return ResponseEntity.ok(consultationService.getActiveForDoctorAndPatient(systemId, patientSystemId));
    }

    /** @deprecated use {@code GET /api/consultations?doctorSystemId=} */
    @Deprecated
    @GetMapping("/{doctorSystemId}")
    @Operation(deprecated = true, summary = "List a doctor's consultations (array)",
            description = "Deprecated: use GET /api/consultations?doctorSystemId=. That doctor or an admin.")
    @ApiResponse(responseCode = "200", description = "The consultation list (JSON array)")
    public ResponseEntity<List<ConsultationResponse>> getDoctorConsultations(@PathVariable String doctorSystemId) {
        return ResponseEntity.ok(consultationService.getConsultationsByDoctorSystemId(doctorSystemId));
    }

    /** @deprecated use {@code GET /api/consultations?doctorSystemId=&status=DRAFT} */
    @Deprecated
    @GetMapping("/{doctorSystemId}/draft")
    @Operation(deprecated = true, summary = "List a doctor's draft consultations (array)",
            description = "Deprecated: use GET /api/consultations?doctorSystemId=&status=DRAFT. That doctor or an admin.")
    @ApiResponse(responseCode = "200", description = "The consultation list (JSON array)")
    public ResponseEntity<List<ConsultationResponse>> getDoctorDraftConsultations(@PathVariable String doctorSystemId) {
        return ResponseEntity.ok(consultationService.getDraftForUser(doctorSystemId, "DOCTOR"));
    }

    /** @deprecated use {@code GET /api/consultations?doctorSystemId=&status=IN_PROGRESS} */
    @Deprecated
    @GetMapping("/{doctorSystemId}/in-progress")
    @Operation(deprecated = true, summary = "List a doctor's consultations in progress (array)",
            description = "Deprecated: use GET /api/consultations?doctorSystemId=&status=IN_PROGRESS. That doctor or an admin.")
    @ApiResponse(responseCode = "200", description = "The consultation list (JSON array)")
    public ResponseEntity<List<ConsultationResponse>> getDoctorInProgressConsultations(@PathVariable String doctorSystemId) {
        return ResponseEntity.ok(consultationService.getInProgressForUser(doctorSystemId, "DOCTOR"));
    }

    /** @deprecated use {@code GET /api/consultations?doctorSystemId=&status=COMPLETED} */
    @Deprecated
    @GetMapping("/{doctorSystemId}/completed")
    @Operation(deprecated = true, summary = "List a doctor's completed consultations (array)",
            description = "Deprecated: use GET /api/consultations?doctorSystemId=&status=COMPLETED. That doctor or an admin.")
    @ApiResponse(responseCode = "200", description = "The consultation list (JSON array)")
    public ResponseEntity<List<ConsultationResponse>> getDoctorCompletedConsultations(@PathVariable String doctorSystemId) {
        return ResponseEntity.ok(consultationService.getCompletedForUser(doctorSystemId, "DOCTOR"));
    }

    /** @deprecated use {@code GET /api/consultations?doctorSystemId=&status=CANCELLED} */
    @Deprecated
    @GetMapping("/{doctorSystemId}/cancelled")
    @Operation(deprecated = true, summary = "List a doctor's cancelled consultations (array)",
            description = "Deprecated: use GET /api/consultations?doctorSystemId=&status=CANCELLED. That doctor or an admin.")
    @ApiResponse(responseCode = "200", description = "The consultation list (JSON array)")
    public ResponseEntity<List<ConsultationResponse>> getDoctorCancelledConsultations(@PathVariable String doctorSystemId) {
        return ResponseEntity.ok(consultationService.getCancelledForUser(doctorSystemId, "DOCTOR"));
    }

    /** @deprecated use {@code GET /api/consultations?doctorSystemId=&patientSystemId=} */
    @Deprecated
    @GetMapping("/{doctorSystemId}/patient/{patientSystemId}")
    @Operation(deprecated = true, summary = "List a doctor's consultations with one patient (array)",
            description = "Deprecated: use GET /api/consultations?doctorSystemId=&patientSystemId=. That doctor or an "
                    + "admin; all statuses.")
    @ApiResponse(responseCode = "200", description = "The consultation list (JSON array)")
    public ResponseEntity<List<ConsultationResponse>> getDoctorConsultationsWithPatient(
            @PathVariable String doctorSystemId,
            @PathVariable String patientSystemId) {
        return ResponseEntity.ok(consultationService.getDoctorPatientConsultations(doctorSystemId, patientSystemId));
    }
}
