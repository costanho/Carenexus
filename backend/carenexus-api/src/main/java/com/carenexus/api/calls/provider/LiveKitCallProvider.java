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
import lombok.extern.slf4j.Slf4j;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * LiveKit implementation (official JVM server SDK, io.livekit:livekit-server).
 * Error messages stored for jobs contain only the operation, HTTP status and exception class.
 *
 * Room-service calls run on the call job worker with explicit HTTP timeouts (OkHttp's defaults leave the whole call
 * unbounded): connecting gives up after {@value #CONNECT_TIMEOUT_SECONDS} s, a read or write stalls for at most
 * {@value #READ_WRITE_TIMEOUT_SECONDS} s, and a whole call is abandoned after {@value #CALL_TIMEOUT_SECONDS} s. A
 * timeout is an IOException and is retried with backoff by the job worker like any other transport failure.
 */
@Slf4j
public class LiveKitCallProvider implements CallProvider {

    static final long CONNECT_TIMEOUT_SECONDS = 5;
    static final long READ_WRITE_TIMEOUT_SECONDS = 10;
    static final long CALL_TIMEOUT_SECONDS = 20;

    private final LiveKitProperties properties;
    private final RoomServiceClient roomService;
    private final WebhookReceiver webhookReceiver;

    public LiveKitCallProvider(LiveKitProperties properties) {
        this.properties = properties;
        this.roomService = createRoomService(properties.getUrl().trim(), properties.getApiKey(), properties.getApiSecret());
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

    /**
     * The SDK's {@code RoomServiceClient.create(host, apiKey, secret, logging, Consumer<OkHttpClient.Builder>)} with a
     * configurator that sets the timeouts above.
     *
     * OkHttp is only a runtime dependency of the SDK (it is not on this module's compile classpath), so the factory
     * and the builder methods are reached reflectively; both are public, stable API ({@code connectTimeout},
     * {@code readTimeout}, {@code writeTimeout} and {@code callTimeout(long, TimeUnit)} exist in OkHttp 3 to 5). Should
     * either ever be missing, the client is built with the SDK defaults (10 s connect/read/write, no overall limit)
     * and a warning is logged, so calls keep working.
     */
    static RoomServiceClient createRoomService(String url, String apiKey, String apiSecret) {
        try {
            Method create = RoomServiceClient.class.getMethod("create",
                    String.class, String.class, String.class, boolean.class, Consumer.class);
            Consumer<Object> timeouts = LiveKitCallProvider::applyTimeouts;
            return (RoomServiceClient) create.invoke(null, url, apiKey, apiSecret, false, timeouts);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Throwable cause = e instanceof InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : e;
            log.warn("LiveKit HTTP timeouts could not be set ({}); the SDK default timeouts apply",
                    cause.getClass().getSimpleName());
            return RoomServiceClient.createClient(url, apiKey, apiSecret);
        }
    }

    /** Sets the timeouts on an {@code okhttp3.OkHttpClient.Builder}. */
    static void applyTimeouts(Object okHttpBuilder) {
        setTimeout(okHttpBuilder, "connectTimeout", CONNECT_TIMEOUT_SECONDS);
        setTimeout(okHttpBuilder, "readTimeout", READ_WRITE_TIMEOUT_SECONDS);
        setTimeout(okHttpBuilder, "writeTimeout", READ_WRITE_TIMEOUT_SECONDS);
        setTimeout(okHttpBuilder, "callTimeout", CALL_TIMEOUT_SECONDS);
    }

    private static void setTimeout(Object builder, String method, long seconds) {
        try {
            builder.getClass().getMethod(method, long.class, TimeUnit.class).invoke(builder, seconds, TimeUnit.SECONDS);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("OkHttpClient.Builder." + method + " is not available", e);
        }
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
