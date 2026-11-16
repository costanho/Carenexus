package com.carenexus.api.billing.method;

import com.carenexus.api.billing.invoice.InvoiceService;
import com.carenexus.api.billing.invoice.dto.InvoiceResponse;
import com.carenexus.api.billing.method.dto.AdminPaymentResponse;
import com.carenexus.api.billing.method.dto.ManualPaymentRequest;
import com.carenexus.api.billing.method.repository.PaymentLookupQueries;
import com.carenexus.api.billing.model.Invoice;
import com.carenexus.api.billing.model.Payment;
import com.carenexus.api.billing.model.PaymentMethod;
import com.carenexus.api.billing.repository.InvoiceRepository;
import com.carenexus.api.billing.repository.PaymentRepository;
import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.event.PaymentRecordedEvent;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The administrator's back office for payments: the list of every payment and the manual (cash, bank transfer)
 * payment recorded against an invoice. ADMIN only (route rule {@code /api/admin/**} plus the service check).
 * <p>
 * A manual payment records money already received: it is created {@code COMPLETED} with gateway {@code MANUAL}, the
 * administrator as initiator, the invoice's currency and the recording time, is applied to the invoice through
 * {@link InvoiceService#applyPayment} (which decides under the invoice row lock) and publishes
 * {@link PaymentRecordedEvent}, all in one transaction.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminPaymentService {

    public static final String RESOURCE = "PAYMENT";
    public static final String GATEWAY_MANUAL = "MANUAL";

    /** Methods a manual payment can use. */
    public static final List<String> MANUAL_METHODS = List.of(PaymentMethod.TYPE_CASH, PaymentMethod.TYPE_BANK_TRANSFER);

    static final String REFERENCE_REQUIRED = "reference is required for a bank transfer";
    static final String PAYMENT_EXCEEDS = "Payment exceeds the outstanding balance";

    /** Newest first, ties by id. */
    static final Sort LIST_SORT = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "paymentId"));

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final PaymentRepository paymentRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceService invoiceService;
    private final PaymentLookupQueries lookups;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    // =====================================================================================================
    // LIST
    // =====================================================================================================

    /**
     * Every payment of every patient and status (GET /api/admin/billing/payments). Filters, order and paging run in
     * the database; invoice numbers and names come from batch lookups. One READ audit row per patient on the page.
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminPaymentResponse> list(String status, String method, String patientSystemId,
                                                   Integer page, Integer size) {
        authorizationService.requireRole(AuthorizationService.ADMIN);
        List<String> statuses = parseList(status, Payment.STATUSES, "Unknown payment status '");
        List<String> methods = parseList(method, PaymentMethod.TYPES, "Unknown payment method '");
        Specification<Payment> spec = patient(blankToNull(patientSystemId))
                .and(in("status", statuses))
                .and(in("method", methods));
        Page<Payment> rows = paymentRepository.findAll(spec, PageParams.of(page, size, LIST_SORT));
        auditService.logListRead(RESOURCE, rows.getContent(), Payment::getPatientSystemId);
        return PageResponse.of(rows, toResponses(rows.getContent()));
    }

    // =====================================================================================================
    // MANUAL PAYMENT
    // =====================================================================================================

    /**
     * Records a cash or bank-transfer payment against an invoice. Check order after Bean Validation: reference for a
     * bank transfer (400), invoice lookup (404), invoice status (409), amount against the outstanding balance (409).
     * The status and amount checks are repeated by {@link InvoiceService#applyPayment} under the row lock, so two
     * administrators recording at the same moment cannot overpay: the second one gets the 409 and its payment row is
     * rolled back.
     */
    @Transactional
    public AdminPaymentResponse recordManual(Integer invoiceId, ManualPaymentRequest request) {
        authorizationService.requireRole(AuthorizationService.ADMIN);
        String admin = authorizationService.currentSystemId();
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        if (!MANUAL_METHODS.contains(method)) {
            // Bean Validation already restricts the method; this only guards a direct call.
            throw new BadRequestException(ManualPaymentRequest.METHOD_MESSAGE);
        }
        String reference = request.getReference();
        if (PaymentMethod.TYPE_BANK_TRANSFER.equals(method) && reference == null) {
            throw new BadRequestException(REFERENCE_REQUIRED);
        }
        BigDecimal amount = request.getAmount().setScale(2, RoundingMode.HALF_UP);

        // Take the invoice row lock before anything reads the invoice. A plain read first would put an unlocked copy
        // in the persistence context, and the locked re-read inside applyPayment would then return that managed
        // (possibly stale) instance: a concurrent part payment committed in between would be overwritten. Locking
        // first makes the pre-checks below and applyPayment see the same, current row. An unknown id is reported by
        // getForCaller (404 'Invoice not found').
        invoiceRepository.findByIdForUpdate(invoiceId);
        InvoiceResponse invoice = invoiceService.getForCaller(invoiceId);
        if (!Invoice.PAYABLE_STATUSES.contains(invoice.status())) {
            throw new ConflictException("An invoice in status " + invoice.status() + " cannot be paid");
        }
        if (amount.compareTo(invoice.outstanding()) > 0) {
            throw new ConflictException(PAYMENT_EXCEEDS);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        Payment payment = paymentRepository.saveAndFlush(Payment.builder()
                .patientSystemId(invoice.patientSystemId())
                .appointmentId(invoice.appointmentId())
                .invoiceId(invoice.invoiceId())
                .amount(amount)
                .currency(invoice.currency())
                .status(Payment.STATUS_COMPLETED)
                .method(method)
                .gateway(GATEWAY_MANUAL)
                .providerReference(reference)
                .initiatedBySystemId(admin)
                .refundedAmount(ZERO)
                .paidAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build());

        invoiceService.applyPayment(invoice.invoiceId(), amount);

        auditService.logClinicalAccess("CREATE", RESOURCE, payment.getPaymentId(), payment.getPatientSystemId());
        eventPublisher.publishEvent(new PaymentRecordedEvent(payment.getPaymentId(), payment.getInvoiceId(),
                payment.getPatientSystemId(), admin, payment.getAmount(), payment.getCurrency(), payment.getMethod(),
                PaymentRecordedEvent.COMPLETED));
        log.info("Manual payment {} ({}) recorded on invoice {} by {}", payment.getPaymentId(), method,
                invoice.invoiceId(), admin);
        return toResponses(List.of(payment)).get(0);
    }

    // =====================================================================================================
    // MAPPING
    // =====================================================================================================

    List<AdminPaymentResponse> toResponses(Collection<Payment> payments) {
        Set<Integer> invoiceIds = new HashSet<>();
        Set<Integer> methodIds = new HashSet<>();
        Set<String> people = new HashSet<>();
        for (Payment payment : payments) {
            if (payment.getInvoiceId() != null) {
                invoiceIds.add(payment.getInvoiceId());
            }
            if (payment.getPaymentMethodId() != null) {
                methodIds.add(payment.getPaymentMethodId());
            }
            people.add(payment.getPatientSystemId());
            people.add(payment.getInitiatedBySystemId());
        }
        people.remove(null);
        Map<Integer, String> invoiceNumbers = lookups.invoiceNumbersOf(invoiceIds);
        Map<Integer, String> methodLabels = lookups.methodLabelsOf(methodIds);
        Map<String, String> names = lookups.namesOf(people);

        List<AdminPaymentResponse> responses = new ArrayList<>(payments.size());
        for (Payment payment : payments) {
            BigDecimal amount = money(payment.getAmount());
            BigDecimal refunded = money(payment.getRefundedAmount());
            responses.add(new AdminPaymentResponse(
                    payment.getPaymentId(),
                    payment.getInvoiceId(),
                    payment.getInvoiceId() == null ? null : invoiceNumbers.get(payment.getInvoiceId()),
                    payment.getPatientSystemId(),
                    names.get(payment.getPatientSystemId()),
                    amount,
                    payment.getCurrency(),
                    payment.getMethod(),
                    payment.getStatus(),
                    payment.getGateway(),
                    payment.getProviderReference(),
                    payment.getTransactionId(),
                    payment.getPaymentMethodId(),
                    payment.getPaymentMethodId() == null ? null : methodLabels.get(payment.getPaymentMethodId()),
                    payment.getInitiatedBySystemId(),
                    payment.getInitiatedBySystemId() == null ? null : names.get(payment.getInitiatedBySystemId()),
                    payment.getFailureReason(),
                    refunded,
                    refundableOf(payment, amount, refunded),
                    payment.getRefundReason(),
                    payment.getPaidAt(),
                    payment.getCreatedAt(),
                    payment.getUpdatedAt()));
        }
        return responses;
    }

    /** amount - refundedAmount for a COMPLETED, non-INSURANCE payment linked to an invoice; 0.00 otherwise. */
    static BigDecimal refundableOf(Payment payment, BigDecimal amount, BigDecimal refunded) {
        if (!Payment.STATUS_COMPLETED.equals(payment.getStatus())
                || PaymentMethod.TYPE_INSURANCE.equals(payment.getMethod())
                || payment.getInvoiceId() == null) {
            return ZERO;
        }
        BigDecimal left = amount.subtract(refunded);
        return left.signum() < 0 ? ZERO : left.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    // =====================================================================================================
    // FILTERS
    // =====================================================================================================

    /** One value or a comma-separated list, trimmed and upper-cased; empty = no filter. */
    static List<String> parseList(String csv, List<String> allowed, String unknownPrefix) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String value = part.trim();
            if (value.isEmpty()) {
                continue;
            }
            String normalized = value.toUpperCase(Locale.ROOT);
            if (!allowed.contains(normalized)) {
                throw new BadRequestException(unknownPrefix + value + "'");
            }
            values.add(normalized);
        }
        return List.copyOf(values);
    }

    private static Specification<Payment> patient(String patientSystemId) {
        return (root, query, cb) -> patientSystemId == null ? cb.conjunction()
                : cb.equal(root.get("patientSystemId"), patientSystemId);
    }

    private static Specification<Payment> in(String attribute, Collection<String> values) {
        return (root, query, cb) -> values == null || values.isEmpty() ? cb.conjunction()
                : root.get(attribute).in(values);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
