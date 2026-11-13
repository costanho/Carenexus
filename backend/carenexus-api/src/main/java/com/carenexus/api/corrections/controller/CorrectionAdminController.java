package com.carenexus.api.corrections.controller;

import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.dto.StandardErrors;
import com.carenexus.api.corrections.dto.request.AssignCorrectionRequest;
import com.carenexus.api.corrections.dto.response.AssignableDoctorResponse;
import com.carenexus.api.corrections.dto.response.CorrectionRequestResponse;
import com.carenexus.api.corrections.service.CorrectionRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Correction requests for administrators (ADMIN only: route rule {@code /api/admin/**} plus the service check).
 *
 * - GET   /api/admin/correction-requests                              every request (status, unassigned filters)
 * - GET   /api/admin/correction-requests/{id}/assignable-doctors      doctors the request can be assigned to
 * - PATCH /api/admin/correction-requests/{id}/assign                  assign or reassign an OPEN request
 *
 * Administrators resolve requests through {@code POST /api/correction-requests/{id}/resolve}.
 */
@RestController
@RequestMapping("/api/admin/correction-requests")
@RequiredArgsConstructor
@Tag(name = "Admin: correction requests", description = "Oversight of correction requests: assignment and the unassigned queue")
public class CorrectionAdminController {

    private final CorrectionRequestService correctionRequestService;

    @GetMapping
    @Operation(summary = "List every correction request",
            description = "ADMIN only. Newest first. Optional status filter (OPEN, ACCEPTED, REJECTED; "
                    + "case-insensitive) and unassigned=true to keep only requests without a doctor. The admin home "
                    + "count of unassigned requests is totalElements of ?status=OPEN&unassigned=true&size=1. Audited "
                    + "as a list read.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "One page of requests")
    public ResponseEntity<PageResponse<CorrectionRequestResponse>> list(
            @Parameter(description = "OPEN, ACCEPTED or REJECTED; blank = every status")
            @RequestParam(required = false) String status,
            @Parameter(description = "true = only requests with no assigned doctor; false or absent = no filter")
            @RequestParam(defaultValue = "false") boolean unassigned,
            @Parameter(description = "Zero-based page; negative becomes 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(correctionRequestService.listAll(status, unassigned, page, size));
    }

    @GetMapping("/{id}/assignable-doctors")
    @Operation(summary = "List the doctors a correction request can be assigned to",
            description = "ADMIN only. The patient's active related doctors (care team, appointments, consultations), "
                    + "sorted by name; not paged (hard cap 200). An empty array means the patient has no active "
                    + "related doctor: the administrator resolves the request directly. Audited as READ "
                    + "CORRECTION_REQUEST.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The assignable doctors")
    public ResponseEntity<List<AssignableDoctorResponse>> assignableDoctors(
            @Parameter(description = "Correction request id", example = "41") @PathVariable Integer id) {
        return ResponseEntity.ok(correctionRequestService.assignableDoctors(id));
    }

    @PatchMapping("/{id}/assign")
    @Operation(summary = "Assign a correction request to a doctor",
            description = "ADMIN only. Assigns or reassigns an OPEN request to an active doctor with a care "
                    + "relationship with the patient and notifies that doctor. Assigning the current assignee again "
                    + "is a no-op (200, no notification). A resolved request answers 409. Audited as UPDATE "
                    + "CORRECTION_REQUEST.")
    @StandardErrors
    @ApiResponse(responseCode = "200", description = "The request with its new assignee")
    public ResponseEntity<CorrectionRequestResponse> assign(
            @Parameter(description = "Correction request id", example = "41") @PathVariable Integer id,
            @Valid @RequestBody AssignCorrectionRequest request) {
        return ResponseEntity.ok(correctionRequestService.assign(id, request));
    }
}
