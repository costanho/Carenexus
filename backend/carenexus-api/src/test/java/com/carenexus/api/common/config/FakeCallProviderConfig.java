package com.carenexus.api.common.config;

import com.carenexus.api.calls.provider.CallProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Replaces the real LiveKit adapter in integration tests. It is always "configured", never talks to a network
 * and records every provider call so tests can assert what the job worker did.
 */
@TestConfiguration
public class FakeCallProviderConfig {

    @Bean
    @Primary
    public FakeCallProvider fakeCallProvider() {
        return new FakeCallProvider();
    }

    public static class FakeCallProvider implements CallProvider {

        public final List<String> createdRooms = new CopyOnWriteArrayList<>();
        public final List<String> deletedRooms = new CopyOnWriteArrayList<>();
        public final List<String> removedIdentities = new CopyOnWriteArrayList<>();
        public final List<TokenRequest> issuedTokens = new CopyOnWriteArrayList<>();

        public void reset() {
            createdRooms.clear();
            deletedRooms.clear();
            removedIdentities.clear();
            issuedTokens.clear();
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public String clientUrl() {
            return "ws://fake-livekit.test:7880";
        }

        @Override
        public void createRoom(String roomName, int emptyTimeoutSeconds, int maxParticipants) {
            createdRooms.add(roomName);
        }

        @Override
        public void deleteRoom(String roomName) {
            deletedRooms.add(roomName);
        }

        @Override
        public void removeParticipant(String roomName, String identity) {
            removedIdentities.add(identity);
        }

        @Override
        public IssuedToken issueToken(TokenRequest request) {
            issuedTokens.add(request);
            return new IssuedToken("fake-token-" + UUID.randomUUID(), request.expiresAt());
        }

        @Override
        public WebhookEventData parseWebhook(String rawBody, String authorizationHeader) {
            return new WebhookEventData("evt-" + UUID.randomUUID(), "room_started", Instant.now().getEpochSecond(), null, null);
        }
    }
}
