package com.carenexus.api.billing.payment.job;

import com.carenexus.api.billing.model.Payment;
import com.carenexus.api.billing.payment.PaymentSettlement;
import com.carenexus.api.billing.repository.PaymentRepository;
import com.carenexus.api.common.job.JobSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Job {@value #JOB_NAME}: a gateway payment still PENDING {@link #TIMEOUT} after it was started becomes FAILED with
 * {@value #TIMED_OUT}, and {@code PaymentRecordedEvent} (FAILED) is published. The invoice is untouched (a PENDING
 * payment never changed it); the amount the payment reserved is free again.
 * <p>
 * Batches of {@value #BATCH_SIZE}, each claimed with {@code FOR UPDATE SKIP LOCKED} inside
 * {@link JobSupport#runExclusive} (one transaction per batch), so a payment a webhook is settling right now is left
 * for the next run. Re-running changes nothing. There is no authenticated actor: no audit_log row is written; the
 * trail is the payment's failureReason. A late PAYMENT_COMPLETED webhook after this finds the payment terminal and
 * changes nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentReconciliationSweeper {

    public static final String JOB_NAME = "payment-reconcile";
    public static final String TIMED_OUT = "Payment was not confirmed in time";
    public static final Duration TIMEOUT = Duration.ofMinutes(30);
    static final int BATCH_SIZE = 200;
    /** Upper bound of batches per run, so one run cannot loop forever. */
    static final int MAX_BATCHES = 50;

    private final JobSupport jobSupport;
    private final PaymentRepository paymentRepository;
    private final PaymentSettlement settlement;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${carenexus.jobs.payment-reconcile.interval-ms:300000}",
            initialDelayString = "${carenexus.jobs.payment-reconcile.interval-ms:300000}")
    public void tick() {
        if (!jobSupport.enabled()) {
            return;
        }
        try {
            int failed = sweepOnce();
            if (failed > 0) {
                log.info("Payment reconciliation: {} pending payment(s) timed out", failed);
            }
        } catch (RuntimeException e) {
            log.error("Payment reconciliation failed: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Fails every PENDING payment created more than {@link #TIMEOUT} before now (injected clock, clinic zone).
     *
     * @return the number of payments failed by this run
     */
    public int sweepOnce() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(TIMEOUT);
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            AtomicInteger claimed = new AtomicInteger();
            AtomicInteger failed = new AtomicInteger();
            boolean ran = jobSupport.runExclusive(JOB_NAME, () -> {
                List<Integer> ids = paymentRepository.claimPendingIdsCreatedBefore(cutoff, BATCH_SIZE);
                claimed.set(ids.size());
                for (Integer id : ids) {
                    // Already locked by the claim; read it fresh and re-check under that lock.
                    paymentRepository.findById(id)
                            .filter(p -> Payment.STATUS_PENDING.equals(p.getStatus()))
                            .ifPresent(p -> {
                                settlement.fail(p, TIMED_OUT);
                                failed.incrementAndGet();
                            });
                }
            });
            total += failed.get();
            if (!ran || claimed.get() < BATCH_SIZE) {
                break;
            }
        }
        return total;
    }
}
