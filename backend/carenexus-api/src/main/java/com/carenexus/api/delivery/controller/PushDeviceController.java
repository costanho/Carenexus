package com.carenexus.api.delivery.controller;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.delivery.dto.request.RegisterPushDeviceRequest;
import com.carenexus.api.delivery.dto.response.PushDeviceResponse;
import com.carenexus.api.delivery.service.PushDeviceService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Push device tokens of the signed-in user (any role). A user manages only their own devices.
 *
 * - POST   /api/notifications/devices           201 new token, 200 refreshed or re-assigned token
 * - DELETE /api/notifications/devices/{token}   204; 404 when the token is not the caller's
 */
@RestController
@RequestMapping("/api/notifications/devices")
@RequiredArgsConstructor
@Tag(name = "Push devices", description = "Push tokens of the signed-in user's own devices")
public class PushDeviceController {

    private final PushDeviceService pushDeviceService;

    @PostMapping
    @Operation(summary = "Register or refresh a push device token",
            description = "Any signed-in user, for their own devices. Upsert by token: a new token is stored (201); "
                    + "a token already known is refreshed, or moved to the caller when it was registered to another "
                    + "account (200). A user keeps the 10 most recently seen devices. The call is naturally "
                    + "idempotent, so an Idempotency-Key header is accepted and ignored. Nothing is audited.",
            parameters = @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = false,
                    description = "Accepted and ignored: repeating the call simply answers 200",
                    schema = @Schema(type = "string")))
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The token was registered"),
            @ApiResponse(responseCode = "200", description = "The token was already registered and has been refreshed "
                    + "or re-assigned to the caller"),
            @ApiResponse(responseCode = "400", description = "Malformed body, or platform / token invalid (code VALIDATION_FAILED)",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "415", description = "The body is not application/json",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<PushDeviceResponse> register(@Valid @RequestBody RegisterPushDeviceRequest request) {
        PushDeviceService.Registration registration = pushDeviceService.register(request);
        return ResponseEntity.status(registration.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(registration.device());
    }

    @DeleteMapping("/{token}")
    @Operation(summary = "Remove one of the caller's push device tokens",
            description = "Any signed-in user, for their own devices; call it on sign-out. The row is deleted. A token "
                    + "that is not registered, or is registered to another user, answers 404 (the two cases are "
                    + "indistinguishable on purpose). Nothing is audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "The token was removed"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "The token is not registered to the caller",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> remove(
            @Parameter(description = "The token exactly as registered, URL-encoded ([ and ] as %5B and %5D)")
            @PathVariable String token) {
        pushDeviceService.remove(token);
        return ResponseEntity.noContent().build();
    }
}
