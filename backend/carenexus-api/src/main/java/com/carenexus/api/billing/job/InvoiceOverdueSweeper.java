package com.carenexus.api.billing.job;

import com.carenexus.api.billing.config.BillingProperties;
import com.carenexus.api.billing.invoice.InvoiceService;
import com.carenexus.api.billing.invoice.dto.InvoiceResponse;
import com.carenexus.api.billing.model.Invoice;
import com.carenexus.api.billing.repository.InvoiceRepository;
import com.carenexus.api.common.job.JobSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Marks unpaid invoices OVERDUE (job {@value #JOB_NAME}, every {@code carenexus.jobs.invoice-overdue.interval-ms},
 * default one hour).
 * <p>
 * An invoice in SENT or PARTIALLY_PAID whose due date plus {@code carenexus.billing.overdue-grace-days} has passed
 * (selection: {@code due_date < today - graceDays}, today in the clinic zone of the injected {@link Clock}) is moved
 * to OVERDUE through {@link InvoiceService#markOverdue}, which re-checks the invoice under a row lock, writes its
 * UPDATE / INVOICE audit row and publishes {@code InvoiceStatusChangedEvent}. The billing notification listener turns
 * that event into the "Invoice overdue" notification in the same transaction; this job sends nothing itself.
 * <p>
 * One run is one transaction inside {@link JobSupport#runExclusive}: a second API instance skips the run. Ids are
 * selected in batches of {@value #BATCH_SIZE} until a batch is empty or changes nothing. PAID, CANCELLED, REFUNDED,
 * DRAFT and already OVERDUE invoices are never selected, so a re-run changes nothing.
 * <p>
 * Scheduled runs are off when {@code carenexus.jobs.enabled=false}; tests call {@link #sweepOnce()}.
 */
@Slf4j
@Component
public class InvoiceOverdueSweeper {

    public static final String JOB_NAME = "invoice-overdue";
    public static final int BATCH_SIZE = 200;
    /** Upper bound of batches per run, so one run stays bounded; the next run continues. */
    static final int MAX_BATCHES_PER_RUN = 50;

    private final JobSupport jobs;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceService invoiceService;
    private final BillingProperties billingProperties;
    private final Clock clock;

    public InvoiceOverdueSweeper(JobSupport jobs, InvoiceRepository invoiceRepository, InvoiceService invoiceService,
                                 BillingProperties billingProperties, Clock clock) {
        this.jobs = jobs;
        this.invoiceRepository = invoiceRepository;
        this.invoiceService = invoiceService;
        this.billingProperties = billingProperties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${carenexus.jobs.invoice-overdue.interval-ms:3600000}",
            initialDelayString = "${carenexus.jobs.invoice-overdue.interval-ms:3600000}")
    void tick() {
        if (!jobs.enabled()) {
            return;
        }
        try {
            int moved = sweepOnce();
            if (moved > 0) {
                log.info("Invoice overdue job marked {} invoice(s) overdue", moved);
            }
        } catch (RuntimeException e) {
            // JobSupport already counted and logged the failed run; the next tick tries again.
            log.debug("Invoice overdue run ended early: {}", e.getClass().getName());
        }
    }

    /**
     * One run.
     *
     * @return how many invoices this run moved to OVERDUE; 0 when nothing was due or another instance holds the job
     */
    public int sweepOnce() {
        LocalDate cutoff = LocalDate.now(clock).minusDays(billingProperties.effectiveOverdueGraceDays());
        AtomicInteger moved = new AtomicInteger();
        jobs.runExclusive(JOB_NAME, () -> moved.set(markDue(cutoff)));
        return moved.get();
    }

    /** Runs inside the job's transaction. */
    private int markDue(LocalDate cutoff) {
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            List<Integer> ids = invoiceRepository.findIdsDueBefore(cutoff, PageRequest.of(0, BATCH_SIZE));
            if (ids.isEmpty()) {
                break;
            }
            int changed = 0;
            for (Integer id : ids) {
                InvoiceResponse result = invoiceService.markOverdue(id);
                if (Invoice.STATUS_OVERDUE.equals(result.status())) {
                    changed++;
                }
            }
            total += changed;
            if (changed == 0) {
                // The facade refused every selected invoice (its own date rule): stop instead of re-selecting them.
                break;
            }
        }
        return total;
    }
}
