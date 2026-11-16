package com.carenexus.api.account.password.controller;

import com.carenexus.api.account.password.dto.request.ChangePasswordRequest;
import com.carenexus.api.account.password.dto.response.PasswordMessageResponse;
import com.carenexus.api.account.password.service.PasswordChangeService;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/me")
@RequiredArgsConstructor
@Tag(name = "Current user", description = "The signed-in account")
public class PasswordChangeController {

    private final PasswordChangeService passwordChangeService;

    /**
     * POST /api/users/me/password  body {currentPassword, newPassword, keepSessionId?}
     */
    @PostMapping("/password")
    @Operation(summary = "Change the signed-in user's password",
            description = "Any signed-in user, for their own account only. Verifies the current password, stores the "
                    + "new one, clears the forced-change flag, cancels unused reset links and ends every other session "
                    + "(keepSessionId names the session that stays signed in; without it every session ends, the "
                    + "calling one included). Emails a password-changed notice. Shares the bucket password-change "
                    + "(5 attempts per 15 minutes per account) with the other password-confirming forms; a successful "
                    + "change resets it.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Password changed (fixed text)"),
            @ApiResponse(responseCode = "400", description = "A field is invalid or the new password is too weak "
                    + "(code VALIDATION_FAILED), the body is malformed, the current password is wrong (code "
                    + "CURRENT_PASSWORD_INCORRECT) or the new password equals it (code PASSWORD_UNCHANGED)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "429", description = "Too many attempts for this account (code RATE_LIMITED)",
                    headers = @Header(name = "Retry-After", description = "Seconds until the next attempt is allowed",
                            schema = @Schema(type = "integer")),
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PasswordMessageResponse> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        return ResponseEntity.ok(new PasswordMessageResponse(passwordChangeService.changeOwnPassword(request)));
    }
}
