package com.carenexus.api.common.job;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Shared plumbing for scheduled jobs.
 *
 * <pre>
 * &#64;Scheduled(fixedDelayString = "${carenexus.jobs.reminders.interval-ms:60000}")
 * void tick() { if (jobs.enabled()) sweepOnce(); }
 *
 * public int sweepOnce() {
 *     AtomicInteger done = new AtomicInteger();
 *     jobs.runExclusive("reminders", () -&gt; done.set(processBatch()));
 *     return done.get();
 * }
 * </pre>
 *
 * {@link #enabled()} is the global switch ({@code carenexus.jobs.enabled}, false in tests, which call
 * {@code sweepOnce()} themselves). {@link #runExclusive} makes sure only one API instance runs a given job at a
 * time, and counts runs per job in Micrometer:
 * {@code carenexus.jobs.runs{job, outcome=success|failure|skipped}} and the timer {@code carenexus.jobs.duration{job}}.
 */
@Component
@Slf4j
public class JobSupport {

    static final String RUNS_METRIC = "carenexus.jobs.runs";
    static final String DURATION_METRIC = "carenexus.jobs.duration";

    private final boolean enabled;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate requiresNew;
    private final MeterRegistry meterRegistry;

    public JobSupport(@Value("${carenexus.jobs.enabled:true}") boolean enabled, JdbcTemplate jdbc,
                      PlatformTransactionManager transactionManager, ObjectProvider<MeterRegistry> meterRegistry) {
        this.enabled = enabled;
        this.jdbc = jdbc;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.meterRegistry = meterRegistry.getIfAvailable();
    }

    /** {@code carenexus.jobs.enabled} (default true; false in application-test.yml). */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Runs {@code body} in a new transaction while holding a transaction-scoped Postgres advisory lock named after
     * the job ({@code pg_try_advisory_xact_lock(hashtext(jobName))}). The lock is released when that transaction
     * ends, including on a crash of this instance.
     *
     * @return false when another instance (or thread) holds the lock: the body did not run
     * @throws RuntimeException whatever the body throws; the transaction is rolled back and the run counted as a failure
     */
    public boolean runExclusive(String jobName, Runnable body) {
        if (jobName == null || jobName.isBlank()) {
            throw new IllegalArgumentException("Job name is required");
        }
        Timer.Sample sample = meterRegistry != null ? Timer.start(meterRegistry) : null;
        boolean ran;
        try {
            ran = Boolean.TRUE.equals(requiresNew.execute(tx -> {
                Boolean locked = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtext(?))", Boolean.class, jobName);
                if (!Boolean.TRUE.equals(locked)) {
                    return false;
                }
                body.run();
                return true;
            }));
        } catch (RuntimeException | Error failure) {
            count(jobName, "failure");
            stop(sample, jobName);
            log.error("Job {} failed: {}", jobName, failure.getClass().getName());
            throw failure;
        }
        if (ran) {
            count(jobName, "success");
            stop(sample, jobName);
        } else {
            count(jobName, "skipped");
            log.debug("Job {} skipped: another instance is running it", jobName);
        }
        return ran;
    }

    private void count(String jobName, String outcome) {
        if (meterRegistry != null) {
            meterRegistry.counter(RUNS_METRIC, "job", jobName, "outcome", outcome).increment();
        }
    }

    private void stop(Timer.Sample sample, String jobName) {
        if (sample != null) {
            sample.stop(meterRegistry.timer(DURATION_METRIC, "job", jobName));
        }
    }
}
