package com.carenexus.api.account.mfa.controller;

import com.carenexus.api.account.mfa.dto.request.MfaCodeRequest;
import com.carenexus.api.account.mfa.dto.request.MfaDisableRequest;
import com.carenexus.api.account.mfa.dto.response.MfaRecoveryCodesResponse;
import com.carenexus.api.account.mfa.dto.response.MfaSetupResponse;
import com.carenexus.api.account.mfa.dto.response.MfaStatusResponse;
import com.carenexus.api.account.mfa.service.MfaAccountService;
import com.carenexus.api.common.dto.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Two-factor authentication of the signed-in account. Every endpoint acts on the caller's own account (identity from
 * the access token) and answers 200 with a body.
 *
 * - GET  /api/users/me/mfa                              status
 * - POST /api/users/me/mfa/setup                        start (or continue) enrolment: secret and otpauth URI
 * - POST /api/users/me/mfa/enable                       confirm with a code: ten recovery codes
 * - POST /api/users/me/mfa/disable                      password plus code or recovery code
 * - POST /api/users/me/mfa/recovery-codes/regenerate    new recovery codes with a code
 */
@RestController
@RequestMapping("/api/users/me/mfa")
@RequiredArgsConstructor
@Tag(name = "Two-factor authentication", description = "TOTP two-factor authentication of the signed-in account: "
        + "enrolment, recovery codes and turning it off")
public class MfaController {

    private final MfaAccountService mfaAccountService;

    @GetMapping
    @Operation(summary = "Get my two-factor authentication status",
            description = "Any signed-in user, own account only. No side effects.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status of the caller's account"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<MfaStatusResponse> status() {
        return ResponseEntity.ok(mfaAccountService.myStatus());
    }

    @PostMapping("/setup")
    @Operation(summary = "Start two-factor setup and get the secret",
            description = "Any signed-in user, own account only. The first call stores a new encrypted secret as a "
                    + "pending enrolment; later calls return the same secret until two-factor authentication is "
                    + "turned on or reset. Two-factor authentication stays off until /enable succeeds. No body.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Secret and otpauth URI of the pending enrolment"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Two-factor authentication is already turned on",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<MfaSetupResponse> setup() {
        return ResponseEntity.ok(mfaAccountService.startSetup());
    }

    @PostMapping("/enable")
    @Operation(summary = "Turn on two-factor authentication",
            description = "Any signed-in user, own account only. Confirms the pending setup with a current code, turns "
                    + "two-factor authentication on and returns ten one-time recovery codes that are never shown "
                    + "again. Existing sessions stay signed in. Audited MFA_ENABLED (MFA_FAILURE on a wrong code). "
                    + "Shares the auth-mfa limit (5 per 5 minutes) of the account.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Two-factor authentication is on; recovery codes"),
            @ApiResponse(responseCode = "400", description = "code is not 6 digits (code VALIDATION_FAILED), or the "
                    + "code is wrong or already used (code MFA_CODE_INVALID)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Already turned on, or setup was not started",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "429", description = "Too many attempts (code RATE_LIMITED)",
                    headers = @Header(name = "Retry-After", description = "Seconds until the next attempt is allowed",
                            schema = @Schema(type = "integer")),
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<MfaRecoveryCodesResponse> enable(@Valid @RequestBody MfaCodeRequest request) {
        return ResponseEntity.ok(mfaAccountService.enable(request.getCode()));
    }

    @PostMapping("/disable")
    @Operation(summary = "Turn off two-factor authentication",
            description = "Signed-in users whose role does not require two-factor authentication, own account only. "
                    + "Needs the current password plus a current code or one unused recovery code. Deletes the "
                    + "secret, all recovery codes and any pending sign-in challenge; sessions stay signed in. "
                    + "Audited MFA_DISABLED (MFA_FAILURE on a wrong password or code). Shares the auth-mfa limit.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Two-factor authentication is off"),
            @ApiResponse(responseCode = "400", description = "Invalid body or both/neither of code and recoveryCode "
                    + "(code VALIDATION_FAILED), wrong password, or wrong or used code (code MFA_CODE_INVALID)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "The role requires two-factor authentication (code "
                    + "MFA_REQUIRED), or it is not turned on",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "429", description = "Too many attempts (code RATE_LIMITED)",
                    headers = @Header(name = "Retry-After", description = "Seconds until the next attempt is allowed",
                            schema = @Schema(type = "integer")),
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<MfaStatusResponse> disable(@Valid @RequestBody MfaDisableRequest request) {
        return ResponseEntity.ok(mfaAccountService.disable(request));
    }

    @PostMapping("/recovery-codes/regenerate")
    @Operation(summary = "Replace my recovery codes",
            description = "Signed-in users with two-factor authentication on, own account only. Needs a current code "
                    + "(a recovery code is not accepted). Replaces all recovery codes, used and unused, with ten new "
                    + "ones shown once. Audited MFA_RECOVERY_CODES_REGENERATED (MFA_FAILURE on a wrong code). Shares "
                    + "the auth-mfa limit.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ten new recovery codes"),
            @ApiResponse(responseCode = "400", description = "code is not 6 digits (code VALIDATION_FAILED), or the "
                    + "code is wrong or already used (code MFA_CODE_INVALID)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Two-factor authentication is not turned on",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "429", description = "Too many attempts (code RATE_LIMITED)",
                    headers = @Header(name = "Retry-After", description = "Seconds until the next attempt is allowed",
                            schema = @Schema(type = "integer")),
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<MfaRecoveryCodesResponse> regenerateRecoveryCodes(@Valid @RequestBody MfaCodeRequest request) {
        return ResponseEntity.ok(mfaAccountService.regenerateRecoveryCodes(request.getCode()));
    }
}
