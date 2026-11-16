package com.carenexus.api.billing.receipt;

import com.carenexus.api.billing.invoice.dto.InvoiceLineItem;
import com.carenexus.api.billing.invoice.dto.InvoiceResponse;
import com.carenexus.api.billing.model.Payment;
import com.carenexus.api.billing.notify.BillingText;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests of the receipt renderer and the shared billing text formats (no Spring context).
 */
class ReceiptHtmlRendererTest {

    private static InvoiceResponse invoice(String invoiceNo, String status, String currency) {
        return new InvoiceResponse(42, invoiceNo, "PAT-00001", null, "DOC-00001", null, "DOC-00001", null, null,
                List.of(new InvoiceLineItem("Visit", 2, new BigDecimal("1000.00"), new BigDecimal("2000.00"))),
                new BigDecimal("2000.00"), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                new BigDecimal("2000.00"), BigDecimal.ZERO.setScale(2), new BigDecimal("2000.00"), currency, status,
                null, null, null, null, "  ", LocalDateTime.of(2026, 11, 1, 8, 0), LocalDateTime.of(2026, 11, 1, 8, 0));
    }

    @Test
    void moneyDateAndKeyFormats() {
        assertEquals("KES 2,900.00", BillingText.money("KES", new BigDecimal("2900")));
        assertEquals("KES 0.00", BillingText.money(null, null));
        assertEquals("-KES 300.00", BillingText.money("KES", new BigDecimal("-300")));
        assertEquals("USD 1,234,567.51", BillingText.money("USD", new BigDecimal("1234567.505")));
        assertEquals("24 Nov 2026", BillingText.date(LocalDate.of(2026, 11, 24)));
        assertEquals("1 Dec 2026", BillingText.date(LocalDateTime.of(2026, 12, 1, 23, 59)));
        assertEquals("10 Nov 2026 at 09:31", BillingText.dateTime(LocalDateTime.of(2026, 11, 10, 9, 31, 59)));
        assertEquals("1000.00", BillingText.plain(new BigDecimal("1000")));
        assertEquals("", BillingText.date((LocalDate) null));
    }

    @Test
    void escapesTheFiveSpecialCharacters() {
        assertEquals("&amp;&lt;&gt;&quot;&#39;", ReceiptHtmlRenderer.escape("&<>\"'"));
        assertEquals("", ReceiptHtmlRenderer.escape(null));
        assertEquals("plain text", ReceiptHtmlRenderer.escape("plain text"));
    }

    @Test
    void fileNameDependsOnStatusAndReplacesUnsafeCharacters() {
        assertEquals("receipt-INV-2026-000042.html",
                ReceiptHtmlRenderer.fileName(invoice("INV-2026-000042", "PAID", "KES")));
        assertEquals("invoice-INV-2026-000042.html",
                ReceiptHtmlRenderer.fileName(invoice("INV-2026-000042", "OVERDUE", "KES")));
        assertEquals("invoice-INV_2026__000042_.html",
                ReceiptHtmlRenderer.fileName(invoice("INV/2026\"é000042;", "SENT", "KES")));
    }

    @Test
    void referenceFallsBackFromTransactionToProviderReferenceToDash() {
        Payment withTransaction = Payment.builder().transactionId("TX-1").providerReference("PR-1").build();
        Payment blankTransaction = Payment.builder().transactionId(" ").providerReference("PR-1").build();
        Payment neither = Payment.builder().build();
        assertEquals("TX-1", ReceiptHtmlRenderer.reference(withTransaction));
        assertEquals("PR-1", ReceiptHtmlRenderer.reference(blankTransaction));
        assertEquals("-", ReceiptHtmlRenderer.reference(neither));
    }

    @Test
    void documentFallsBackForMissingNamesAndPrintsRawUnknownLabels() {
        Payment payment = Payment.builder().method("VOUCHER").amount(new BigDecimal("50.00"))
                .refundedAmount(BigDecimal.ZERO).currency(null).createdAt(LocalDateTime.of(2026, 11, 3, 10, 0))
                .build();
        String html = ReceiptHtmlRenderer.render("Clinic", invoice("INV-1", "SENT", null), List.of(payment),
                LocalDateTime.of(2026, 11, 10, 9, 31));

        assertTrue(html.contains("<p id=\"billed-to\">Patient<br>PAT-00001</p>"), html);
        assertFalse(html.contains("id=\"issued-by\""));
        assertFalse(html.contains("id=\"notes\""));
        assertTrue(html.contains("<td>3 Nov 2026</td><td>VOUCHER</td><td>-</td><td class=\"num\">KES 50.00</td>"
                + "<td class=\"num\">-</td>"), html);
        assertTrue(html.contains("<td class=\"pre\">Visit</td><td class=\"num\">2</td>"
                + "<td class=\"num\">KES 1,000.00</td><td class=\"num\">KES 2,000.00</td>"));
        assertTrue(html.contains("<p id=\"generated-at\">Generated on 10 Nov 2026 at 09:31</p>"));
        assertTrue(html.contains("@media print"));
    }
}
