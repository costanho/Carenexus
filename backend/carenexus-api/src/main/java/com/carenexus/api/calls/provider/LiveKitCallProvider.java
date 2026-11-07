package com.carenexus.api.calls.provider;

import com.carenexus.api.calls.config.LiveKitProperties;
import io.livekit.server.AccessToken;
import io.livekit.server.CanPublish;
import io.livekit.server.CanPublishData;
import io.livekit.server.CanPublishSources;
import io.livekit.server.CanSubscribe;
import io.livekit.server.CanUpdateOwnMetadata;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomName;
import io.livekit.server.RoomServiceClient;
import io.livekit.server.WebhookReceiver;
import livekit.LivekitWebhook;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.util.Date;
import java.util.List;

/**
 * LiveKit implementation (official JVM server SDK, io.livekit:livekit-server).
 * Error messages stored for jobs contain only the operation, HTTP status and exception class.
 */
public class LiveKitCallProvider implements CallProvider {

    private final LiveKitProperties properties;
    private final RoomServiceClient roomService;
    private final WebhookReceiver webhookReceiver;

    public LiveKitCallProvider(LiveKitProperties properties) {
        this.properties = properties;
        this.roomService = RoomServiceClient.createClient(properties.getUrl().trim(), properties.getApiKey(),
                properties.getApiSecret());
        this.webhookReceiver = new WebhookReceiver(properties.getApiKey(), properties.getApiSecret());
    }

    @Override
    public boolean isConfigured() {
        return properties.isConfigured();
    }

    @Override
    public String clientUrl() {
        return properties.effectiveClientUrl();
    }

    @Override
    public void createRoom(String roomName, int emptyTimeoutSeconds, int maxParticipants) {
        execute("createRoom", roomService.createRoom(roomName, emptyTimeoutSeconds, maxParticipants), false);
    }

    @Override
    public void deleteRoom(String roomName) {
        execute("deleteRoom", roomService.deleteRoom(roomName), true);
    }

    @Override
    public void removeParticipant(String roomName, String identity) {
        execute("removeParticipant", roomService.removeParticipant(roomName, identity), true);
    }

    @Override
    public IssuedToken issueToken(TokenRequest request) {
        AccessToken token = new AccessToken(properties.getApiKey(), properties.getApiSecret());
        token.setIdentity(request.identity());
        token.setName(request.displayName());
        token.setMetadata(request.metadataJson());
        token.setExpiration(Date.from(request.expiresAt()));
        token.addGrants(
                new RoomJoin(true),
                new RoomName(request.roomName()),
                new CanPublish(true),
                new CanSubscribe(true),
                // No data channel: LiveKit's built-in chat would use it, and calls have no temporary chat.
                new CanPublishData(false),
                // Camera and microphone only (no screen share).
                new CanPublishSources(List.of("camera", "microphone")),
                new CanUpdateOwnMetadata(false));
        return new IssuedToken(token.toJwt(), request.expiresAt());
    }

    @Override
    public WebhookEventData parseWebhook(String rawBody, String authorizationHeader) {
        LivekitWebhook.WebhookEvent event;
        try {
            event = webhookReceiver.receive(rawBody, authorizationHeader);
        } catch (RuntimeException e) {
            throw new InvalidWebhookSignatureException();
        }
        String room = event.hasRoom() ? event.getRoom().getName() : null;
        String identity = event.hasParticipant() ? event.getParticipant().getIdentity() : null;
        return new WebhookEventData(event.getId(), event.getEvent(), event.getCreatedAt(), room, identity);
    }

    private static <T> void execute(String operation, Call<T> call, boolean notFoundIsSuccess) {
        Response<T> response;
        try {
            response = call.execute();
        } catch (IOException e) {
            throw new CallProviderException("LiveKit " + operation + " failed: " + e.getClass().getSimpleName(), true);
        } catch (RuntimeException e) {
            throw new CallProviderException("LiveKit " + operation + " failed: " + e.getClass().getSimpleName(), true);
        }
        if (response.isSuccessful()) {
            return;
        }
        int code = response.code();
        // Twirp maps not_found to HTTP 404.
        if (notFoundIsSuccess && code == 404) {
            return;
        }
        // 4xx other than 404/429 will not succeed on retry (bad credentials, bad request), but keep retrying with
        // backoff anyway so a configuration fix takes effect without manual intervention.
        throw new CallProviderException("LiveKit " + operation + " failed: HTTP " + code, true);
    }
}
