package com.carenexus.api.billing.receipt;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.billing.model.Invoice;
import com.carenexus.api.billing.model.Payment;
import com.carenexus.api.billing.notify.BillingNotifyFixtures;
import com.carenexus.api.billing.repository.InsuranceClaimRepository;
import com.carenexus.api.billing.repository.InvoiceRepository;
import com.carenexus.api.billing.repository.PaymentRepository;
import com.carenexus.api.core.repository.UserSettingsRepository;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * GET /api/billing/invoices/{id}/receipt: the authorization matrix of contract section 10 (patient, other patient,
 * caregiver with canViewBilling / FULL_ACCESS / without the flag / with only canManageBilling / expired / revoked /
 * no grant, issuing and other doctor, admin, no token), never-issued invoices, the success headers, the JSON error
 * envelope whatever the Accept header, one READ / INVOICE audit row per success and no PAYMENT row, the document
 * content (heading, totals, payments rule and order, clinic name) and HTML escaping.
 */
class InvoiceReceiptApiTest extends CommittedIntegrationTest {

    private static final String CSP = "default-src 'none'; style-src 'unsafe-inline'; sandbox";
    private static final String NO_ACCESS = "You don't have active proxy access to this patient";
    private static final String NO_BILLING = "Your proxy access for this patient does not allow viewing billing";

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private InsuranceClaimRepository claimRepository;

    @Autowired
    private UserSettingsRepository userSettingsRepository;

    private BillingNotifyFixtures fixtures;

    private User fullAccess;
    private User managerOnly;
    private User expired;
    private User revoked;
    private User noGrant;

    @BeforeEach
    void setUpCircle() {
        fixtures = new BillingNotifyFixtures(invoiceRepository, paymentRepository, claimRepository,
                userSettingsRepository, jdbc);
        fullAccess = createAndSaveUser(8, "CGV-00003", "full.access@example.com", "CAREGIVER");
        managerOnly = createAndSaveUser(9, "CGV-00004", "manager.only@example.com", "CAREGIVER");
        expired = createAndSaveUser(10, "CGV-00005", "expired@example.com", "CAREGIVER");
        revoked = createAndSaveUser(11, "CGV-00006", "revoked@example.com", "CAREGIVER");
        noGrant = createAndSaveUser(12, "CGV-00007", "no.grant@example.com", "CAREGIVER");
        grantAccess(caregiver, patient, a -> a.setCanViewBilling(true));
        grantAccess(otherCaregiver, patient, a -> a.setCanViewRecords(true));
        grantAccess(fullAccess, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        grantAccess(managerOnly, patient, a -> a.setCanManageBilling(true));
        grantAccess(expired, patient, a -> {
            a.setCanViewBilling(true);
            a.setExpiresAt(LocalDateTime.now().minusDays(1));
        });
        grantAccess(revoked, patient, a -> {
            a.setCanViewBilling(true);
            a.setIsActive(false);
            a.setRevokedAt(LocalDateTime.now().minusHours(1));
        });
    }

    // ==================== HELPERS ====================

    private static String url(Integer invoiceId) {
        return "/api/billing/invoices/" + invoiceId + "/receipt";
    }

    private static String body(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private void assertStatus(int expected, MvcResult result) {
        assertEquals(expected, result.getResponse().getStatus(), body(result));
    }

    private void assertJsonError(MvcResult result, int status, String message) throws Exception {
        assertStatus(status, result);
        assertTrue(result.getResponse().getContentType().startsWith(MediaType.APPLICATION_JSON_VALUE),
                result.getResponse().getContentType());
        assertNull(result.getResponse().getHeader("Content-Disposition"));
        assertNull(result.getResponse().getHeader("Content-Security-Policy"));
        if (message != null) {
            assertEquals(message, message(result));
        }
    }

    /** Text of the element with the given id (the first element carrying it), tags inside removed. */
    private static String element(String html, String id) {
        int idAt = html.indexOf(" id=\"" + id + "\"");
        assertTrue(idAt >= 0, "element " + id + " missing");
        int start = html.indexOf('>', idAt) + 1;
        int tagStart = html.lastIndexOf('<', idAt);
        String tag = html.substring(tagStart + 1, html.indexOf(' ', tagStart)).trim();
        int end = html.indexOf("</" + tag + ">", start);
        return html.substring(start, end);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    private long auditRows(String action, String resourceType, Integer resourceId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ? AND resource_type = ? "
                + "AND resource_id = ?", Long.class, action, resourceType, resourceId);
        return count == null ? 0 : count;
    }

    private Invoice paidInvoice() {
        return fixtures.invoice(patient, doctor, Invoice.STATUS_PAID, LocalDate.of(2026, 11, 24),
                new BigDecimal("2900.00"));
    }

    // ==================== AUTHORIZATION MATRIX ====================

    @Test
    void issuedInvoicesFollowTheInvoiceReadMatrix() throws Exception {
        for (String status : new String[]{Invoice.STATUS_SENT, Invoice.STATUS_PARTIALLY_PAID, Invoice.STATUS_PAID,
                Invoice.STATUS_OVERDUE, Invoice.STATUS_REFUNDED, Invoice.STATUS_CANCELLED}) {
            Invoice invoice = fixtures.invoice(patient, doctor, status, LocalDate.of(2026, 11, 24));
            String url = url(invoice.getInvoiceId());

            for (User allowed : new User[]{patient, caregiver, fullAccess, doctor, admin}) {
                MvcResult result = doGet(url, allowed);
                assertStatus(200, result);
                assertTrue(result.getResponse().getContentType().startsWith("text/html"), status);
            }
            assertJsonError(doGet(url, otherPatient), 403, "Patients can only access their own data");
            assertJsonError(doGet(url, otherCaregiver), 403, NO_BILLING);
            assertJsonError(doGet(url, managerOnly), 403, NO_BILLING);
            assertJsonError(doGet(url, expired), 403, NO_ACCESS);
            assertJsonError(doGet(url, revoked), 403, NO_ACCESS);
            assertJsonError(doGet(url, noGrant), 403, NO_ACCESS);
            assertJsonError(doGet(url, otherDoctor), 403, "You can only view invoices you issued");
            MvcResult anonymous = mockMvc.perform(get(url)).andReturn();
            assertEquals(401, anonymous.getResponse().getStatus());
        }
    }

    @Test
    void neverIssuedInvoicesAnswer404ToPatientsAndCaregiversOnly() throws Exception {
        Invoice draft = fixtures.invoice(patient, doctor, Invoice.STATUS_DRAFT, null);
        Invoice voidedDraft = fixtures.invoice(patient, doctor, Invoice.STATUS_DRAFT, null);
        jdbc.update("UPDATE invoices SET status = 'CANCELLED', cancel_reason = 'Duplicate' WHERE invoice_id = ?",
                voidedDraft.getInvoiceId());

        for (Invoice invoice : new Invoice[]{draft, voidedDraft}) {
            String url = url(invoice.getInvoiceId());
            assertJsonError(doGet(url, patient), 404, "Invoice not found");
            assertJsonError(doGet(url, caregiver), 404, "Invoice not found");
            assertJsonError(doGet(url, fullAccess), 404, "Invoice not found");
            assertJsonError(doGet(url, otherPatient), 403, "Patients can only access their own data");
            assertJsonError(doGet(url, otherCaregiver), 403, NO_BILLING);
            assertJsonError(doGet(url, otherDoctor), 403, "You can only view invoices you issued");
            assertStatus(200, doGet(url, doctor));
            assertStatus(200, doGet(url, admin));
        }
        String html = body(doGet(url(draft.getInvoiceId()), doctor));
        assertEquals("Invoice", element(html, "doc-title"));
        assertEquals("Draft", element(html, "invoice-status"));
        assertEquals("Not issued", element(html, "issued-on"));
        String voided = body(doGet(url(voidedDraft.getInvoiceId()), admin));
        assertEquals("Cancelled", element(voided, "invoice-status"));
        assertEquals("Duplicate", element(voided, "cancel-reason"));
    }

    @Test
    void unknownAndMalformedIdsAnswerTheJsonEnvelopeEvenForAnHtmlAcceptHeader() throws Exception {
        assertJsonError(doGet(url(999_999), admin), 404, "Invoice not found");
        assertJsonError(doGet("/api/billing/invoices/abc/receipt", admin), 400, "Invalid value for parameter 'id'");

        Invoice invoice = paidInvoice();
        MvcResult htmlAccept = call(get(url(invoice.getInvoiceId())).accept(MediaType.TEXT_HTML), otherPatient, null);
        assertJsonError(htmlAccept, 403, "Patients can only access their own data");
        assertEquals("INVOICE", json(htmlAccept).path("resourceType").asText());
        assertEquals(invoice.getInvoiceId().intValue(), json(htmlAccept).path("resourceId").asInt());
        MvcResult notFound = call(get(url(999_999)).accept(MediaType.TEXT_HTML), admin, null);
        assertJsonError(notFound, 404, "Invoice not found");

        // Success is text/html whatever the Accept header says: no 406.
        MvcResult jsonAccept = call(get(url(invoice.getInvoiceId())).accept(MediaType.APPLICATION_JSON), patient, null);
        assertStatus(200, jsonAccept);
        assertTrue(jsonAccept.getResponse().getContentType().startsWith("text/html"));
    }

    // ==================== HEADERS AND AUDIT ====================

    @Test
    void successCarriesAttachmentAndSecurityHeadersAndOneReadAuditRow() throws Exception {
        Invoice invoice = paidInvoice();
        Payment payment = fixtures.payment(invoice, Payment.STATUS_COMPLETED, "MPESA", new BigDecimal("2900.00"),
                BigDecimal.ZERO, "LOC-8K2M4QX7P1ZD", null, LocalDateTime.of(2026, 11, 10, 9, 16),
                patient.getSystemId());

        MvcResult result = doGet(url(invoice.getInvoiceId()), caregiver);

        assertStatus(200, result);
        assertEquals("text/html;charset=UTF-8", result.getResponse().getContentType().replace(" ", ""));
        assertEquals("attachment; filename=\"receipt-" + invoice.getInvoiceNo() + ".html\"",
                result.getResponse().getHeader("Content-Disposition"));
        assertEquals("private, no-store", result.getResponse().getHeader("Cache-Control"));
        assertEquals(CSP, result.getResponse().getHeader("Content-Security-Policy"));
        assertEquals("nosniff", result.getResponse().getHeader("X-Content-Type-Options"));
        assertEquals(result.getResponse().getContentAsByteArray().length, result.getResponse().getContentLength());

        assertEquals(1, auditRows("READ", "INVOICE", invoice.getInvoiceId()));
        assertEquals(0, auditRows("READ", "PAYMENT", payment.getPaymentId()));
        Long paymentAudits = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE resource_type = 'PAYMENT'",
                Long.class);
        assertEquals(0L, paymentAudits);

        // A refused request writes no READ row.
        doGet(url(invoice.getInvoiceId()), otherPatient);
        assertEquals(1, auditRows("READ", "INVOICE", invoice.getInvoiceId()));
    }

    // ==================== CONTENT ====================

    @Test
    void paidReceiptListsSettledPaymentsInOrderAndTheInvoiceTotals() throws Exception {
        MvcResult setting = doPut("/api/admin/settings/clinic.name", admin, Map.of("value", "Harare Family Clinic"));
        assertStatus(200, setting);
        Invoice invoice = paidInvoice();
        jdbc.update("UPDATE invoices SET issued_at = ?, paid_at = ? WHERE invoice_id = ?",
                LocalDateTime.of(2026, 11, 10, 9, 0), LocalDateTime.of(2026, 11, 10, 9, 20), invoice.getInvoiceId());
        Payment later = fixtures.payment(invoice, Payment.STATUS_REFUNDED, "CASH", new BigDecimal("400.00"),
                new BigDecimal("400.00"), null, "RCPT-2", LocalDateTime.of(2026, 11, 12, 8, 0),
                admin.getSystemId());
        Payment first = fixtures.payment(invoice, Payment.STATUS_COMPLETED, "MPESA", new BigDecimal("2500.00"),
                new BigDecimal("100.00"), "LOC-AAA", "PROV-IGNORED", LocalDateTime.of(2026, 11, 10, 9, 16),
                patient.getSystemId());
        fixtures.payment(invoice, Payment.STATUS_FAILED, "CARD", new BigDecimal("2900.00"), BigDecimal.ZERO,
                null, "FAILED-REF", null, patient.getSystemId());
        fixtures.payment(invoice, Payment.STATUS_PENDING, "MPESA", new BigDecimal("2900.00"), BigDecimal.ZERO,
                null, "PENDING-REF", null, patient.getSystemId());

        String html = body(doGet(url(invoice.getInvoiceId()), patient));

        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.contains("<html lang=\"en\">"));
        assertTrue(html.contains("<title>Receipt " + invoice.getInvoiceNo() + "</title>"));
        assertEquals("Harare Family Clinic", element(html, "clinic-name"));
        assertEquals("Receipt", element(html, "doc-title"));
        assertEquals(invoice.getInvoiceNo(), element(html, "invoice-no"));
        assertEquals("Paid", element(html, "invoice-status"));
        assertEquals("10 Nov 2026", element(html, "issued-on"));
        assertEquals("24 Nov 2026", element(html, "due-date"));
        assertEquals("10 Nov 2026", element(html, "paid-on"));
        assertEquals("PATIENT User1<br>PAT-00001", element(html, "billed-to"));
        assertEquals("Dr. DOCTOR User2", element(html, "issued-by"));

        String lines = element(html, "line-items");
        assertTrue(lines.contains("<td class=\"pre\">Video consultation</td><td class=\"num\">1</td>"
                + "<td class=\"num\">KES 2,000.00</td><td class=\"num\">KES 2,000.00</td>"), lines);
        assertTrue(lines.indexOf("Video consultation") < lines.indexOf("Medical report"));

        String totals = element(html, "totals");
        assertTrue(totals.contains("<th>Subtotal</th><td class=\"num\">KES 2,500.00</td>"), totals);
        assertFalse(totals.contains("Discount"));
        assertTrue(totals.contains("<th>Tax</th><td class=\"num\">KES 400.00</td>"));
        assertTrue(totals.contains("<th>Total</th><td class=\"num\">KES 2,900.00</td>"));
        assertTrue(totals.contains("<th>Amount paid</th><td class=\"num\">KES 2,900.00</td>"));
        assertTrue(totals.contains("<th>Balance due</th><td class=\"num\">KES 0.00</td>"));
        assertTrue(totals.indexOf("Subtotal") < totals.indexOf("Tax")
                && totals.indexOf("Tax") < totals.indexOf("<th>Total")
                && totals.indexOf("<th>Total") < totals.indexOf("Amount paid")
                && totals.indexOf("Amount paid") < totals.indexOf("Balance due"));

        String payments = element(html, "payments");
        assertEquals(2, occurrences(payments, "<tr><td>"), payments);
        assertTrue(payments.contains("<tr><td>10 Nov 2026</td><td>M-PESA</td><td>LOC-AAA</td>"
                + "<td class=\"num\">KES 2,500.00</td><td class=\"num\">KES 100.00</td></tr>"), payments);
        assertTrue(payments.contains("<tr><td>12 Nov 2026</td><td>Cash</td><td>RCPT-2</td>"
                + "<td class=\"num\">KES 400.00</td><td class=\"num\">KES 400.00</td></tr>"), payments);
        assertTrue(payments.indexOf("LOC-AAA") < payments.indexOf("RCPT-2"), "paidAt ascending");
        assertFalse(html.contains("FAILED-REF"));
        assertFalse(html.contains("PENDING-REF"));
        assertFalse(html.contains("PROV-IGNORED"));
        assertTrue(element(html, "generated-at").matches("Generated on \\d{1,2} [A-Z][a-z]{2} \\d{4} at \\d{2}:\\d{2}"));
        assertTrue(first.getPaymentId() > 0 && later.getPaymentId() > 0);

        // Self-contained: no script, no link, no URL.
        assertFalse(html.contains("<script"));
        assertFalse(html.contains("<link"));
        assertFalse(html.contains("<img"));
        assertFalse(html.contains("href="));
        assertFalse(html.contains("http"));
    }

    @Test
    void unpaidInvoiceIsAnInvoiceWithDiscountAndNoPayments() throws Exception {
        Invoice invoice = fixtures.invoice(patient, doctor, Invoice.STATUS_SENT, LocalDate.of(2026, 11, 24));
        jdbc.update("UPDATE invoices SET discount = 300.00, total = 2600.00, line_items = '[]'::jsonb, "
                + "notes = ? WHERE invoice_id = ?", "Line one\nLine two", invoice.getInvoiceId());
        String number = invoice.getInvoiceNo();

        MvcResult result = doGet(url(invoice.getInvoiceId()), patient);

        assertEquals("attachment; filename=\"invoice-" + number + ".html\"",
                result.getResponse().getHeader("Content-Disposition"));
        String html = body(result);
        assertEquals("Invoice", element(html, "doc-title"));
        assertEquals("Sent", element(html, "invoice-status"));
        assertTrue(element(html, "line-items").contains("No items"));
        String totals = element(html, "totals");
        assertTrue(totals.contains("<th>Discount</th><td class=\"num\">-KES 300.00</td>"), totals);
        assertTrue(totals.contains("<th>Balance due</th><td class=\"num\">KES 2,600.00</td>"), totals);
        assertEquals("No payments recorded.", element(html, "payments"));
        assertTrue(html.contains("<p id=\"payments\">No payments recorded.</p>"));
        assertEquals("Line one\nLine two", element(html, "notes"));
        assertFalse(html.contains("id=\"paid-on\""));
        assertFalse(html.contains("id=\"cancel-reason\""));
        assertEquals("CareNexus", element(html, "clinic-name"));
    }

    @Test
    void everyStoredValueIsEscaped() throws Exception {
        String attack = "<script>alert(1)</script>\" onload=\"x";
        jdbc.update("UPDATE users SET first_name = ? WHERE system_id = ?", attack, patient.getSystemId());
        Invoice invoice = fixtures.invoice(patient, doctor, Invoice.STATUS_SENT, LocalDate.of(2026, 11, 24));
        String lines = "[{\"description\":\"<script>alert(1)</script>\\\" onload=\\\"x\",\"quantity\":1,"
                + "\"unitPrice\":2500.00,\"amount\":2500.00}]";
        jdbc.update("UPDATE invoices SET line_items = ?::jsonb, notes = ? WHERE invoice_id = ?", lines, attack,
                invoice.getInvoiceId());
        fixtures.payment(invoice, Payment.STATUS_COMPLETED, "CARD", new BigDecimal("100.00"), BigDecimal.ZERO,
                "<script>alert(2)</script>", null, LocalDateTime.of(2026, 11, 10, 9, 0), patient.getSystemId());
        jdbc.update("UPDATE invoices SET status = 'PARTIALLY_PAID', amount_paid = 100.00 WHERE invoice_id = ?",
                invoice.getInvoiceId());
        assertStatus(200, doPut("/api/admin/settings/clinic.name", admin, Map.of("value", "<b>Clinic & Co</b>")));

        String html = body(doGet(url(invoice.getInvoiceId()), patient));

        assertFalse(html.contains("<script"), html);
        assertFalse(html.contains("onload=\""));
        assertFalse(html.contains("<b>"));
        String escaped = "&lt;script&gt;alert(1)&lt;/script&gt;&quot; onload=&quot;x";
        assertTrue(element(html, "billed-to").startsWith(escaped), element(html, "billed-to"));
        assertTrue(element(html, "line-items").contains(escaped));
        assertEquals(escaped, element(html, "notes"));
        assertTrue(element(html, "payments").contains("&lt;script&gt;alert(2)&lt;/script&gt;"));
        assertEquals("&lt;b&gt;Clinic &amp; Co&lt;/b&gt;", element(html, "clinic-name"));
    }
}
