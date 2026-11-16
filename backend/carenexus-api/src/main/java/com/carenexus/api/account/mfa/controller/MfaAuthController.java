package com.carenexus.api.account.mfa.controller;

import com.carenexus.api.account.mfa.dto.request.MfaChallengeRequest;
import com.carenexus.api.account.mfa.dto.request.MfaSetupConfirmRequest;
import com.carenexus.api.account.mfa.dto.request.MfaVerifyRequest;
import com.carenexus.api.account.mfa.dto.response.MfaSetupConfirmResponse;
import com.carenexus.api.account.mfa.dto.response.MfaSetupResponse;
import com.carenexus.api.account.mfa.service.MfaChallengeService;
import com.carenexus.api.auth.dto.response.AuthResponse;
import com.carenexus.api.common.dto.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two-factor step of sign-in. Public routes ({@code POST /api/auth/mfa/**} is open in SecurityConfig):
 * the credential is the challenge token returned by {@code POST /api/auth/login}; a bearer token is ignored.
 *
 * - POST /api/auth/mfa/verify          challenge MFA: code or recovery code -> session
 * - POST /api/auth/mfa/setup/start     challenge MFA_SETUP: secret for the authenticator app
 * - POST /api/auth/mfa/setup/confirm   challenge MFA_SETUP: first code -> recovery codes and session
 */
@RestController
@RequestMapping("/api/auth/mfa")
@RequiredArgsConstructor
@Tag(name = "Authentication: two-factor step", description = "Completes a sign-in that answered with a challenge. "
        + "Public endpoints authenticated by the challenge token.")
public class MfaAuthController {

    private final MfaChallengeService challengeService;

    @PostMapping("/verify")
    @SecurityRequirements
    @Operation(summary = "Complete sign-in with a two-factor code",
            description = "Public; the challenge token (type MFA) is the credential. Accepts a 6-digit code or one "
                    + "unused recovery code (consumed). On success the challenge is used up and the session is issued "
                    + "exactly like a sign-in without two-factor authentication; the device description of the "
                    + "sign-in request is kept. Five wrong codes end the challenge. Audited MFA_SUCCESS or "
                    + "MFA_FAILURE. Shares the auth-mfa limit (5 per 5 minutes) of the account.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Session: tokens, user and flags"),
            @ApiResponse(responseCode = "400", description = "Invalid body or both/neither of code and recoveryCode "
                    + "(code VALIDATION_FAILED), or a wrong or used code (code MFA_CODE_INVALID)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Challenge unknown, expired, used, of another type, "
                    + "locked after five wrong codes, or the account can no longer sign in (code MFA_CHALLENGE_INVALID)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The account cannot sign in (codes ACCOUNT_CLOSED, "
                    + "ACCOUNT_PENDING_VERIFICATION, ACCOUNT_REJECTED)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "429", description = "Too many attempts (code RATE_LIMITED)",
                    headers = @Header(name = "Retry-After", description = "Seconds until the next attempt is allowed",
                            schema = @Schema(type = "integer")),
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<AuthResponse> verify(@Valid @RequestBody MfaVerifyRequest request,
                                               HttpServletRequest httpRequest) {
        return ResponseEntity.ok(challengeService.verify(request, httpRequest.getRemoteAddr(),
                httpRequest.getHeader("User-Agent")));
    }

    @PostMapping("/setup/start")
    @SecurityRequirements
    @Operation(summary = "Get the secret for required two-factor setup",
            description = "Public; the challenge token (type MFA_SETUP) is the credential. The first call creates the "
                    + "secret and binds it to the challenge; repeated calls return the same secret. Does not extend "
                    + "the challenge and is not counted as an attempt.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Secret and otpauth URI"),
            @ApiResponse(responseCode = "400", description = "challengeToken is blank (code VALIDATION_FAILED)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Challenge unknown, expired, used, of another type, "
                    + "locked, or the account can no longer sign in or enrolled meanwhile (code MFA_CHALLENGE_INVALID)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<MfaSetupResponse> startSetup(@Valid @RequestBody MfaChallengeRequest request) {
        return ResponseEntity.ok(challengeService.startSetup(request));
    }

    @PostMapping("/setup/confirm")
    @SecurityRequirements
    @Operation(summary = "Finish required two-factor setup and sign in",
            description = "Public; the challenge token (type MFA_SETUP) is the credential. Checks the first code of "
                    + "the new authenticator, turns two-factor authentication on, returns ten recovery codes (shown "
                    + "once) and the session, in one transaction. Five wrong codes end the challenge. Audited "
                    + "MFA_ENABLED or MFA_FAILURE. Shares the auth-mfa limit of the account.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Recovery codes and the session"),
            @ApiResponse(responseCode = "400", description = "Invalid body (code VALIDATION_FAILED), or a wrong code "
                    + "(code MFA_CODE_INVALID)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Challenge unknown, expired, used, of another type, "
                    + "locked, or the account can no longer sign in or enrolled meanwhile (code MFA_CHALLENGE_INVALID)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The account cannot sign in (codes ACCOUNT_CLOSED, "
                    + "ACCOUNT_PENDING_VERIFICATION, ACCOUNT_REJECTED)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "setup/start was not called for this challenge",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "429", description = "Too many attempts (code RATE_LIMITED)",
                    headers = @Header(name = "Retry-After", description = "Seconds until the next attempt is allowed",
                            schema = @Schema(type = "integer")),
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<MfaSetupConfirmResponse> confirmSetup(@Valid @RequestBody MfaSetupConfirmRequest request,
                                                                HttpServletRequest httpRequest) {
        return ResponseEntity.ok(challengeService.confirmSetup(request, httpRequest.getRemoteAddr(),
                httpRequest.getHeader("User-Agent")));
    }
}
