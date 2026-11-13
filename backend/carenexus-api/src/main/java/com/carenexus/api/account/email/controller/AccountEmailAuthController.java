package com.carenexus.api.account.email.controller;

import com.carenexus.api.account.email.dto.request.EmailTokenRequest;
import com.carenexus.api.account.email.dto.request.ResendVerificationRequest;
import com.carenexus.api.account.email.dto.response.AccountMessageResponse;
import com.carenexus.api.account.email.dto.response.EmailChangeConfirmResponse;
import com.carenexus.api.account.email.service.EmailChangeService;
import com.carenexus.api.account.email.service.EmailVerificationService;
import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.ratelimit.RateLimitGuard;
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
 * Public steps of email verification and email change: the one-time token from the emailed link (or, for a resend,
 * just an address) is all the caller sends. An Authorization header is ignored.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Account email", description = "Email verification and confirmed change of the sign-in email")
public class AccountEmailAuthController {

    static final String BUCKET_VERIFY_IP = "auth-verify-ip";

    private final EmailVerificationService emailVerificationService;
    private final EmailChangeService emailChangeService;
    private final RateLimitGuard rateLimitGuard;

    @PostMapping("/email/verify")
    @SecurityRequirements
    @Operation(summary = "Confirm an email address with the emailed token",
            description = "Public; the token is the credential and works once. Stamps the account's email as "
                    + "verified, invalidates its other verification links and audits EMAIL_VERIFIED. Returns no "
                    + "session: the user signs in afterwards. Works for an account awaiting clinician approval.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Email verified"),
            @ApiResponse(responseCode = "400", description = "Token blank or too long (code VALIDATION_FAILED), or "
                    + "unknown, used, superseded, expired, issued for a previous address, or the account is closed "
                    + "(code TOKEN_INVALID)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<AccountMessageResponse> verify(@Valid @RequestBody EmailTokenRequest request) {
        emailVerificationService.verify(request.getToken());
        return ResponseEntity.ok(new AccountMessageResponse(EmailVerificationService.VERIFIED_MESSAGE));
    }

    @PostMapping("/email/resend")
    @SecurityRequirements
    @Operation(summary = "Send a new email verification link",
            description = "Public. The answer is the same whether or not the address belongs to an account. A link "
                    + "is sent only to an account that exists, is not verified and is not closed; earlier links of "
                    + "that account stop working. Limited to 20 requests per hour per client address.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Generic confirmation"),
            @ApiResponse(responseCode = "400", description = "Email blank, malformed or longer than 150 characters "
                    + "(code VALIDATION_FAILED)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "429", description = "Too many requests from this address (code RATE_LIMITED)",
                    headers = @Header(name = "Retry-After", description = "Seconds until the next attempt is allowed",
                            schema = @Schema(type = "integer")),
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<AccountMessageResponse> resend(@Valid @RequestBody ResendVerificationRequest request,
                                                         HttpServletRequest httpRequest) {
        // Every well-formed request counts, before any account lookup. getRemoteAddr only (see AuthController).
        rateLimitGuard.check(BUCKET_VERIFY_IP, httpRequest.getRemoteAddr());
        emailVerificationService.resend(request.getEmail());
        return ResponseEntity.ok(new AccountMessageResponse(EmailVerificationService.RESEND_MESSAGE));
    }

    @PostMapping("/email-change/confirm")
    @SecurityRequirements
    @Operation(summary = "Apply a pending email change with the emailed token",
            description = "Public; the token sent to the new address is the credential and works once. Changes the "
                    + "account's sign-in email, marks it verified, ends every session of the account (the user signs "
                    + "in again with the new address), audits EMAIL_CHANGE and notifies the old address.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Email changed"),
            @ApiResponse(responseCode = "400", description = "Token blank or too long (code VALIDATION_FAILED), or "
                    + "unknown, used, cancelled, replaced, expired, or the account is inactive or closed "
                    + "(code TOKEN_INVALID)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Another account took the address after the request "
                    + "was made; nothing changed",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<EmailChangeConfirmResponse> confirmEmailChange(@Valid @RequestBody EmailTokenRequest request) {
        return ResponseEntity.ok(emailChangeService.confirm(request.getToken()));
    }
}
