package com.carenexus.api.billing.payment;

import com.carenexus.api.billing.config.BillingProperties;
import com.carenexus.api.billing.model.Payment;
import com.carenexus.api.billing.provider.InvalidWebhookSignatureException;
import com.carenexus.api.billing.provider.LocalPaymentGateway;
import com.carenexus.api.billing.provider.PaymentGateway;
import com.carenexus.api.billing.provider.PaymentGatewayException;
import com.carenexus.api.billing.provider.gateway.LocalWebhookSigner;
import com.carenexus.api.billing.provider.gateway.MalformedWebhookPayloadException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The local gateway: immediate and pending methods, the signed callback contract and refunds. */
class LocalPaymentGatewayTest {

    private static final String SECRET = "unit-test-secret-0123456789abcdef0123";

    private final BillingProperties properties = properties(SECRET);
    private final LocalPaymentGateway gateway = new LocalPaymentGateway(properties, new ObjectMapper());

    private static BillingProperties properties(String secret) {
        BillingProperties p = new BillingProperties();
        p.getLocal().setWebhookSecret(secret);
        return p;
    }

    private static PaymentGateway.InitiateRequest request(String method) {
        return new PaymentGateway.InitiateRequest("pay_1", "key_1", new BigDecimal("100.00"), "KES", method,
                "+254712344567", null, "Invoice INV-2026-000001");
    }

    @Test
    void offlineMethodsCompleteAtOnce_onlineMethodsStayPendingWithTheirNextAction() {
        for (String method : new String[]{"CASH", "BANK_TRANSFER", "INSURANCE"}) {
            PaymentGateway.InitiateResult result = gateway.initiate(request(method));
            assertThat(result.status()).isEqualTo(PaymentGateway.STATUS_COMPLETED);
            assertThat(result.nextAction()).isEqualTo(Payment.NEXT_ACTION_NONE);
        }
        PaymentGateway.InitiateResult mpesa = gateway.initiate(request("MPESA"));
        assertThat(mpesa.status()).isEqualTo(PaymentGateway.STATUS_PENDING);
        assertThat(mpesa.nextAction()).isEqualTo(Payment.NEXT_ACTION_STK_PUSH);
        assertThat(mpesa.providerReference()).matches("local_pay_[0-9a-f]{32}");
        PaymentGateway.InitiateResult card = gateway.initiate(request("CARD"));
        assertThat(card.nextAction()).isEqualTo(Payment.NEXT_ACTION_AWAIT_CONFIRMATION);
        assertThat(card.redirectUrl()).isNull();
        assertThat(card.providerReference()).isNotEqualTo(mpesa.providerReference());
        assertThat(gateway.supportedMethods()).containsExactly("CARD", "INSURANCE", "CASH", "MPESA", "BANK_TRANSFER");
    }

    @Test
    void withoutASecret_onlinePaymentsAreUnavailable() {
        LocalPaymentGateway unconfigured = new LocalPaymentGateway(properties(""), new ObjectMapper());
        assertThat(unconfigured.isConfigured()).isFalse();
        assertThatThrownBy(() -> unconfigured.initiate(request("CARD"))).isInstanceOf(PaymentGatewayException.class);
        assertThatThrownBy(() -> unconfigured.parseWebhook("{}", Map.of()))
                .isInstanceOf(InvalidWebhookSignatureException.class);
    }

    @Test
    void parseWebhook_verifiesTheSignatureOverTheExactBody() {
        String body = "{\"eventId\":\"evt_1\",\"eventType\":\"payment_completed\",\"providerReference\":\"local_pay_x\","
                + "\"transactionId\":\"LOC-1\"}";
        PaymentGateway.WebhookEvent event = gateway.parseWebhook(body,
                Map.of(LocalWebhookSigner.HEADER_LOWER, LocalWebhookSigner.sign(SECRET, body)));
        assertThat(event.eventId()).isEqualTo("evt_1");
        assertThat(event.eventType()).isEqualTo(PaymentGateway.EVENT_PAYMENT_COMPLETED);
        assertThat(event.providerReference()).isEqualTo("local_pay_x");
        assertThat(event.transactionId()).isEqualTo("LOC-1");

        assertThatThrownBy(() -> gateway.parseWebhook(body + " ",
                Map.of(LocalWebhookSigner.HEADER_LOWER, LocalWebhookSigner.sign(SECRET, body))))
                .isInstanceOf(InvalidWebhookSignatureException.class);
        assertThatThrownBy(() -> gateway.parseWebhook(body,
                Map.of(LocalWebhookSigner.HEADER_LOWER, LocalWebhookSigner.sign("another-secret", body))))
                .isInstanceOf(InvalidWebhookSignatureException.class);
        assertThatThrownBy(() -> gateway.parseWebhook(body, Map.of(LocalWebhookSigner.HEADER_LOWER, "sha256=zz")))
                .isInstanceOf(InvalidWebhookSignatureException.class);
        assertThatThrownBy(() -> gateway.parseWebhook(body, Map.of()))
                .isInstanceOf(InvalidWebhookSignatureException.class);
    }

    @Test
    void parseWebhook_derivesAnEventIdAndRefusesIncompleteBodies() {
        String body = "{\"eventType\":\"PAYMENT_FAILED\",\"providerReference\":\"local_pay_y\"}";
        PaymentGateway.WebhookEvent event = gateway.parseWebhook(body,
                Map.of(LocalWebhookSigner.HEADER_LOWER, LocalWebhookSigner.sign(SECRET, body)));
        assertThat(event.eventId()).isEqualTo(LocalWebhookSigner.sha256Hex(body)).hasSize(64);
        assertThat(event.failureReason()).isNull();

        String missing = "{\"eventType\":\"PAYMENT_FAILED\"}";
        assertThatThrownBy(() -> gateway.parseWebhook(missing,
                Map.of(LocalWebhookSigner.HEADER_LOWER, LocalWebhookSigner.sign(SECRET, missing))))
                .isInstanceOf(MalformedWebhookPayloadException.class);
        String array = "[1,2]";
        assertThatThrownBy(() -> gateway.parseWebhook(array,
                Map.of(LocalWebhookSigner.HEADER_LOWER, LocalWebhookSigner.sign(SECRET, array))))
                .isInstanceOf(MalformedWebhookPayloadException.class);
    }

    @Test
    void refundAndTokenize_needNoExternalCall() {
        assertThat(gateway.refund("Receipt 12 (typed at the desk)", new BigDecimal("10.00"), "KES", "k").status())
                .isEqualTo(PaymentGateway.STATUS_COMPLETED);
        assertThatThrownBy(() -> gateway.refund("ref", BigDecimal.ZERO, "KES", "k"))
                .isInstanceOf(PaymentGatewayException.class);
        assertThat(gateway.tokenize("CARD", Map.of())).matches("local_pm_[0-9a-f]{32}");
    }

    @Test
    void responseMapper_masksThePhoneAndComputesTheRefundableAmount() {
        assertThat(PaymentResponseMapper.maskPhone("+254 712 344 567")).isEqualTo("**** 4567");
        assertThat(PaymentResponseMapper.maskPhone("12")).isEqualTo("****");
        assertThat(PaymentResponseMapper.maskPhone(null)).isNull();
        Payment completed = Payment.builder().status(Payment.STATUS_COMPLETED).invoiceId(1).method("CARD")
                .amount(new BigDecimal("100")).refundedAmount(new BigDecimal("40.00")).build();
        assertThat(PaymentResponseMapper.refundableAmount(completed)).isEqualByComparingTo("60.00");
        completed.setMethod("INSURANCE");
        assertThat(PaymentResponseMapper.refundableAmount(completed)).isEqualByComparingTo("0.00");
    }
}
