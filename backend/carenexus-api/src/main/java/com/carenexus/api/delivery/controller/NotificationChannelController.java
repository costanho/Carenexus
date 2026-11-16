package com.carenexus.api.delivery.controller;

import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.delivery.dto.response.NotificationChannelsResponse;
import com.carenexus.api.delivery.service.NotificationChannelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Which external delivery channels (email, SMS, push) exist on this installation, for the settings screens.
 *
 * - GET /api/notifications/channels
 */
@RestController
@RequestMapping("/api/notifications/channels")
@RequiredArgsConstructor
@Tag(name = "Notification channels", description = "External delivery channels available on this installation")
public class NotificationChannelController {

    private final NotificationChannelService notificationChannelService;

    @GetMapping
    @Operation(summary = "List the external notification channels of this installation",
            description = "Any signed-in user. The answer describes the installation, not the caller, and changes "
                    + "only when the API is restarted with different configuration. A channel that is not available "
                    + "never sends; provider 'local' records messages on the server instead of sending them. The "
                    + "in-app channel is always on and is not listed. No side effects, nothing is audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "State of the email, SMS and push channels"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<NotificationChannelsResponse> channels() {
        return ResponseEntity.ok(notificationChannelService.channels());
    }
}
