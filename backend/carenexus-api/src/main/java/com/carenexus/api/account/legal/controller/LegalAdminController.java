package com.carenexus.api.account.legal.controller;

import com.carenexus.api.account.legal.dto.request.PublishLegalDocumentRequest;
import com.carenexus.api.account.legal.dto.response.LegalAcceptanceAdminResponse;
import com.carenexus.api.account.legal.dto.response.LegalDocumentResponse;
import com.carenexus.api.account.legal.service.LegalService;
import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.idempotency.IdempotencyService;
import com.carenexus.api.common.idempotency.IdempotentResult;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Legal documents for administrators (ADMIN only: route rule {@code /api/admin/**} plus the service check).
 *
 * - GET  /api/admin/users/{systemId}/legal-acceptances   acceptance history of one account (paged)
 * - POST /api/admin/legal/documents                      publish a new version of the terms or the privacy policy
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Tag(name = "Admin: legal documents", description = "Publishing legal document versions and reading acceptance records")
public class LegalAdminController {

    static final String PUBLISH_SCOPE = "POST /api/admin/legal/documents";

    private final LegalService legalService;
    private final IdempotencyService idempotencyService;

    @GetMapping("/users/{systemId}/legal-acceptances")
    @Operation(summary = "List the legal acceptances of one account",
            description = "ADMIN only. Returns the account's acceptances with the recorded address and User-Agent, "
                    + "newest first (acceptedAt, then id), paged. Active, inactive and closed accounts are readable. "
                    + "No side effects.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "One page of acceptances (may be empty)"),
            @ApiResponse(responseCode = "400", description = "page or size is not a number",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No account has this system id",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PageResponse<LegalAcceptanceAdminResponse>> acceptances(
            @Parameter(description = "System id of the account", example = "PAT-00014") @PathVariable String systemId,
            @Parameter(description = "Zero-based page index (negative becomes 0)") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, clamped to 1..100") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(legalService.acceptancesOf(systemId, page, size));
    }

    @PostMapping("/legal/documents")
    @Operation(summary = "Publish a new version of the terms or the privacy policy",
            description = "ADMIN only. The version becomes current at once; every account that has not accepted it "
                    + "gets legalActionRequired = true at its next sign-in, token refresh or GET /api/users/me. "
                    + "effectiveAt defaults to now and must be neither in the future nor earlier than the current "
                    + "version's. Writes one audit row (LEGAL_PUBLISH). Honours Idempotency-Key: a repeat returns "
                    + "the stored body with 200 and publishes nothing.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Replay of an earlier request with the same Idempotency-Key"),
            @ApiResponse(responseCode = "201", description = "The published version"),
            @ApiResponse(responseCode = "400", description = "Invalid field (code VALIDATION_FAILED), malformed body or "
                    + "malformed Idempotency-Key",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "The version exists for this type, effectiveAt is earlier "
                    + "than the current version's, or the same Idempotency-Key is still being processed "
                    + "(code IDEMPOTENCY_IN_PROGRESS)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<LegalDocumentResponse> publish(
            @Parameter(in = ParameterIn.HEADER, description = "Optional key (8-64 of [A-Za-z0-9_-]) that makes a retry safe")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody PublishLegalDocumentRequest request) {
        IdempotentResult<LegalDocumentResponse> result = idempotencyService.execute(PUBLISH_SCOPE, idempotencyKey,
                LegalDocumentResponse.class, () -> legalService.publish(request));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.body());
    }
}
