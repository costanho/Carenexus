package com.carenexus.api.auth.controller;

import com.carenexus.api.auth.dto.request.LoginRequest;
import com.carenexus.api.auth.dto.request.RegisterRequest;
import com.carenexus.api.auth.dto.request.TokenRefreshRequest;
import com.carenexus.api.auth.dto.response.AuthResponse;
import com.carenexus.api.auth.service.AuthService;
import com.carenexus.api.common.config.UserContext;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Authentication", description = "Registration, sign-in, token refresh and sign-out. Public endpoints: "
        + "none of them needs an access token.")
public class AuthController {

    static final String BUCKET_REGISTER_IP = "auth-register-ip";
    static final String BUCKET_REFRESH_IP = "auth-refresh-ip";

    private final AuthService authService;
    private final UserContext userContext;
    private final RateLimitGuard rateLimitGuard;

    /**
     * POST /api/auth/register
     * Register a patient, doctor (pending verification) or proxy/caregiver account.
     * Returns the created user; no tokens (the client logs in afterwards).
     */
    @PostMapping("/register")
    @SecurityRequirements
    @Operation(summary = "Register a patient, doctor or caregiver account",
            description = "Public. Creates the account and its role row in one transaction and returns the user "
                    + "without tokens; the client signs in afterwards. A doctor is created pending verification and "
                    + "cannot sign in until an administrator approves it. Administrators cannot be registered here. "
                    + "Limited to 10 requests per hour per client address.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Account created; tokens, flags and challenge are null"),
            @ApiResponse(responseCode = "400", description = "A field is invalid (code VALIDATION_FAILED), the role "
                    + "is not allowed, the caregiver link is invalid, or a supplied legal version is not current "
                    + "(code LEGAL_ACCEPTANCE_REQUIRED)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Email, phone or licence number already registered",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "429", description = "Too many registrations from this address (code RATE_LIMITED)",
                    headers = @Header(name = "Retry-After", description = "Seconds until the next attempt is allowed",
                            schema = @Schema(type = "integer")),
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
                                                 HttpServletRequest httpRequest) {
        String ipAddress = getClientIpAddress(httpRequest);
        // Every request that passed validation counts, whatever its outcome.
        rateLimitGuard.check(BUCKET_REGISTER_IP, ipAddress);
        AuthResponse response = authService.register(request, ipAddress, httpRequest.getHeader("User-Agent"));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * POST /api/auth/login
     * Login with email and password.
     * Returns a session (access token + refresh token + flags) or a challenge to complete first.
     */
    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "Sign in with email and password",
            description = "Public; every role signs in here. Answers with a session (tokens, user, flags) or, when "
                    + "another step is required, with a challenge and no tokens (decide by challenge != null). "
                    + "Account-state refusals are returned only after the password was verified. Attempts are "
                    + "limited per client address, per email and address, and per email.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Session issued, or a challenge to complete first"),
            @ApiResponse(responseCode = "400", description = "Email or password missing (code VALIDATION_FAILED), or "
                    + "the account was deactivated (\"Account is inactive\")",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Unknown email or wrong password (same answer for both)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The account cannot sign in. Codes: ACCOUNT_CLOSED, "
                    + "ACCOUNT_PENDING_VERIFICATION, ACCOUNT_REJECTED, EMAIL_NOT_VERIFIED",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "429", description = "Too many sign-in attempts (code RATE_LIMITED)",
                    headers = @Header(name = "Retry-After", description = "Seconds until the next attempt is allowed",
                            schema = @Schema(type = "integer")),
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
        String ipAddress = getClientIpAddress(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");
        AuthResponse response = authService.login(request, ipAddress, userAgent);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/auth/refresh  body {refreshToken}
     * Returns the same shape as login. The refresh token is rotated: the response carries a NEW
     * refreshToken and the presented one stops working. Invalid/expired/reused tokens -> 401.
     */
    @PostMapping("/refresh")
    @SecurityRequirements
    @Operation(summary = "Exchange a refresh token for new tokens",
            description = "Public; the refresh token is the credential. The presented token stops working and the "
                    + "answer carries a new one for the same session (same sessionId), with the user and the flags "
                    + "recomputed. Presenting a token that was already exchanged ends every session of the user. "
                    + "Limited to 120 requests per 15 minutes per client address.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "New access token and refresh token"),
            @ApiResponse(responseCode = "400", description = "refreshToken is longer than 512 characters (code VALIDATION_FAILED)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Token missing, unknown, expired, revoked or reused, or "
                    + "the account is inactive; the client must sign in again",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "429", description = "Too many requests from this address (code RATE_LIMITED)",
                    headers = @Header(name = "Retry-After", description = "Seconds until the next attempt is allowed",
                            schema = @Schema(type = "integer")),
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody(required = false) TokenRefreshRequest request,
                                                HttpServletRequest httpRequest) {
        rateLimitGuard.check(BUCKET_REFRESH_IP, getClientIpAddress(httpRequest));
        AuthResponse response = authService.refreshToken(request);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/auth/logout  body {refreshToken} (optional). Public endpoint, always 200.
     * - With a refreshToken: revokes that session's refresh token (works even after the access token expired).
     * - Without a body but authenticated: revokes all of the user's refresh tokens (logout everywhere).
     */
    @PostMapping("/logout")
    @Operation(summary = "Sign out of one session or of every session",
            description = "Public and always 200. With a refreshToken in the body that session ends (this works "
                    + "with an expired access token; an unknown token is ignored). Without one, and with a valid "
                    + "bearer token, every session of the caller ends. Access tokens already issued keep working "
                    + "until they expire.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Done (empty body)"),
            @ApiResponse(responseCode = "400", description = "refreshToken is longer than 512 characters (code VALIDATION_FAILED)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> logout(
            @Valid @RequestBody(required = false) TokenRefreshRequest request,
            HttpServletRequest httpRequest) {
        String ipAddress = getClientIpAddress(httpRequest);
        String refreshToken = request == null ? null : request.getRefreshToken();
        if (refreshToken != null && !refreshToken.isBlank()) {
            authService.logoutRefreshToken(refreshToken, ipAddress);
        } else {
            String systemId = userContext.getCurrentUserSystemId();
            if (systemId != null) {
                authService.logout(systemId, ipAddress);
            }
        }
        return ResponseEntity.ok().build();
    }

    /**
     * Refresh-token failures are thrown as Spring AuthenticationExceptions with messages written in
     * AuthService (never token contents), so they are safe to echo. Returned as 401 in the shared error envelope.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationException(AuthenticationException ex, HttpServletRequest request) {
        log.warn("401 {} {} -> {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of(HttpStatus.UNAUTHORIZED, ex.getMessage(), request.getRequestURI()));
    }

    /**
     * Client IP for audit and rate limiting. X-Forwarded-For is NOT read here: proxy headers are only
     * honoured by Tomcat's RemoteIpValve for trusted (internal) proxies via server.forward-headers-strategy,
     * which already rewrites getRemoteAddr(). Reading the header directly would let any client spoof it.
     */
    private String getClientIpAddress(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
