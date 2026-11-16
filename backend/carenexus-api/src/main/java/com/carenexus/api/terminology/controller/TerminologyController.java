package com.carenexus.api.terminology.controller;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.terminology.dto.response.IcdCodeResponse;
import com.carenexus.api.terminology.service.TerminologyService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Clinical terminology lookups.
 *
 * - GET /api/terminology/icd?query=&page=&size=   ICD-10 type-ahead (DOCTOR, ADMIN)
 */
@RestController
@RequestMapping("/api/terminology")
@RequiredArgsConstructor
@Validated
@Tag(name = "Terminology", description = "ICD-10 code lookup for the medical record form")
public class TerminologyController {

    private final TerminologyService terminologyService;

    @GetMapping("/icd")
    @Operation(summary = "Search ICD-10 codes",
            description = "DOCTOR and ADMIN only. Case-insensitive: an entry matches when its code starts with the "
                    + "query (the dot is ignored on both sides, so J06, J069 and j06.9 all find J06.9) or when every "
                    + "word of the query occurs in its description. Code matches come first, then description "
                    + "matches, each by code. A query shorter than 2 characters answers an empty page. At most 200 "
                    + "matches per search. Served by the configured terminology provider (bundled ICD-10 subset); "
                    + "codes outside it are still accepted on records as unverified. Not audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page of matching codes"),
            @ApiResponse(responseCode = "400", description = "query longer than 100 characters (code VALIDATION_FAILED), "
                    + "or page / size not an integer",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is a patient or caregiver",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PageResponse<IcdCodeResponse>> searchIcd(
            @Parameter(description = "Code prefix or words of the description; trimmed, max 100 characters")
            @RequestParam(required = false, defaultValue = "")
            @Size(max = TerminologyService.MAX_QUERY_LENGTH, message = "must be at most 100 characters") String query,
            @Parameter(description = "Zero-based page; a negative value becomes 0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100")
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(terminologyService.searchIcd(query, page, size));
    }
}
