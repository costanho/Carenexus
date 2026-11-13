package com.carenexus.api.account.password.controller;

import com.carenexus.api.account.password.dto.request.ForgotPasswordRequest;
import com.carenexus.api.account.password.dto.request.ResetPasswordRequest;
import com.carenexus.api.account.password.dto.response.PasswordMessageResponse;
import com.carenexus.api.account.password.service.PasswordRecoveryService;
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

@RestController
@RequestMapping("/api/auth/password")
@RequiredArgsConstructor
@Tag(name = "Account recovery", description = "Forgot password and set a new password with an emailed link. Public "
        + "endpoints: the emailed token is the credential.")
public class PasswordRecoveryController {

    private final PasswordRecoveryService passwordRecoveryService;

    /**
     * POST /api/auth/password/forgot  body {email, humanVerificationToken?}
     * Always the same 200 answer for a well-formed request, whether or not the account exists.
     */
    @PostMapping("/forgot")
    @SecurityRequirements
    @Operation(summary = "Ask for a password reset link by email",
            description = "Public. Answers 200 with the same text whether or not an account exists. An active, open "
                    + "account that has a password gets an email with a link to /auth/reset-password?token=...; "
                    + "earlier links of that account stop working. Unknown, inactive, closed and managed accounts get "
                    + "nothing. Human verification runs first; then 10 requests per hour per client address and 3 per "
                    + "hour per email are allowed.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Request accepted (fixed text)"),
            @ApiResponse(responseCode = "400", description = "Invalid email or verification token (code "
                    + "VALIDATION_FAILED), malformed body, or human verification refused (code HUMAN_VERIFICATION_FAILED)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "429", description = "Too many requests from this address or for this email "
                    + "(code RATE_LIMITED)",
                    headers = @Header(name = "Retry-After", description = "Seconds until the next attempt is allowed",
                            schema = @Schema(type = "integer")),
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PasswordMessageResponse> forgot(@Valid @RequestBody ForgotPasswordRequest request,
                                                          HttpServletRequest httpRequest) {
        String ip = httpRequest.getRemoteAddr();
        passwordRecoveryService.guardForgotRequest(request, ip);
        return ResponseEntity.ok(new PasswordMessageResponse(passwordRecoveryService.requestReset(request, ip)));
    }

    /**
     * POST /api/auth/password/reset  body {token, newPassword}
     * Sets the new password, ends every session and returns a fixed text (no tokens).
     */
    @PostMapping("/reset")
    @SecurityRequirements
    @Operation(summary = "Set a new password with an emailed token",
            description = "Public; the token from the emailed link is the credential and works once. Also sets the "
                    + "first password of an invited account. Ends every session of the account on every device, "
                    + "clears the forced-change flag and emails a password-changed notice. Returns no tokens: the "
                    + "user signs in afterwards.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Password set (fixed text)"),
            @ApiResponse(responseCode = "400", description = "A field is invalid or the new password is too weak "
                    + "(code VALIDATION_FAILED), the body is malformed, or the token is unknown, used, superseded, "
                    + "expired or belongs to a closed account (code PASSWORD_RESET_TOKEN_INVALID)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PasswordMessageResponse> reset(@Valid @RequestBody ResetPasswordRequest request) {
        return ResponseEntity.ok(new PasswordMessageResponse(passwordRecoveryService.resetPassword(request)));
    }
}
