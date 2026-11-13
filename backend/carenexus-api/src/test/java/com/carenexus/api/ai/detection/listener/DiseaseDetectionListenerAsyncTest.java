package com.carenexus.api.ai.detection.listener;

import com.carenexus.api.ai.detection.service.DetectionPipeline;
import com.carenexus.api.ai.detection.service.RiskAssessmentException;
import com.carenexus.api.ai.detection.service.ScreeningDispatcher;
import com.carenexus.api.ai.detection.service.ScreeningTrigger;
import com.carenexus.api.common.event.HealthMetricRecordedEvent;
import com.carenexus.api.common.event.SymptomLoggedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The automatic screening runs off the thread that committed the symptom log or reading: the listener returns while
 * a slow provider is still working, a failing screening is contained, and a full queue skips instead of blocking.
 */
class DiseaseDetectionListenerAsyncTest {

    private final DetectionPipeline pipeline = mock(DetectionPipeline.class);
    private final ScreeningDispatcher dispatcher = new ScreeningDispatcher();
    private final DiseaseDetectionListener listener = new DiseaseDetectionListener(pipeline, dispatcher);
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void stop() {
        release.countDown();
        dispatcher.awaitIdle(Duration.ofSeconds(5));
    }

    @Test
    void theListenerReturnsBeforeASlowScreeningFinishes_onAnotherThread() throws Exception {
        AtomicReference<String> screeningThread = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        when(pipeline.screenAutomatically(anyString(), any(ScreeningTrigger.class))).thenAnswer(invocation -> {
            screeningThread.set(Thread.currentThread().getName());
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Optional.empty();
        });

        long begin = System.nanoTime();
        listener.onSymptomLogged(new SymptomLoggedEvent(7, "PAT-00001", "PAT-00001", "SEVERE"));
        long elapsedMs = (System.nanoTime() - begin) / 1_000_000;

        assertTrue(elapsedMs < 1_000, "the listener waited " + elapsedMs + " ms");
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertNotEquals(Thread.currentThread().getName(), screeningThread.get());
        assertTrue(screeningThread.get().startsWith("risk-screening-"));
        assertFalse(dispatcher.awaitIdle(Duration.ofMillis(100)), "still screening");

        release.countDown();
        assertTrue(dispatcher.awaitIdle(Duration.ofSeconds(5)));
        verify(pipeline).screenAutomatically("PAT-00001", ScreeningTrigger.symptomLog(7));
    }

    @Test
    void failuresAreContained_andTheDispatcherStaysUsable() {
        when(pipeline.screenAutomatically(anyString(), any(ScreeningTrigger.class)))
                .thenThrow(new RiskAssessmentException(RiskAssessmentException.Reason.TIMEOUT, "slow"))
                .thenThrow(new IllegalStateException("boom"))
                .thenReturn(Optional.empty());

        listener.onSymptomLogged(new SymptomLoggedEvent(1, "PAT-00001", "PAT-00001", "MILD"));
        listener.onHealthMetricRecorded(new HealthMetricRecordedEvent(2, "PAT-00001", "PAT-00001", "SPO2", true,
                HealthMetricRecordedEvent.CRITICAL, "MANUAL"));
        listener.onSymptomLogged(new SymptomLoggedEvent(3, "PAT-00001", "PAT-00001", "MILD"));

        assertTrue(dispatcher.awaitIdle(Duration.ofSeconds(5)));
    }

    @Test
    void normalReadingsAndIncompleteEvents_areNotScreened() {
        listener.onHealthMetricRecorded(new HealthMetricRecordedEvent(2, "PAT-00001", "PAT-00001", "SPO2", false,
                HealthMetricRecordedEvent.NORMAL, "MANUAL"));
        listener.onSymptomLogged(new SymptomLoggedEvent(null, "PAT-00001", "PAT-00001", "MILD"));
        listener.onSymptomLogged(null);

        assertTrue(dispatcher.awaitIdle(Duration.ofSeconds(1)));
        verify(pipeline, never()).screenAutomatically(anyString(), any(ScreeningTrigger.class));
    }

    @Test
    void aFullQueue_skipsTheScreeningInsteadOfBlockingTheCaller() throws Exception {
        AtomicInteger ran = new AtomicInteger();
        // Two workers busy, the queue filled to capacity
        for (int i = 0; i < 2 + 200; i++) {
            assertTrue(dispatcher.dispatch(() -> {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                ran.incrementAndGet();
            }));
        }

        long begin = System.nanoTime();
        listener.onSymptomLogged(new SymptomLoggedEvent(9, "PAT-00001", "PAT-00001", "CRITICAL"));
        assertFalse(dispatcher.dispatch(ran::incrementAndGet), "the queue is full");
        assertTrue((System.nanoTime() - begin) / 1_000_000 < 1_000, "the caller was never blocked");

        release.countDown();
        assertTrue(dispatcher.awaitIdle(Duration.ofSeconds(10)));
        assertEquals(202, ran.get());
        verify(pipeline, never()).screenAutomatically(anyString(), any(ScreeningTrigger.class));
    }
}
