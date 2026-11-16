package com.carenexus.api.billing.payment;

import com.carenexus.api.billing.model.Invoice;
import com.carenexus.api.billing.model.Payment;
import com.carenexus.api.billing.model.PaymentMethod;
import com.carenexus.api.billing.payment.dto.PaymentResponse;
import com.carenexus.api.billing.payment.repository.PaymentLookupQueries;
import com.carenexus.api.billing.repository.InvoiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Payment entity to {@link PaymentResponse}, with batch lookups (one query each for invoice numbers, user names and
 * saved-method labels, whatever the number of rows). Never exposes the gateway response, a token or the full phone.
 */
@Component
@RequiredArgsConstructor
public class PaymentResponseMapper {

    static final BigDecimal ZERO = new BigDecimal("0.00");

    private final InvoiceRepository invoiceRepository;
    private final PaymentLookupQueries lookups;

    public PaymentResponse toResponse(Payment payment) {
        return toResponses(List.of(payment)).get(0);
    }

    public List<PaymentResponse> toResponses(Collection<Payment> payments) {
        Set<Integer> invoiceIds = new HashSet<>();
        Set<String> people = new HashSet<>();
        Set<Integer> methodIds = new HashSet<>();
        for (Payment payment : payments) {
            if (payment.getInvoiceId() != null) {
                invoiceIds.add(payment.getInvoiceId());
            }
            people.add(payment.getPatientSystemId());
            people.add(payment.getInitiatedBySystemId());
            if (payment.getPaymentMethodId() != null) {
                methodIds.add(payment.getPaymentMethodId());
            }
        }
        people.remove(null);
        Map<Integer, String> invoiceNumbers = new HashMap<>();
        if (!invoiceIds.isEmpty()) {
            for (Invoice invoice : invoiceRepository.findByInvoiceIdIn(invoiceIds)) {
                invoiceNumbers.put(invoice.getInvoiceId(), invoice.getInvoiceNo());
            }
        }
        Map<String, String> names = lookups.namesOf(people);
        Map<Integer, String> labels = lookups.methodLabelsOf(methodIds);

        List<PaymentResponse> responses = new ArrayList<>(payments.size());
        for (Payment p : payments) {
            boolean pending = Payment.STATUS_PENDING.equals(p.getStatus());
            String nextAction = pending && p.getNextAction() != null ? p.getNextAction() : Payment.NEXT_ACTION_NONE;
            String redirectUrl = Payment.NEXT_ACTION_REDIRECT.equals(nextAction) ? p.getNextActionUrl() : null;
            responses.add(new PaymentResponse(
                    p.getPaymentId(),
                    p.getInvoiceId(),
                    p.getInvoiceId() == null ? null : invoiceNumbers.get(p.getInvoiceId()),
                    p.getPatientSystemId(),
                    names.get(p.getPatientSystemId()),
                    p.getInitiatedBySystemId(),
                    p.getInitiatedBySystemId() == null ? null : names.get(p.getInitiatedBySystemId()),
                    money(p.getAmount()),
                    p.getCurrency(),
                    p.getMethod(),
                    p.getPaymentMethodId(),
                    p.getPaymentMethodId() == null ? null : labels.get(p.getPaymentMethodId()),
                    p.getStatus(),
                    nextAction,
                    redirectUrl,
                    p.getGateway(),
                    p.getProviderReference(),
                    p.getTransactionId(),
                    maskPhone(p.getPayerPhone()),
                    p.getFailureReason(),
                    money(p.getRefundedAmount()),
                    refundableAmount(p),
                    p.getRefundReason(),
                    p.getPaidAt(),
                    p.getCreatedAt(),
                    p.getUpdatedAt()));
        }
        return responses;
    }

    /**
     * amount - refundedAmount for a COMPLETED payment that is linked to an invoice and is not an insurance
     * settlement; 0.00 otherwise.
     */
    public static BigDecimal refundableAmount(Payment payment) {
        if (!Payment.STATUS_COMPLETED.equals(payment.getStatus()) || payment.getInvoiceId() == null
                || PaymentMethod.TYPE_INSURANCE.equals(payment.getMethod())) {
            return ZERO;
        }
        BigDecimal left = money(payment.getAmount()).subtract(money(payment.getRefundedAmount()));
        return left.signum() < 0 ? ZERO : left;
    }

    /** Scale 2, HALF_UP; null reads as 0.00. */
    public static BigDecimal money(BigDecimal value) {
        return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    /** {@code **** } and the last four digits; {@code ****} when the number has fewer digits; null stays null. */
    static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() < 4) {
            return "****";
        }
        return "**** " + digits.substring(digits.length() - 4);
    }
}
