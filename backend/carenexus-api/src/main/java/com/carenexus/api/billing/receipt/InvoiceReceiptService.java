package com.carenexus.api.billing.receipt;

import com.carenexus.api.admin.settings.SettingKey;
import com.carenexus.api.admin.settings.SystemSettingsService;
import com.carenexus.api.billing.invoice.InvoiceService;
import com.carenexus.api.billing.invoice.dto.InvoiceResponse;
import com.carenexus.api.billing.model.Payment;
import com.carenexus.api.billing.repository.PaymentRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The printable invoice or receipt of one invoice (GET /api/billing/invoices/{id}/receipt).
 * <p>
 * Authorization, visibility and the READ / INVOICE audit row all come from {@link InvoiceService#getForCaller}
 * (patient: own issued invoices; caregiver with BILLING: the patient's issued invoices; issuing doctor: any status;
 * admin: everything). This service adds no rule and no audit row of its own. The document is rendered on every
 * request from current data; nothing is stored.
 */
@Service
public class InvoiceReceiptService {

    /** Hard cap on the payment rows printed. */
    static final int MAX_PAYMENTS = 200;

    /** Payments printed: money that was received, whether or not it was returned later. */
    static final List<String> PRINTED_PAYMENT_STATUSES = List.of(Payment.STATUS_COMPLETED, Payment.STATUS_REFUNDED);

    /** Clinic name used when the setting is blank. */
    static final String DEFAULT_CLINIC_NAME = "CareNexus";

    /** Payment print order: paidAt ascending (PostgreSQL puts nulls last), then id. */
    private static final Sort PAYMENT_ORDER = Sort.by(Sort.Order.asc("paidAt"), Sort.Order.asc("paymentId"));

    private final InvoiceService invoiceService;
    private final PaymentRepository paymentRepository;
    private final SystemSettingsService systemSettingsService;
    private final Clock clock;

    public InvoiceReceiptService(InvoiceService invoiceService, PaymentRepository paymentRepository,
                                 SystemSettingsService systemSettingsService, Clock clock) {
        this.invoiceService = invoiceService;
        this.paymentRepository = paymentRepository;
        this.systemSettingsService = systemSettingsService;
        this.clock = clock;
    }

    /** The rendered document. */
    public record ReceiptDocument(String fileName, byte[] body) {
    }

    /**
     * Renders the document for the caller.
     *
     * @throws com.carenexus.api.common.exception.ResourceNotFoundException unknown invoice, or a never-issued invoice
     *                                                                      for a patient or caregiver
     * @throws com.carenexus.api.common.exception.AuthorizationException   the caller may not read the invoice
     */
    @Transactional(readOnly = true)
    public ReceiptDocument render(Integer invoiceId) {
        InvoiceResponse invoice = invoiceService.getForCaller(invoiceId);
        List<Payment> payments = settledPayments(invoice.invoiceId());
        String html = ReceiptHtmlRenderer.render(clinicName(), invoice, payments, LocalDateTime.now(clock));
        return new ReceiptDocument(ReceiptHtmlRenderer.fileName(invoice), html.getBytes(StandardCharsets.UTF_8));
    }

    private List<Payment> settledPayments(Integer invoiceId) {
        Specification<Payment> settledOfInvoice = (root, query, cb) -> cb.and(
                cb.equal(root.get("invoiceId"), invoiceId),
                root.get("status").in(PRINTED_PAYMENT_STATUSES));
        return paymentRepository.findAll(settledOfInvoice, PageRequest.of(0, MAX_PAYMENTS, PAYMENT_ORDER))
                .getContent();
    }

    private String clinicName() {
        String name = systemSettingsService.getString(SettingKey.CLINIC_NAME);
        return name == null || name.isBlank() ? DEFAULT_CLINIC_NAME : name.trim();
    }
}
