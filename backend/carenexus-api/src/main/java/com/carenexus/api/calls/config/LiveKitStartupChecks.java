package com.carenexus.api.calls.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when LiveKit is enabled outside the local/docker/test profiles with the public dev secret
 * ("secret") or a secret shorter than 32 characters, or with the webhook off (the participant_joined webhook is
 * what removes a revoked/removed participant who reconnects with a still-valid token). Missing configuration
 * never fails startup.
 */
@Component
@RequiredArgsConstructor
public class LiveKitStartupChecks {

    static final int MIN_SECRET_LENGTH = 32;

    private final LiveKitProperties properties;
    private final Environment environment;

    @PostConstruct
    void check() {
        if (!properties.isEnabled() || environment.acceptsProfiles(Profiles.of("local", "docker", "test"))) {
            return;
        }
        String secret = properties.getApiSecret();
        if (secret == null || secret.isBlank()) {
            return; // not configured: calls are simply unavailable
        }
        if ("secret".equals(secret) || secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("carenexus.livekit.api-secret is a development value or shorter than "
                    + MIN_SECRET_LENGTH + " characters; set LIVEKIT_API_SECRET to the real secret");
        }
        if (!properties.isWebhookEnabled()) {
            throw new IllegalStateException("carenexus.livekit.webhook-enabled must be true when LiveKit is enabled;"
                    + " set LIVEKIT_WEBHOOK_ENABLED=true and point the LiveKit webhook at /api/integrations/livekit/webhook");
        }
    }
}
