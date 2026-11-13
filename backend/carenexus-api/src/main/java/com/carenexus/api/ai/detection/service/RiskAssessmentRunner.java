package com.carenexus.api.ai.detection.service;

import com.carenexus.api.ai.detection.config.AiDetectionProperties;
import com.carenexus.api.ai.provider.DetectionInput;
import com.carenexus.api.ai.provider.DetectionResult;
import com.carenexus.api.ai.provider.RiskDetectionProvider;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Calls the {@link RiskDetectionProvider} with the assessment timeout ({@code carenexus.ai.assessment-timeout-ms}).
 * The provider runs on a small bounded worker pool, so a hanging provider costs the caller at most the timeout and
 * never blocks request threads indefinitely. Every way the call can go wrong ends in a
 * {@link RiskAssessmentException}; the caller then stores nothing.
 * <p>
 * The pool is private to this class (not a Spring {@code Executor} bean), so it does not replace Spring Boot's
 * auto-configured task executor.
 */
@Slf4j
@Component
public class RiskAssessmentRunner {

    static final int MAX_THREADS = 4;
    static final int QUEUE_CAPACITY = 32;

    private final RiskDetectionProvider provider;
    private final AiDetectionProperties properties;
    private final ThreadPoolExecutor executor;

    public RiskAssessmentRunner(RiskDetectionProvider provider, AiDetectionProperties properties) {
        this.provider = provider;
        this.properties = properties;
        this.executor = new ThreadPoolExecutor(MAX_THREADS, MAX_THREADS, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY), new WorkerThreadFactory(), new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
    }

    /** True when screening is switched on ({@link RiskDetectionProvider#isConfigured()}). */
    public boolean isEnabled() {
        return provider.isConfigured();
    }

    /** Version of the selected provider, for example {@code rules-v1}. */
    public String modelVersion() {
        return provider.modelVersion();
    }

    /**
     * Assesses one snapshot.
     *
     * @throws RiskAssessmentException DISABLED when screening is switched off, TIMEOUT when the provider did not answer
     *                                 in time, FAILED when it threw, returned nothing or could not be scheduled
     */
    public DetectionResult assess(DetectionInput input) {
        if (!provider.isConfigured()) {
            throw new RiskAssessmentException(RiskAssessmentException.Reason.DISABLED, "Risk screening is switched off");
        }
        long timeoutMs = properties.effectiveTimeoutMs();
        Future<DetectionResult> future;
        try {
            future = executor.submit(() -> provider.assess(input));
        } catch (RejectedExecutionException e) {
            throw new RiskAssessmentException(RiskAssessmentException.Reason.FAILED,
                    "Risk screening is overloaded", e);
        }
        try {
            DetectionResult result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (result == null) {
                throw new RiskAssessmentException(RiskAssessmentException.Reason.FAILED,
                        "Risk provider returned no result");
            }
            return result;
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new RiskAssessmentException(RiskAssessmentException.Reason.TIMEOUT,
                    "Risk provider did not answer within " + timeoutMs + " ms", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RiskAssessmentException(RiskAssessmentException.Reason.FAILED,
                    "Risk provider failed (" + cause.getClass().getSimpleName() + ")", cause);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new RiskAssessmentException(RiskAssessmentException.Reason.FAILED,
                    "Risk screening was interrupted", e);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private static final class WorkerThreadFactory implements ThreadFactory {

        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "risk-assessment-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
