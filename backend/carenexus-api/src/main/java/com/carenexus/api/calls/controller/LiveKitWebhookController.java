package com.carenexus.api.calls.controller;

import com.carenexus.api.calls.config.LiveKitProperties;
import com.carenexus.api.calls.provider.CallProvider;
import com.carenexus.api.calls.provider.InvalidWebhookSignatureException;
import com.carenexus.api.calls.service.LiveKitWebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * LiveKit webhook receiver (public path, authenticated by the signed Authorization header).
 * The body is read raw (LiveKit sends application/webhook+json) so the signature check sees the exact bytes.
 * Neither the body nor the Authorization header is ever logged.
 */
@Slf4j
@RestController
@RequestMapping("/api/integrations/livekit")
@RequiredArgsConstructor
@Tag(name = "Calls: provider webhook", description = "Signed room and participant events sent by the LiveKit server")
public class LiveKitWebhookController {

    static final int MAX_BODY_BYTES = 64 * 1024;

    private final LiveKitProperties liveKitProperties;
    private final CallProvider callProvider;
    private final LiveKitWebhookService webhookService;

    @PostMapping(value = "/webhook", consumes = MediaType.ALL_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirements
    @Operation(summary = "Receive a LiveKit webhook event",
            description = "Called by the LiveKit server, not by users: no bearer token. The Authorization header "
                    + "carries LiveKit's signed webhook token over the raw body (at most 64 KB). Idempotent by "
                    + "event id; participant_joined and participant_left update the presence of the matching call "
                    + "participant, other events are recorded and ignored. Answers 404 while "
                    + "webhooks are disabled (LIVEKIT_WEBHOOK_ENABLED) or no LiveKit server is configured. Error "
                    + "bodies are {\"message\": \"...\"} only, not the ErrorResponse envelope.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The event was received (empty JSON object)"),
            @ApiResponse(responseCode = "400", description = "Body larger than 64 KB",
                    content = @Content(mediaType = "application/json", schema = @Schema(type = "object",
                            description = "{\"message\": \"Webhook body is too large\"}"))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid webhook signature",
                    content = @Content(mediaType = "application/json", schema = @Schema(type = "object",
                            description = "{\"message\": \"Invalid webhook signature\"}"))),
            @ApiResponse(responseCode = "404", description = "Webhooks are disabled or LiveKit is not configured",
                    content = @Content(mediaType = "application/json", schema = @Schema(type = "object",
                            description = "{\"message\": \"Not found\"}")))
    })
    public ResponseEntity<Map<String, Object>> receive(
            @RequestBody(required = false) String rawBody,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (!liveKitProperties.isWebhookEnabled() || !callProvider.isConfigured()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Not found"));
        }
        String body = rawBody == null ? "" : rawBody;
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            return ResponseEntity.badRequest().body(Map.of("message", "Webhook body is too large"));
        }
        CallProvider.WebhookEventData event;
        try {
            if (authorization == null || authorization.isBlank()) {
                throw new InvalidWebhookSignatureException();
            }
            event = callProvider.parseWebhook(body, authorization);
        } catch (InvalidWebhookSignatureException e) {
            log.warn("LiveKit webhook rejected: invalid signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Invalid webhook signature"));
        }
        webhookService.process(event, body);
        return ResponseEntity.ok(Map.of());
    }
}
