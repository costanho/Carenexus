package com.carenexus.api.facility.controller;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.facility.dto.response.FacilityResponse;
import com.carenexus.api.facility.service.FacilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Facilities for every signed-in user (PATIENT, DOCTOR, CAREGIVER, ADMIN). No ownership rule and no caregiver
 * permission: a facility is clinic reference data, not patient data.
 *
 * - GET /api/facilities?query=&page=&size=   active facilities only, by name
 * - GET /api/facilities/{id}                 one facility, active or not
 */
@RestController
@RequestMapping("/api/facilities")
@RequiredArgsConstructor
@Validated
@Tag(name = "Facilities", description = "Clinic locations offered for in-person appointments and care-team entries")
public class FacilityController {

    private final FacilityService facilityService;

    @GetMapping
    @Operation(summary = "List active facilities",
            description = "Any signed-in user. Active facilities only (a deactivated facility never appears here), "
                    + "sorted by name (case-insensitive), then facilityId. query matches name or address, "
                    + "case-insensitive; % and _ are matched literally. An empty page is a normal answer (no "
                    + "facility configured yet). Not audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page of active facilities"),
            @ApiResponse(responseCode = "400", description = "query longer than 100 characters (code VALIDATION_FAILED), "
                    + "or page / size not an integer",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PageResponse<FacilityResponse>> list(
            @Parameter(description = "Case-insensitive search on name or address; blank means no filter")
            @RequestParam(required = false)
            @Size(max = FacilityService.MAX_QUERY_LENGTH, message = "must be at most 100 characters") String query,
            @Parameter(description = "Zero-based page; a negative value becomes 0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100")
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(facilityService.listActive(query, page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one facility",
            description = "Any signed-in user. Returns the facility whether it is active or not (read isActive), so "
                    + "screens can still show the location of older appointments and care-team entries. Not audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The facility"),
            @ApiResponse(responseCode = "400", description = "id is not an integer",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No facility with that id",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<FacilityResponse> get(
            @Parameter(description = "facilityId", example = "4") @PathVariable Integer id) {
        return ResponseEntity.ok(facilityService.get(id));
    }
}
