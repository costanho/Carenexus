package com.carenexus.api.account.email.controller;

import com.carenexus.api.account.email.dto.request.StartEmailChangeRequest;
import com.carenexus.api.account.email.dto.response.EmailChangeResponse;
import com.carenexus.api.account.email.service.EmailChangeService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's own email change. Identity comes from the access token; there is no variant for another
 * account.
 */
@RestController
@RequestMapping("/api/users/me/email-change")
@RequiredArgsConstructor
@Tag(name = "Account email", description = "Email verification and confirmed change of the sign-in email")
public class EmailChangeController {

    private final EmailChangeService emailChangeService;

    @PostMapping
    @Operation(summary = "Start a change of your sign-in email",
            description = "Any signed-in user, own account only. Requires the current password. Stores a pending "
                    + "request (replacing an earlier one), sends a confirmation link to the new address and a notice "
                    + "to the current one, and audits EMAIL_CHANGE_REQUESTED. The account changes only when the link "
                    + "is used. Shares the limit of 5 requests per 15 minutes with the password change.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Pending request stored"),
            @ApiResponse(responseCode = "400", description = "A field is invalid (code VALIDATION_FAILED), the "
                    + "current password is wrong (code INVALID_CURRENT_PASSWORD) or the address is the current one "
                    + "(code EMAIL_UNCHANGED)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Not signed in",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Another account already uses the address",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "429", description = "Too many attempts (code RATE_LIMITED)",
                    headers = @Header(name = "Retry-After", description = "Seconds until the next attempt is allowed",
                            schema = @Schema(type = "integer")),
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<EmailChangeResponse> requestChange(@Valid @RequestBody StartEmailChangeRequest request) {
        EmailChangeResponse response = emailChangeService.requestChange(request.getCurrentPassword(), request.getNewEmail());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @Operation(summary = "Get your pending email change",
            description = "Any signed-in user, own account only. 204 with an empty body when nothing is pending.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The pending request"),
            @ApiResponse(responseCode = "204", description = "No pending request", content = @Content),
            @ApiResponse(responseCode = "401", description = "Not signed in",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<EmailChangeResponse> pending() {
        return emailChangeService.pending()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @DeleteMapping
    @Operation(summary = "Cancel your pending email change",
            description = "Any signed-in user, own account only. The emailed link stops working at once. Idempotent: "
                    + "204 whether or not a request was pending. Audits EMAIL_CHANGE_CANCELLED when one was.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Nothing is pending any more", content = @Content),
            @ApiResponse(responseCode = "401", description = "Not signed in",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> cancel() {
        emailChangeService.cancel();
        return ResponseEntity.noContent().build();
    }
}
