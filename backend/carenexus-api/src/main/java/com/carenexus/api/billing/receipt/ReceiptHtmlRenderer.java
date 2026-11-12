package com.carenexus.api.billing.receipt;

import com.carenexus.api.billing.invoice.dto.InvoiceLineItem;
import com.carenexus.api.billing.invoice.dto.InvoiceResponse;
import com.carenexus.api.billing.model.Invoice;
import com.carenexus.api.billing.model.Payment;
import com.carenexus.api.billing.notify.BillingText;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Builds the printable invoice / receipt document (contract be-billing-notify-receipt, section 4.3): one complete,
 * self-contained HTML5 page with one inline style element, no script, no event handler, no link, no image, no URL.
 * Every value from the database or from settings is HTML-escaped and placed only in element text.
 * <p>
 * Stateless and thread-safe.
 */
public final class ReceiptHtmlRenderer {

    /** Heading and title of a PAID invoice. */
    static final String RECEIPT = "Receipt";
    /** Heading and title of an invoice in any other status. */
    static final String INVOICE = "Invoice";

    static final Map<String, String> STATUS_LABELS = Map.of(
            Invoice.STATUS_DRAFT, "Draft",
            Invoice.STATUS_SENT, "Sent",
            Invoice.STATUS_PARTIALLY_PAID, "Partially paid",
            Invoice.STATUS_PAID, "Paid",
            Invoice.STATUS_OVERDUE, "Overdue",
            Invoice.STATUS_CANCELLED, "Cancelled",
            Invoice.STATUS_REFUNDED, "Refunded");

    static final Map<String, String> METHOD_LABELS = Map.of(
            "CARD", "Card",
            "MPESA", "M-PESA",
            "CASH", "Cash",
            "BANK_TRANSFER", "Bank transfer",
            "INSURANCE", "Insurance");

    private static final String STYLE = """
              body { font-family: Arial, Helvetica, sans-serif; color: #111; background: #fff; margin: 24px; font-size: 14px; }
              h1 { font-size: 22px; margin: 0 0 4px; }
              h2 { font-size: 16px; margin: 16px 0 4px; }
              p { margin: 2px 0; }
              table { width: 100%; border-collapse: collapse; margin: 12px 0; }
              th, td { text-align: left; padding: 6px 8px; border-bottom: 1px solid #ccc; vertical-align: top; }
              td.num, th.num { text-align: right; }
              .pre { white-space: pre-wrap; }
              footer { margin-top: 24px; color: #444; font-size: 12px; }
              @page { size: A4; margin: 16mm; }
              @media print { body { margin: 0; color: #000; background: #fff; } th, td { border-bottom-color: #999; } }
            """;

    private ReceiptHtmlRenderer() {
    }

    /** Whether the document is a receipt (PAID) rather than an invoice. */
    static boolean isReceipt(InvoiceResponse invoice) {
        return Invoice.STATUS_PAID.equals(invoice.status());
    }

    /** {@code receipt-<invoiceNo>.html} or {@code invoice-<invoiceNo>.html}; characters outside [A-Za-z0-9-] become _. */
    static String fileName(InvoiceResponse invoice) {
        String number = invoice.invoiceNo() == null ? String.valueOf(invoice.invoiceId()) : invoice.invoiceNo();
        String safe = number.replaceAll("[^A-Za-z0-9-]", "_");
        return (isReceipt(invoice) ? "receipt-" : "invoice-") + safe + ".html";
    }

    /**
     * @param clinicName  header line; never blank
     * @param invoice     the invoice as the caller may read it
     * @param payments    settled payments (COMPLETED, REFUNDED) in print order
     * @param generatedAt clinic wall-clock time of this rendering
     */
    static String render(String clinicName, InvoiceResponse invoice, List<Payment> payments, LocalDateTime generatedAt) {
        String heading = isReceipt(invoice) ? RECEIPT : INVOICE;
        String currency = invoice.currency();
        StringBuilder html = new StringBuilder(4096);
        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
                .append("<meta charset=\"UTF-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>").append(heading).append(' ').append(escape(invoice.invoiceNo())).append("</title>\n")
                .append("<style>\n").append(STYLE).append("</style>\n")
                .append("</head>\n<body>\n");

        // ---- header ----
        html.append("<header>\n");
        html.append("  <p id=\"clinic-name\">").append(escape(clinicName)).append("</p>\n");
        html.append("  <h1 id=\"doc-title\">").append(heading).append("</h1>\n");
        html.append("  <p>Number: <span id=\"invoice-no\">").append(escape(invoice.invoiceNo())).append("</span></p>\n");
        html.append("  <p>Status: <span id=\"invoice-status\">").append(escape(label(STATUS_LABELS, invoice.status())))
                .append("</span></p>\n");
        html.append("  <p>Issued: <span id=\"issued-on\">")
                .append(invoice.issuedAt() == null ? "Not issued" : escape(BillingText.date(invoice.issuedAt())))
                .append("</span></p>\n");
        if (invoice.dueDate() != null) {
            html.append("  <p>Due: <span id=\"due-date\">").append(escape(BillingText.date(invoice.dueDate())))
                    .append("</span></p>\n");
        }
        if (invoice.paidAt() != null) {
            html.append("  <p>Paid: <span id=\"paid-on\">").append(escape(BillingText.date(invoice.paidAt())))
                    .append("</span></p>\n");
        }
        html.append("</header>\n");

        // ---- parties ----
        html.append("<section>\n");
        html.append("  <h2>Billed to</h2>\n");
        String patientName = invoice.patientName() == null || invoice.patientName().isBlank()
                ? "Patient" : invoice.patientName();
        html.append("  <p id=\"billed-to\">").append(escape(patientName)).append("<br>")
                .append(escape(invoice.patientSystemId())).append("</p>\n");
        if (invoice.doctorName() != null && !invoice.doctorName().isBlank()) {
            html.append("  <h2>Issued by</h2>\n");
            html.append("  <p id=\"issued-by\">Dr. ").append(escape(invoice.doctorName())).append("</p>\n");
        }
        html.append("</section>\n");

        // ---- line items ----
        html.append("<table id=\"line-items\">\n")
                .append("  <thead><tr><th>Description</th><th class=\"num\">Quantity</th>")
                .append("<th class=\"num\">Unit price</th><th class=\"num\">Amount</th></tr></thead>\n")
                .append("  <tbody>\n");
        List<InvoiceLineItem> lines = invoice.lineItems() == null ? List.of() : invoice.lineItems();
        if (lines.isEmpty()) {
            html.append("    <tr><td colspan=\"4\">No items</td></tr>\n");
        }
        for (InvoiceLineItem line : lines) {
            html.append("    <tr><td class=\"pre\">").append(escape(line.description())).append("</td>")
                    .append("<td class=\"num\">").append(line.quantity() == null ? "" : line.quantity()).append("</td>")
                    .append("<td class=\"num\">").append(moneyCell(currency, line.unitPrice())).append("</td>")
                    .append("<td class=\"num\">").append(moneyCell(currency, line.amount())).append("</td></tr>\n");
        }
        html.append("  </tbody>\n</table>\n");

        // ---- totals ----
        html.append("<table id=\"totals\">\n  <tbody>\n");
        totalRow(html, "Subtotal", currency, invoice.subtotal());
        if (invoice.discount() != null && invoice.discount().signum() > 0) {
            totalRow(html, "Discount", currency, invoice.discount().negate());
        }
        totalRow(html, "Tax", currency, invoice.tax());
        totalRow(html, "Total", currency, invoice.total());
        totalRow(html, "Amount paid", currency, invoice.amountPaid());
        totalRow(html, "Balance due", currency, invoice.outstanding());
        html.append("  </tbody>\n</table>\n");

        // ---- payments ----
        html.append("<h2>Payments</h2>\n");
        if (payments == null || payments.isEmpty()) {
            html.append("<p id=\"payments\">No payments recorded.</p>\n");
        } else {
            html.append("<table id=\"payments\">\n")
                    .append("  <thead><tr><th>Date</th><th>Method</th><th>Reference</th>")
                    .append("<th class=\"num\">Amount</th><th class=\"num\">Refunded</th></tr></thead>\n")
                    .append("  <tbody>\n");
            for (Payment payment : payments) {
                LocalDateTime when = payment.getPaidAt() != null ? payment.getPaidAt() : payment.getCreatedAt();
                BigDecimal refunded = payment.getRefundedAmount();
                html.append("    <tr><td>").append(escape(BillingText.date(when))).append("</td>")
                        .append("<td>").append(escape(label(METHOD_LABELS, payment.getMethod()))).append("</td>")
                        .append("<td>").append(escape(reference(payment))).append("</td>")
                        .append("<td class=\"num\">").append(moneyCell(payment.getCurrency(), payment.getAmount()))
                        .append("</td>")
                        .append("<td class=\"num\">")
                        .append(refunded != null && refunded.signum() > 0
                                ? moneyCell(payment.getCurrency(), refunded) : "-")
                        .append("</td></tr>\n");
            }
            html.append("  </tbody>\n</table>\n");
        }

        // ---- reason and notes ----
        if (Invoice.STATUS_CANCELLED.equals(invoice.status()) && invoice.cancelReason() != null
                && !invoice.cancelReason().isBlank()) {
            html.append("<h2>Reason for cancellation</h2>\n");
            html.append("<p id=\"cancel-reason\" class=\"pre\">").append(escape(invoice.cancelReason())).append("</p>\n");
        }
        if (invoice.notes() != null && !invoice.notes().isBlank()) {
            html.append("<h2>Notes</h2>\n");
            html.append("<p id=\"notes\" class=\"pre\">").append(escape(invoice.notes())).append("</p>\n");
        }

        // ---- footer ----
        html.append("<footer>\n  <p id=\"generated-at\">Generated on ")
                .append(escape(BillingText.dateTime(generatedAt))).append("</p>\n</footer>\n");
        html.append("</body>\n</html>\n");
        return html.toString();
    }

    /** transactionId, else providerReference, else "-". */
    static String reference(Payment payment) {
        if (payment.getTransactionId() != null && !payment.getTransactionId().isBlank()) {
            return payment.getTransactionId();
        }
        if (payment.getProviderReference() != null && !payment.getProviderReference().isBlank()) {
            return payment.getProviderReference();
        }
        return "-";
    }

    /**
     * Escapes text for an HTML element body: {@code & < > " '} become entities. Null is empty.
     */
    static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static void totalRow(StringBuilder html, String label, String currency, BigDecimal amount) {
        html.append("    <tr><th>").append(label).append("</th><td class=\"num\">").append(moneyCell(currency, amount))
                .append("</td></tr>\n");
    }

    private static String moneyCell(String currency, BigDecimal amount) {
        return escape(BillingText.money(currency, amount));
    }

    private static String label(Map<String, String> labels, String value) {
        if (value == null) {
            return "";
        }
        return labels.getOrDefault(value, value);
    }
}
