package com.carenexus.api.billing.method;

import com.carenexus.api.billing.model.Payment;
import com.carenexus.api.billing.model.PaymentMethod;
import com.carenexus.api.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure rules of the payment-method and admin-payment services: labels, duplicates, filters, refundable amount. */
class PaymentMethodRulesTest {

    private static PaymentMethod method(String type, String provider, String lastFour) {
        return PaymentMethod.builder().type(type).provider(provider).lastFour(lastFour).build();
    }

    @Test
    void labelUsesProviderOrCardAndFourBullets() {
        assertEquals("Visa •••• 4242", PaymentMethodService.labelOf("Visa", "4242"));
        assertEquals("Card •••• 4242", PaymentMethodService.labelOf(null, "4242"));
        assertEquals("Card •••• 4242", PaymentMethodService.labelOf("  ", "4242"));
        assertEquals("M-PESA •••• 5678", PaymentMethodService.labelOf("M-PESA", "5678"));
    }

    @Test
    void responseNeverCarriesStoredOnlyFieldsAndBuildsALabelForLegacyRows() {
        PaymentMethod legacy = PaymentMethod.builder().methodId(3).systemId("PAT-00001").type("CARD")
                .provider("Visa").lastFour("4242").providerToken("local_pm_abc").isDefault(true)
                .createdAt(LocalDateTime.of(2026, 11, 10, 9, 0)).build();
        var response = PaymentMethodService.toResponse(legacy);
        assertEquals("Visa •••• 4242", response.getDisplayLabel());
        assertTrue(response.getIsDefault());
        assertEquals(null, response.getInsurerName());

        PaymentMethod policy = PaymentMethod.builder().methodId(4).systemId("PAT-00001").type("INSURANCE")
                .provider("AAR").insurerName("AAR").lastFour("1234").policyNumber("POL-1234").memberNumber("M-1")
                .displayLabel("AAR •••• 1234").isDefault(null).build();
        var insurance = PaymentMethodService.toResponse(policy);
        assertEquals("AAR", insurance.getInsurerName());
        assertFalse(insurance.getIsDefault());
    }

    @Test
    void duplicateRuleComparesTheInstrumentIdentityOfEachType() {
        assertTrue(PaymentMethodService.sameInstrument(method("CARD", "Visa", "4242"), method("CARD", " VISA ", "4242")));
        assertTrue(PaymentMethodService.sameInstrument(method("CARD", null, "4242"), method("CARD", null, "4242")));
        assertFalse(PaymentMethodService.sameInstrument(method("CARD", null, "4242"), method("CARD", "Visa", "4242")));
        assertFalse(PaymentMethodService.sameInstrument(method("CARD", "Visa", "4242"), method("CARD", "Visa", "4243")));

        PaymentMethod phoneA = method("MPESA", "M-PESA", "5678");
        phoneA.setMpesaPhone("+254 712 345 678");
        PaymentMethod phoneB = method("MPESA", "M-PESA", "5678");
        phoneB.setMpesaPhone("254-712-345-678");
        PaymentMethod phoneC = method("MPESA", "M-PESA", "5678");
        phoneC.setMpesaPhone("0712 345 678");
        assertTrue(PaymentMethodService.sameInstrument(phoneA, phoneB));
        assertFalse(PaymentMethodService.sameInstrument(phoneA, phoneC));

        PaymentMethod policyA = method("INSURANCE", "Jubilee", "1234");
        policyA.setInsurerName("Jubilee");
        policyA.setPolicyNumber("POL-1234");
        PaymentMethod policyB = method("INSURANCE", "JUBILEE", "1234");
        policyB.setInsurerName("JUBILEE");
        policyB.setPolicyNumber("pol-1234");
        assertTrue(PaymentMethodService.sameInstrument(policyA, policyB));
        assertFalse(PaymentMethodService.sameInstrument(policyA, method("CARD", "Jubilee", "1234")));
    }

    @Test
    void filterListsAreTrimmedUpperCasedDeduplicatedAndRejectUnknownValues() {
        assertEquals(List.of(), AdminPaymentService.parseList(null, Payment.STATUSES, "Unknown payment status '"));
        assertEquals(List.of(), AdminPaymentService.parseList(" ", Payment.STATUSES, "Unknown payment status '"));
        assertEquals(List.of("COMPLETED", "REFUNDED"), AdminPaymentService.parseList(
                "completed, Refunded,,COMPLETED", Payment.STATUSES, "Unknown payment status '"));
        BadRequestException unknown = assertThrows(BadRequestException.class, () -> AdminPaymentService.parseList(
                "CASH, crypto", PaymentMethod.TYPES, "Unknown payment method '"));
        assertEquals("Unknown payment method 'crypto'", unknown.getMessage());
    }

    @Test
    void refundableAmountIsTheUnrefundedPartOfACompletedNonInsurancePayment() {
        Payment completed = Payment.builder().status("COMPLETED").method("CASH").invoiceId(1).build();
        assertEquals(new BigDecimal("750.00"), AdminPaymentService.refundableOf(completed,
                new BigDecimal("1000.00"), new BigDecimal("250.00")));

        Payment insurance = Payment.builder().status("COMPLETED").method("INSURANCE").invoiceId(1).build();
        assertEquals(new BigDecimal("0.00"), AdminPaymentService.refundableOf(insurance,
                new BigDecimal("1000.00"), new BigDecimal("0.00")));

        Payment pending = Payment.builder().status("PENDING").method("MPESA").invoiceId(1).build();
        assertEquals(new BigDecimal("0.00"), AdminPaymentService.refundableOf(pending,
                new BigDecimal("1000.00"), new BigDecimal("0.00")));

        Payment legacy = Payment.builder().status("COMPLETED").method("CARD").invoiceId(null).build();
        assertEquals(new BigDecimal("0.00"), AdminPaymentService.refundableOf(legacy,
                new BigDecimal("1000.00"), new BigDecimal("0.00")));
    }
}
