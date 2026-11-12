package com.carenexus.api.calls.provider;

/**
 * Used when carenexus.livekit is not configured, so the application still starts. Every provider operation
 * fails with a non-retryable "LiveKit is not configured".
 */
public class DisabledCallProvider implements CallProvider {

    public static final String NOT_CONFIGURED = "LiveKit is not configured";

    @Override
    public boolean isConfigured() {
        return false;
    }

    @Override
    public String clientUrl() {
        return null;
    }

    @Override
    public void createRoom(String roomName, int emptyTimeoutSeconds, int maxParticipants) {
        throw notConfigured();
    }

    @Override
    public void deleteRoom(String roomName) {
        throw notConfigured();
    }

    @Override
    public void removeParticipant(String roomName, String identity) {
        throw notConfigured();
    }

    @Override
    public IssuedToken issueToken(TokenRequest request) {
        throw notConfigured();
    }

    @Override
    public WebhookEventData parseWebhook(String rawBody, String authorizationHeader) {
        throw notConfigured();
    }

    private static CallProviderException notConfigured() {
        return new CallProviderException(NOT_CONFIGURED, false);
    }
}
