package com.carenexus.api.caregiveraccess.controller;

import com.carenexus.api.caregiveraccess.dto.request.ApproveAccessRequestRequest;
import com.carenexus.api.caregiveraccess.dto.request.DeclineAccessRequestRequest;
import com.carenexus.api.caregiveraccess.dto.response.CaregiverAccessRequestResponse;
import com.carenexus.api.caregiveraccess.service.CaregiverAccessRequestService;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.dto.StandardErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Patient side of access requests (PATIENT only; an ADMIN passes the route rule and is refused by the service).
 *
 * - GET  /api/patients/me/caregiver-access/requests                       requests addressed to the caller
 * - POST /api/patients/me/caregiver-access/requests/{requestId}/approve   approve with the chosen permissions
 * - POST /api/patients/me/caregiver-access/requests/{requestId}/decline   decline with an optional reason
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Caregiver access requests",
        description = "Caregivers ask existing patients for access; patients approve with the permissions they choose, "
                + "or decline")
public class PatientAccessRequestController {

    static final String BASE = "/api/patients/me/caregiver-access/requests";

    private final CaregiverAccessRequestService requestService;

    @GetMapping(BASE)
    @Operation(summary = "List access requests addressed to me",
            description = "PATIENT only. Requests addressed to the caller in every status (or the one given; the "
                    + "Requests section sends status=PENDING), newest first, with the caregiver's name and email. "
                    + "Audited as one READ ACCESS_REQUEST row when the page is not empty.")
    @ApiResponse(responseCode = "200", description = "A page of requests")
    @StandardErrors
    public ResponseEntity<PageResponse<CaregiverAccessRequestResponse>> list(
            @Parameter(description = "PENDING, APPROVED, DECLINED, CANCELLED or EXPIRED; absent = all")
            @RequestParam(required = false) String status,
            @Parameter(description = "Zero-based page; negative becomes 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(requestService.listForPatient(status, page, size));
    }

    @PostMapping(BASE + "/{requestId}/approve")
    @Operation(summary = "Approve an access request",
            description = "The PATIENT the request is addressed to. The body decides the final permissions (absent "
                    + "flags are false, absent expiresAt = no expiry); only the relationship comes from the request. "
                    + "Creates the grant, or reuses a revoked one, in the same transaction; 409 when the caregiver "
                    + "already has an active or expired grant (the request stays PENDING). Approving an APPROVED "
                    + "request answers its current state. Writes one GRANTED consent-ledger row and notifies the "
                    + "caregiver once (category SYSTEM). Audited UPDATE ACCESS_REQUEST plus CREATE DEPENDENT_ACCESS.")
    @ApiResponse(responseCode = "200", description = "The request, APPROVED, with the grant's accessId",
            content = @Content(schema = @Schema(implementation = CaregiverAccessRequestResponse.class)))
    @StandardErrors
    public ResponseEntity<CaregiverAccessRequestResponse> approve(
            @Parameter(description = "Request id") @PathVariable Integer requestId,
            @Valid @RequestBody ApproveAccessRequestRequest request) {
        return ResponseEntity.ok(requestService.approve(requestId, request));
    }

    @PostMapping(BASE + "/{requestId}/decline")
    @Operation(summary = "Decline an access request",
            description = "The PATIENT the request is addressed to. Optional body {reason} (shown to the caregiver). "
                    + "Declining a DECLINED request answers its current state and keeps the first reason. Notifies the "
                    + "caregiver (category SYSTEM). Audited UPDATE ACCESS_REQUEST.")
    @ApiResponse(responseCode = "200", description = "The request, DECLINED",
            content = @Content(schema = @Schema(implementation = CaregiverAccessRequestResponse.class)))
    @StandardErrors
    public ResponseEntity<CaregiverAccessRequestResponse> decline(
            @Parameter(description = "Request id") @PathVariable Integer requestId,
            @Valid @RequestBody(required = false) DeclineAccessRequestRequest request) {
        return ResponseEntity.ok(requestService.decline(requestId, request));
    }
}
