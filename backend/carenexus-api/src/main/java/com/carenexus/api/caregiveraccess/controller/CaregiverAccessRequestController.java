package com.carenexus.api.caregiveraccess.controller;

import com.carenexus.api.caregiveraccess.dto.request.CreateAccessRequestRequest;
import com.carenexus.api.caregiveraccess.dto.response.AccessRequestAcceptedResponse;
import com.carenexus.api.caregiveraccess.dto.response.CaregiverAccessRequestResponse;
import com.carenexus.api.caregiveraccess.service.CaregiverAccessRequestService;
import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.dto.StandardErrors;
import com.carenexus.api.common.ratelimit.RateLimitGuard;
import com.carenexus.api.common.service.AuthorizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Caregiver side of access requests (CAREGIVER only at the route).
 *
 * - POST   /api/caregiver/access-requests              ask an existing patient for access (always 202, same body)
 * - GET    /api/caregiver/access-requests              the caller's requests (paged, optional status)
 * - DELETE /api/caregiver/access-requests/{requestId}  cancel the caller's pending request (204)
 *
 * Authorization, audit and notifications happen in {@link CaregiverAccessRequestService}.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Caregiver access requests",
        description = "Caregivers ask existing patients for access; patients approve with the permissions they choose, "
                + "or decline")
public class CaregiverAccessRequestController {

    static final String BASE = "/api/caregiver/access-requests";
    static final String RATE_LIMIT_BUCKET = "caregiver-request";

    private final CaregiverAccessRequestService requestService;
    private final AuthorizationService authorizationService;
    private final RateLimitGuard rateLimitGuard;

    @PostMapping(BASE)
    @Operation(summary = "Ask a patient for access",
            description = "CAREGIVER with a caregiver profile. Always answers 202 with the same body whether or not the "
                    + "email belongs to a patient. A matching patient (active patient account with that email) gets a "
                    + "pending request and an in-app notification; an email with no account is stored too, nobody can "
                    + "approve it and it expires after 30 days. A repeat for a patient or email with a pending request "
                    + "of the caller changes nothing. Rate limited (bucket caregiver-request, 10 per day per caregiver, "
                    + "every call counted). Audited CREATE ACCESS_REQUEST when a row is stored.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Accepted (neutral confirmation)",
                    content = @Content(schema = @Schema(implementation = AccessRequestAcceptedResponse.class))),
            @ApiResponse(responseCode = "409", description = "The caller already holds an unrevoked (active or expired) "
                    + "grant from this patient",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "429", description = "Too many requests (code RATE_LIMITED, header Retry-After)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    @StandardErrors
    public ResponseEntity<AccessRequestAcceptedResponse> requestAccess(
            @Valid @RequestBody CreateAccessRequestRequest request) {
        rateLimitGuard.check(RATE_LIMIT_BUCKET, authorizationService.currentSystemId());
        requestService.requestAccess(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(AccessRequestAcceptedResponse.accepted());
    }

    @GetMapping(BASE)
    @Operation(summary = "List my access requests",
            description = "CAREGIVER. The caller's own requests in every status (or the one given), unmatched emails "
                    + "included, newest first. The patient's system id and name appear only once a request is APPROVED; "
                    + "the granted permissions are on GET /api/caregiver/dependents (same accessId). Audited as one "
                    + "READ ACCESS_REQUEST row when the page is not empty.")
    @ApiResponse(responseCode = "200", description = "A page of requests")
    @StandardErrors
    public ResponseEntity<PageResponse<CaregiverAccessRequestResponse>> listMine(
            @Parameter(description = "PENDING, APPROVED, DECLINED, CANCELLED or EXPIRED; absent = all")
            @RequestParam(required = false) String status,
            @Parameter(description = "Zero-based page; negative becomes 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(requestService.listForCaregiver(status, page, size));
    }

    @DeleteMapping(BASE + "/{requestId}")
    @Operation(summary = "Cancel my pending access request",
            description = "The CAREGIVER who made the request. Idempotent: a cancelled request answers 204 again. The "
                    + "patient is not notified. Audited DELETE ACCESS_REQUEST on the first cancel.")
    @ApiResponse(responseCode = "204", description = "Cancelled")
    @StandardErrors
    public ResponseEntity<Void> cancel(@Parameter(description = "Request id") @PathVariable Integer requestId) {
        requestService.cancel(requestId);
        return ResponseEntity.noContent().build();
    }
}
