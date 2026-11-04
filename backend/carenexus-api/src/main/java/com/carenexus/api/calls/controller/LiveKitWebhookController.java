package com.carenexus.api.calls.controller;

import com.carenexus.api.calls.config.LiveKitProperties;
import com.carenexus.api.calls.provider.CallProvider;
import com.carenexus.api.calls.provider.InvalidWebhookSignatureException;
import com.carenexus.api.calls.service.LiveKitWebhookService;
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
public class LiveKitWebhookController {

    static final int MAX_BODY_BYTES = 64 * 1024;

    private final LiveKitProperties liveKitProperties;
    private final CallProvider callProvider;
    private final LiveKitWebhookService webhookService;

    @PostMapping(value = "/webhook", consumes = MediaType.ALL_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
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
