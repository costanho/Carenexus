package com.carenexus.api.facility.controller;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.idempotency.IdempotencyService;
import com.carenexus.api.common.idempotency.IdempotentResult;
import com.carenexus.api.facility.dto.request.FacilityRequest;
import com.carenexus.api.facility.dto.response.FacilityResponse;
import com.carenexus.api.facility.service.FacilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
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

/**
 * Facility administration (ADMIN only: route rule {@code /api/admin/**} plus the service check).
 *
 * - GET   /api/admin/facilities?active=&query=&page=&size=   every facility, or only active / inactive ones
 * - POST  /api/admin/facilities                              create (Idempotency-Key), always active
 * - PUT   /api/admin/facilities/{id}                         replace the details
 * - PATCH /api/admin/facilities/{id}/deactivate              take out of use (no delete exists)
 * - PATCH /api/admin/facilities/{id}/activate                put back in use
 *
 * One facility is read through {@code GET /api/facilities/{id}}, which also returns inactive facilities.
 */
@RestController
@RequestMapping("/api/admin/facilities")
@RequiredArgsConstructor
@Validated
@Tag(name = "Admin: facilities", description = "Create, edit, deactivate and reactivate clinic locations")
public class FacilityAdminController {

    static final String CREATE_SCOPE = "POST /api/admin/facilities";

    private final FacilityService facilityService;
    private final IdempotencyService idempotencyService;

    @GetMapping
    @Operation(summary = "List facilities in any state",
            description = "ADMIN only. active=true lists active facilities, active=false inactive ones, no value "
                    + "lists both. Sorted by name (case-insensitive), then facilityId. query matches name or address, "
                    + "case-insensitive. Not audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page of facilities"),
            @ApiResponse(responseCode = "400", description = "query longer than 100 characters (code VALIDATION_FAILED), "
                    + "or active / page / size of the wrong type",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PageResponse<FacilityResponse>> list(
            @Parameter(description = "true: only active, false: only inactive, absent: both")
            @RequestParam(required = false) Boolean active,
            @Parameter(description = "Case-insensitive search on name or address; blank means no filter")
            @RequestParam(required = false)
            @Size(max = FacilityService.MAX_QUERY_LENGTH, message = "must be at most 100 characters") String query,
            @Parameter(description = "Zero-based page; a negative value becomes 0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100")
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(facilityService.adminList(active, query, page, size));
    }

    @PostMapping
    @Operation(summary = "Create a facility",
            description = "ADMIN only. The new facility is active. Names are not unique. Accepts an Idempotency-Key "
                    + "header (8 to 64 characters of letters, digits, '_' or '-'): a repeat of a finished call by the "
                    + "same administrator answers 200 with the body stored at creation and creates nothing. Writes "
                    + "one audit row (CREATE / FACILITY).")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The created facility"),
            @ApiResponse(responseCode = "200", description = "Idempotent replay: the response of the first call"),
            @ApiResponse(responseCode = "400", description = "Malformed body, invalid field (code VALIDATION_FAILED) "
                    + "or malformed Idempotency-Key",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "The same Idempotency-Key is still being processed "
                    + "(code IDEMPOTENCY_IN_PROGRESS)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<FacilityResponse> create(
            @Parameter(description = "Optional key that makes a retried create safe")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody FacilityRequest request) {
        IdempotentResult<FacilityResponse> result = idempotencyService.execute(
                CREATE_SCOPE, idempotencyKey, FacilityResponse.class, () -> facilityService.create(request));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.body());
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace the details of a facility",
            description = "ADMIN only. Full replacement of name, type, address, phone and email: an optional field "
                    + "that is missing, null or blank clears the stored value. Works on active and inactive "
                    + "facilities; isActive is not changed. Always stamps updatedAt. Writes one audit row "
                    + "(UPDATE / FACILITY).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The facility after the change"),
            @ApiResponse(responseCode = "400", description = "id not an integer, malformed body or invalid field "
                    + "(code VALIDATION_FAILED)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No facility with that id",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<FacilityResponse> update(
            @Parameter(description = "facilityId", example = "4") @PathVariable Integer id,
            @Valid @RequestBody FacilityRequest request) {
        return ResponseEntity.ok(facilityService.update(id, request));
    }

    @PatchMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate a facility",
            description = "ADMIN only. The facility disappears from GET /api/facilities; GET /api/facilities/{id} "
                    + "still returns it. Existing appointments and care-team entries keep it. Already inactive: "
                    + "no-op, 200 with the current state. Writes one audit row (UPDATE / FACILITY) when the state "
                    + "changed.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The facility after the change"),
            @ApiResponse(responseCode = "400", description = "id is not an integer",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No facility with that id",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<FacilityResponse> deactivate(
            @Parameter(description = "facilityId", example = "4") @PathVariable Integer id) {
        return ResponseEntity.ok(facilityService.deactivate(id));
    }

    @PatchMapping("/{id}/activate")
    @Operation(summary = "Activate a facility",
            description = "ADMIN only. The facility is listed by GET /api/facilities again. Already active: no-op, "
                    + "200 with the current state. Writes one audit row (UPDATE / FACILITY) when the state changed.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The facility after the change"),
            @ApiResponse(responseCode = "400", description = "id is not an integer",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No facility with that id",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<FacilityResponse> activate(
            @Parameter(description = "facilityId", example = "4") @PathVariable Integer id) {
        return ResponseEntity.ok(facilityService.activate(id));
    }
}
