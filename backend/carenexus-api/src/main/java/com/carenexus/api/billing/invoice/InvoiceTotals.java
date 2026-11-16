package com.carenexus.api.billing.invoice;

import com.carenexus.api.billing.invoice.dto.InvoiceLineItem;
import com.carenexus.api.billing.invoice.dto.InvoiceLineItemRequest;
import com.carenexus.api.common.exception.BadRequestException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Invoice arithmetic. Every amount is a {@link BigDecimal} with scale 2, rounded {@link RoundingMode#HALF_UP}:
 * <ul>
 *   <li>line amount = unit price x quantity</li>
 *   <li>subtotal = sum of the line amounts</li>
 *   <li>tax = (subtotal - discount) x tax rate</li>
 *   <li>total = subtotal - discount + tax</li>
 * </ul>
 * The tax rate is a fraction between 0 and 1 (0.16 = 16 %).
 */
public record InvoiceTotals(List<InvoiceLineItem> lines, BigDecimal subtotal, BigDecimal discount, BigDecimal tax,
                            BigDecimal total) {

    static final String DISCOUNT_ABOVE_SUBTOTAL = "Discount cannot exceed the subtotal";

    public static final BigDecimal ZERO = new BigDecimal("0.00");

    /**
     * Computes the lines and totals of an invoice.
     *
     * @param requested validated lines (description is trimmed here)
     * @param discount  null means 0.00
     * @param taxRate   fraction 0..1; null means 0
     * @throws BadRequestException when the discount is above the subtotal
     */
    public static InvoiceTotals compute(List<InvoiceLineItemRequest> requested, BigDecimal discount, BigDecimal taxRate) {
        List<InvoiceLineItem> lines = new ArrayList<>(requested.size());
        BigDecimal subtotal = ZERO;
        for (InvoiceLineItemRequest line : requested) {
            BigDecimal unitPrice = money(line.getUnitPrice());
            BigDecimal amount = money(unitPrice.multiply(BigDecimal.valueOf(line.getQuantity())));
            lines.add(new InvoiceLineItem(line.getDescription().trim(), line.getQuantity(), unitPrice, amount));
            subtotal = subtotal.add(amount);
        }
        subtotal = money(subtotal);
        BigDecimal appliedDiscount = money(discount);
        if (appliedDiscount.compareTo(subtotal) > 0) {
            throw new BadRequestException(DISCOUNT_ABOVE_SUBTOTAL);
        }
        BigDecimal rate = taxRate == null ? BigDecimal.ZERO : taxRate;
        BigDecimal taxable = subtotal.subtract(appliedDiscount);
        BigDecimal tax = money(taxable.multiply(rate));
        BigDecimal total = money(taxable.add(tax));
        return new InvoiceTotals(List.copyOf(lines), subtotal, appliedDiscount, tax, total);
    }

    /** Scale 2, HALF_UP; null becomes 0.00. */
    public static BigDecimal money(BigDecimal value) {
        return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }
}
