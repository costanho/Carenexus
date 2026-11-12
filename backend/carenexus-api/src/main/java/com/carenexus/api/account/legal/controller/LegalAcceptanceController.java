package com.carenexus.api.account.legal.controller;

import com.carenexus.api.account.legal.dto.request.AcceptLegalRequest;
import com.carenexus.api.account.legal.dto.response.LegalStatusResponse;
import com.carenexus.api.account.legal.service.LegalService;
import com.carenexus.api.common.dto.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in account's acceptance of the terms and the privacy policy (any role, own account only).
 *
 * - GET  /api/users/me/legal          what the caller accepted and what is still pending
 * - POST /api/users/me/legal/accept   accept the current version of one document
 */
@RestController
@RequestMapping("/api/users/me/legal")
@RequiredArgsConstructor
@Tag(name = "Account: legal acceptance", description = "Accepting the terms of service and the privacy policy")
public class LegalAcceptanceController {

    private final LegalService legalService;

    @GetMapping
    @Operation(summary = "Get my legal acceptance state",
            description = "Any signed-in user, own account only. Returns the caller's acceptances (newest first, at "
                    + "most 50), the current documents not accepted yet (TERMS before PRIVACY) and actionRequired, "
                    + "the same value as flags.legalActionRequired. No side effects.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The caller's state"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<LegalStatusResponse> status() {
        return ResponseEntity.ok(legalService.myStatus());
    }

    @PostMapping("/accept")
    @Operation(summary = "Accept the current version of a legal document",
            description = "Any signed-in user, for their own account only. The version must be the current version "
                    + "of docType (exact match). Writes one acceptance row with the request address and User-Agent "
                    + "and one audit row (LEGAL_ACCEPT); repeating it for an accepted version writes nothing. "
                    + "Returns the caller's state afterwards: 201 when a row was written, 200 otherwise.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "This version was already accepted; nothing was written"),
            @ApiResponse(responseCode = "201", description = "Accepted; the caller's state afterwards"),
            @ApiResponse(responseCode = "400", description = "Invalid field (code VALIDATION_FAILED), malformed body, "
                    + "or the version is not the current one (code LEGAL_ACCEPTANCE_REQUIRED)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<LegalStatusResponse> accept(@Valid @RequestBody AcceptLegalRequest request,
                                                      HttpServletRequest httpRequest) {
        LegalService.AcceptOutcome outcome = legalService.accept(request, httpRequest.getRemoteAddr(),
                httpRequest.getHeader(HttpHeaders.USER_AGENT));
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK).body(outcome.status());
    }
}
