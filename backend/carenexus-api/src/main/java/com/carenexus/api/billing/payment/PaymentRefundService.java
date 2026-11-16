package com.carenexus.api.billing.payment;

import com.carenexus.api.billing.invoice.InvoiceService;
import com.carenexus.api.billing.model.Payment;
import com.carenexus.api.billing.model.PaymentMethod;
import com.carenexus.api.billing.payment.dto.PaymentResponse;
import com.carenexus.api.billing.payment.dto.RefundPaymentRequest;
import com.carenexus.api.billing.provider.PaymentGateway;
import com.carenexus.api.billing.provider.PaymentGatewayException;
import com.carenexus.api.billing.repository.PaymentRepository;
import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.event.PaymentRecordedEvent;
import com.carenexus.api.common.exception.ApiException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Administrator refunds of completed payments (POST /api/admin/billing/payments/{id}/refund). One transaction:
 * the invoice's amountPaid is lowered first ({@code invoiceService.reversePayment}, which may refuse with 409), then
 * the gateway is asked to return the money when it handled the payment; a gateway failure rolls everything back
 * (503). Partial refunds are allowed until {@code refundedAmount} reaches {@code amount}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRefundService {

    static final String INSURANCE_NOT_REFUNDABLE = "An insurance settlement cannot be refunded here";
    static final String NOT_LINKED = "This payment is not linked to an invoice";
    static final String ALREADY_REFUNDED = "This payment has already been refunded in full";
    static final String ONLY_COMPLETED = "Only a completed payment can be refunded";
    static final String REFUND_EXCEEDS = "Refund exceeds the refundable amount";

    private final PaymentRepository paymentRepository;
    private final InvoiceService invoiceService;
    private final PaymentGateway paymentGateway;
    private final PaymentSettlement settlement;
    private final PaymentResponseMapper mapper;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final Clock clock;

    /**
     * @param idempotencyKey the request's Idempotency-Key, handed to the gateway's refund call (may be null)
     */
    @Transactional
    public PaymentResponse refund(Integer paymentId, RefundPaymentRequest request, String idempotencyKey) {
        authorizationService.requireRole(AuthorizationService.ADMIN);
        String admin = authorizationService.currentSystemId();

        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException(PaymentService.PAYMENT_NOT_FOUND));
        if (PaymentMethod.TYPE_INSURANCE.equals(payment.getMethod())) {
            throw new ConflictException(INSURANCE_NOT_REFUNDABLE);
        }
        if (payment.getInvoiceId() == null) {
            throw new ConflictException(NOT_LINKED);
        }
        if (Payment.STATUS_REFUNDED.equals(payment.getStatus())) {
            throw new ConflictException(ALREADY_REFUNDED);
        }
        if (!Payment.STATUS_COMPLETED.equals(payment.getStatus())) {
            throw new ConflictException(ONLY_COMPLETED);
        }
        BigDecimal refundable = PaymentResponseMapper.refundableAmount(payment);
        BigDecimal amount = request.getAmount() == null ? refundable : PaymentResponseMapper.money(request.getAmount());
        if (amount.signum() <= 0 || amount.compareTo(refundable) > 0) {
            throw new ConflictException(REFUND_EXCEEDS);
        }

        invoiceService.reversePayment(payment.getInvoiceId(), amount);

        if (paymentGateway.name().equals(payment.getGateway()) && payment.getProviderReference() != null) {
            try {
                paymentGateway.refund(payment.getProviderReference(), amount, payment.getCurrency(),
                        idempotencyKey == null || idempotencyKey.isBlank()
                                ? "refund_" + payment.getPaymentId() + "_" + PaymentResponseMapper.money(payment.getRefundedAmount()).toPlainString()
                                : idempotencyKey.trim());
            } catch (PaymentGatewayException e) {
                log.warn("Payment gateway {} could not refund payment {} (retryable: {})", paymentGateway.name(),
                        payment.getPaymentId(), e.isRetryable());
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorResponse.PROVIDER_UNAVAILABLE,
                        PaymentService.PROVIDER_UNAVAILABLE);
            }
        }

        BigDecimal refunded = PaymentResponseMapper.money(payment.getRefundedAmount()).add(amount);
        payment.setRefundedAmount(refunded);
        payment.setRefundReason(request.getReason().trim());
        if (refunded.compareTo(PaymentResponseMapper.money(payment.getAmount())) >= 0) {
            payment.setStatus(Payment.STATUS_REFUNDED);
        }
        payment.setUpdatedAt(LocalDateTime.now(clock));
        paymentRepository.saveAndFlush(payment);
        settlement.publish(payment, PaymentRecordedEvent.REFUNDED, amount, admin);
        auditService.logClinicalAccess("UPDATE", PaymentService.RESOURCE, payment.getPaymentId(),
                payment.getPatientSystemId());
        return mapper.toResponse(payment);
    }
}
