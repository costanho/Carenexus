package com.carenexus.api.account.mfa.controller;

import com.carenexus.api.account.mfa.dto.response.MfaStatusResponse;
import com.carenexus.api.account.mfa.service.MfaAccountService;
import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.dto.ValidationPatterns;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Two-factor authentication of any account, for administrators (route rule {@code /api/admin/**} ADMIN plus the
 * service check).
 *
 * - GET  /api/admin/users/{systemId}/mfa         status of the account
 * - POST /api/admin/users/{systemId}/mfa/reset   remove the enrolment (idempotent, audited MFA_RESET)
 */
@RestController
@RequestMapping("/api/admin/users/{systemId}/mfa")
@RequiredArgsConstructor
@Validated
@Tag(name = "Admin: users", description = "User administration")
public class MfaAdminController {

    static final String SYSTEM_ID_MESSAGE = "must be a system id such as PAT-00001";

    private final MfaAccountService mfaAccountService;

    @GetMapping
    @Operation(summary = "Get a user's two-factor authentication status",
            description = "ADMIN only. required follows the target's role. No side effects, no audit row.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status of the account"),
            @ApiResponse(responseCode = "400", description = "Malformed systemId (code VALIDATION_FAILED)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No user with that system id",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<MfaStatusResponse> status(
            @Parameter(description = "System id of the account", example = "ADM-00001")
            @PathVariable @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = SYSTEM_ID_MESSAGE) String systemId) {
        return ResponseEntity.ok(mfaAccountService.statusForAdmin(systemId));
    }

    @PostMapping("/reset")
    @Operation(summary = "Reset a user's two-factor authentication",
            description = "ADMIN only, own account included. Deletes the secret (on or pending), all recovery codes "
                    + "and any pending sign-in challenge of the account. Idempotent. Sessions are not revoked. If the "
                    + "role requires two-factor authentication, the next sign-in asks to set it up again. Writes an "
                    + "MFA_RESET audit row on every call. No body.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status of the account after the reset"),
            @ApiResponse(responseCode = "400", description = "Malformed systemId (code VALIDATION_FAILED)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "The caller is not an administrator",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No user with that system id",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<MfaStatusResponse> reset(
            @Parameter(description = "System id of the account", example = "ADM-00001")
            @PathVariable @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = SYSTEM_ID_MESSAGE) String systemId) {
        return ResponseEntity.ok(mfaAccountService.resetForAdmin(systemId));
    }
}
