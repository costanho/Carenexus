package com.carenexus.api.common.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carenexus.api.calls.config.LiveKitProperties;
import com.carenexus.api.calls.provider.CallProviderException;
import com.carenexus.api.calls.provider.LiveKitCallProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The LiveKit room-service client is built with explicit HTTP timeouts (LiveKitCallProvider configures the SDK's
 * OkHttp builder): construction takes the configured path without the fallback warning, and a server that accepts
 * the connection but never answers makes the call fail as a retryable provider error instead of hanging the job
 * worker.
 */
class LiveKitHttpTimeoutsTest {

    private final Logger providerLogger = (Logger) LoggerFactory.getLogger(LiveKitCallProvider.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        appender.start();
        providerLogger.addAppender(appender);
    }

    @AfterEach
    void releaseLogs() {
        providerLogger.detachAppender(appender);
        appender.stop();
    }

    private static LiveKitProperties properties(String url) {
        LiveKitProperties properties = new LiveKitProperties();
        properties.setEnabled(true);
        properties.setUrl(url);
        properties.setClientUrl("ws://localhost:7880");
        properties.setApiKey("devkey");
        properties.setApiSecret("test-only-livekit-secret-0123456789abcdef");
        return properties;
    }

    @Test
    void theClientIsBuiltWithTheTimeouts_withoutFallingBackToTheSdkDefaults() {
        new LiveKitCallProvider(properties("http://localhost:7880"));
        assertTrue(appender.list.stream().noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN)),
                "the timeout configurator was applied: " + appender.list);
    }

    @Test
    void aServerThatNeverAnswers_failsTheCallAsRetryable_withinTheReadTimeout() throws Exception {
        List<Socket> accepted = Collections.synchronizedList(new ArrayList<>());
        try (ServerSocket silent = new ServerSocket(0)) {
            Thread acceptor = new Thread(() -> {
                try {
                    while (!silent.isClosed()) {
                        accepted.add(silent.accept());   // keep the connection open, never write a response
                    }
                } catch (Exception closed) {
                    // the test is over
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            LiveKitCallProvider provider = new LiveKitCallProvider(properties("http://127.0.0.1:" + silent.getLocalPort()));
            long started = System.nanoTime();
            CallProviderException failure = assertThrows(CallProviderException.class,
                    () -> provider.createRoom("observability-timeout-room", 60, 2));
            Duration took = Duration.ofNanos(System.nanoTime() - started);

            assertTrue(failure.getMessage().startsWith("LiveKit createRoom failed: "), failure.getMessage());
            assertTrue(took.compareTo(Duration.ofSeconds(25)) < 0, "the call was cut off by a timeout, took " + took);
        } finally {
            synchronized (accepted) {
                for (Socket socket : accepted) {
                    socket.close();
                }
            }
        }
    }
}
