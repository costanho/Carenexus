package com.carenexus.api.calls.provider;

import java.time.Instant;

/**
 * Video provider adapter. Implementations never log tokens, keys, the webhook Authorization header or
 * room names together with identities.
 */
public interface CallProvider {

    /** Provider is enabled and has url, api key and api secret. */
    boolean isConfigured();

    /** Browser-facing server URL (ws/wss). */
    String clientUrl();

    /** Create (or keep) a room. Throws {@link CallProviderException} on failure. */
    void createRoom(String roomName, int emptyTimeoutSeconds, int maxParticipants);

    /** Delete a room. A room that does not exist counts as success. */
    void deleteRoom(String roomName);

    /** Disconnect one participant. A participant/room that does not exist counts as success. */
    void removeParticipant(String roomName, String identity);

    /** Short-lived join token (data channel denied, camera/microphone only). */
    IssuedToken issueToken(TokenRequest request);

    /** Verify the signature and parse a webhook. Throws {@link InvalidWebhookSignatureException} when invalid. */
    WebhookEventData parseWebhook(String rawBody, String authorizationHeader);

    record TokenRequest(String roomName, String identity, String displayName, String metadataJson, Instant expiresAt) { }

    record IssuedToken(String token, Instant expiresAt) { }

    /**
     * Only what the API needs from a webhook.
     * @param createdAtEpochSeconds provider event time (0 when absent)
     */
    record WebhookEventData(String id, String event, long createdAtEpochSeconds, String roomName, String identity) { }
}
