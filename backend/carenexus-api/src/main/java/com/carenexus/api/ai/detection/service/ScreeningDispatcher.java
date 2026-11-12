package com.carenexus.api.ai.detection.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs automatic disease-risk screenings off the request thread, on a small bounded pool of its own.
 * <p>
 * The screening reads the patient's recent data in a short transaction, waits for the provider (bounded by the
 * assessment timeout of {@link RiskAssessmentRunner}) with no transaction and no database connection held, and then
 * writes the detection and its alert in a second short transaction ({@link DetectionPipeline#screenAutomatically}).
 * Running it on the thread of the request that saved the symptom log or reading would keep that request, and the
 * database connection still bound to it, waiting for a slow provider. Here the request returns as soon as its own
 * transaction has committed; the detection follows shortly after.
 * <p>
 * Bounded: {@value #THREADS} workers and at most {@value #QUEUE_CAPACITY} waiting screenings. When the queue is full
 * the screening is skipped with a WARN line (the next symptom log or reading screens the patient again, and a doctor
 * can run one by hand), so a burst never grows memory or threads without limit. The pool is private to this class
 * (not a Spring {@code Executor} bean), so it does not replace Spring Boot's auto-configured task executor. On
 * shutdown queued screenings get a short grace period to finish.
 */
@Slf4j
@Component
public class ScreeningDispatcher {

    static final int THREADS = 2;
    static final int QUEUE_CAPACITY = 200;
    static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);

    private final ThreadPoolExecutor executor;
    private final AtomicInteger pending = new AtomicInteger();
    private final Object idle = new Object();

    public ScreeningDispatcher() {
        this.executor = new ThreadPoolExecutor(THREADS, THREADS, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY), new ScreeningThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
    }

    /**
     * Queues one screening.
     *
     * @return false when the queue is full or the application is shutting down (the screening is skipped)
     */
    public boolean dispatch(Runnable screening) {
        pending.incrementAndGet();
        try {
            executor.execute(() -> {
                try {
                    screening.run();
                } catch (RuntimeException e) {
                    log.warn("Automatic risk screening failed: {}", e.getClass().getName());
                } finally {
                    finished();
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            finished();
            return false;
        }
    }

    /**
     * Waits until no screening is queued or running, at most {@code timeout} (graceful shutdown, and tests that
     * read the outcome of a screening their request started).
     *
     * @return true when idle, false when the timeout passed first
     */
    public boolean awaitIdle(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (idle) {
            while (pending.get() > 0) {
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remainingMs <= 0) {
                    return false;
                }
                try {
                    idle.wait(remainingMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    private void finished() {
        if (pending.decrementAndGet() <= 0) {
            synchronized (idle) {
                idle.notifyAll();
            }
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("Risk screening: {} screening(s) still queued at shutdown were dropped",
                        executor.shutdownNow().size());
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static final class ScreeningThreadFactory implements ThreadFactory {

        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "risk-screening-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
