package com.carenexus.api.billing.invoice.listener;

import com.carenexus.api.billing.config.BillingProperties;
import com.carenexus.api.billing.invoice.InvoiceService;
import com.carenexus.api.common.event.ConsultationCompletedEvent;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Automatic invoice of a completed consultation ({@code carenexus.billing.auto-invoice}: DRAFT, SENT or OFF).
 * <p>
 * A plain {@link EventListener} (CONTRACTS.v2 section 6): it runs synchronously inside the transaction that completes
 * the consultation, so the invoice commits with the completion, and in mode SENT the {@code InvoiceIssuedEvent} is
 * published in that same transaction (never from a BEFORE_COMMIT phase, whose late registrations Spring skips).
 * It is a no-op when a non-cancelled invoice already exists for the consultation or its appointment, so a repeated
 * event creates nothing. It never fails the completion for a business reason: such refusals are logged and
 * swallowed (they are raised before any write, so the surrounding transaction stays usable).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AutoInvoiceListener {

    private final InvoiceService invoiceService;
    private final BillingProperties billingProperties;

    @EventListener
    public void onConsultationCompleted(ConsultationCompletedEvent event) {
        String mode = billingProperties.getAutoInvoice();
        if (mode == null || BillingProperties.AUTO_INVOICE_OFF.equals(mode)) {
            return;
        }
        try {
            invoiceService.generateForCompletedConsultation(event, mode)
                    .ifPresent(invoiceId -> log.info("Auto-invoice {} created for consultation {} (mode {})",
                            invoiceId, event.consultationId(), mode));
        } catch (ConflictException | BadRequestException | ResourceNotFoundException refused) {
            // InvoiceService does not mark the transaction rollback-only for these, so the completion still commits.
            log.warn("Auto-invoice skipped for consultation {}: {}", event.consultationId(),
                    refused.getClass().getSimpleName());
        }
    }
}
