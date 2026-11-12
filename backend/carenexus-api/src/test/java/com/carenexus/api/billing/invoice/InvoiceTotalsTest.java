package com.carenexus.api.billing.invoice;

import com.carenexus.api.billing.invoice.dto.InvoiceLineItemRequest;
import com.carenexus.api.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Invoice arithmetic: scale 2, HALF_UP, tax on the discounted subtotal. */
class InvoiceTotalsTest {

    private static InvoiceLineItemRequest line(String description, int quantity, String unitPrice) {
        return new InvoiceLineItemRequest(description, quantity, new BigDecimal(unitPrice));
    }

    @Test
    void sumsLinesAndAppliesTaxToTheDiscountedSubtotal() {
        InvoiceTotals totals = InvoiceTotals.compute(
                List.of(line("Video consultation", 1, "2000"), line("Medical report", 1, "500.00")),
                null, new BigDecimal("0.16"));

        assertEquals(new BigDecimal("2000.00"), totals.lines().get(0).amount());
        assertEquals(new BigDecimal("2000.00"), totals.lines().get(0).unitPrice());
        assertEquals(new BigDecimal("2500.00"), totals.subtotal());
        assertEquals(new BigDecimal("0.00"), totals.discount());
        assertEquals(new BigDecimal("400.00"), totals.tax());
        assertEquals(new BigDecimal("2900.00"), totals.total());
    }

    @Test
    void roundsHalfUpToTwoDecimals() {
        InvoiceTotals totals = InvoiceTotals.compute(List.of(line("  Dressing  ", 3, "99.99")),
                new BigDecimal("0.01"), new BigDecimal("0.16"));

        assertEquals("Dressing", totals.lines().get(0).description());
        assertEquals(new BigDecimal("299.97"), totals.subtotal());
        // (299.97 - 0.01) x 0.16 = 47.9936 -> 47.99
        assertEquals(new BigDecimal("47.99"), totals.tax());
        assertEquals(new BigDecimal("347.95"), totals.total());

        // A half cent rounds up: 0.05 x 0.5 = 0.025 -> 0.03
        InvoiceTotals half = InvoiceTotals.compute(List.of(line("Item", 1, "0.05")), null, new BigDecimal("0.5"));
        assertEquals(new BigDecimal("0.03"), half.tax());
        assertEquals(new BigDecimal("0.08"), half.total());
    }

    @Test
    void zeroTaxRateAndDiscountEqualToSubtotalGiveZeroTotal() {
        InvoiceTotals totals = InvoiceTotals.compute(List.of(line("Follow-up", 2, "750.00")),
                new BigDecimal("1500.00"), BigDecimal.ZERO);
        assertEquals(new BigDecimal("1500.00"), totals.subtotal());
        assertEquals(new BigDecimal("0.00"), totals.tax());
        assertEquals(new BigDecimal("0.00"), totals.total());
    }

    @Test
    void discountAboveSubtotalIsRefused() {
        BadRequestException refused = assertThrows(BadRequestException.class, () -> InvoiceTotals.compute(
                List.of(line("Video consultation", 1, "100.00")), new BigDecimal("100.01"), BigDecimal.ZERO));
        assertEquals("Discount cannot exceed the subtotal", refused.getMessage());
    }
}
