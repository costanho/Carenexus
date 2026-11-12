package com.carenexus.api.releasegates.crossflow2;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.billing.config.BillingProperties;
import com.carenexus.api.billing.job.InvoiceOverdueSweeper;
import com.carenexus.api.billing.provider.gateway.LocalWebhookSigner;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.carenexus.api.releasegates.crossflow2.CrossFlow2Fixtures.clinicNow;
import static com.carenexus.api.releasegates.crossflow2.CrossFlow2Fixtures.clinicToday;
import static com.carenexus.api.releasegates.crossflow2.CrossFlow2Fixtures.count;
import static com.carenexus.api.releasegates.crossflow2.CrossFlow2Fixtures.insertAppointment;
import static com.carenexus.api.releasegates.crossflow2.CrossFlow2Fixtures.ofType;
import static com.carenexus.api.releasegates.crossflow2.CrossFlow2Fixtures.onlyWithKey;
import static com.carenexus.api.releasegates.crossflow2.CrossFlow2Fixtures.withKey;
import static com.carenexus.api.releasegates.crossflow2.CrossFlow2Fixtures.withKeyPrefix;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Cross-slice gate of billing, end to end through HTTP with committed transactions: completing a consultation
 * (be-consultations) creates exactly one invoice through the auto-invoice listener (be-billing-invoices), the doctor
 * issues it, the patient pays it through the local gateway (be-billing-payments) and the signed M-PESA webhook settles
 * it, the invoice becomes PAID and the billing listener (be-billing-notify-receipt) notifies each recipient once per
 * kind (API-NOTES: per kind, the payer of a COMPLETED payment is skipped). A manual payment (be-billing-payment-methods)
 * and a two-step claim approval (be-billing-claims) settle further invoices, the overdue sweeper notifies once, and a
 * caregiver without a billing flag receives nothing and reads nothing.
 */
class BillingEndToEndReleaseGateTest extends CommittedIntegrationTest {

    private static final String CONSULTATIONS = "/api/consultations";
    private static final String DOCTOR_CONSULTATIONS = "/api/doctors/consultations";
    private static final String INVOICES = "/api/billing/invoices";
    private static final String WEBHOOK = "/api/integrations/payments/local/webhook";
    private static final String ADMIN_CLAIMS = "/api/admin/billing/claims";

    private static final String ISSUED = "INVOICE_ISSUED:";
    private static final String RECEIVED = "PAYMENT_RECEIVED:";
    private static final String OVERDUE = "INVOICE_OVERDUE:";
    private static final String PAID = "INVOICE_PAID:";
    private static final String CLAIM = "CLAIM_STATUS:";

    @Autowired
    private InvoiceOverdueSweeper overdueSweeper;

    @Autowired
    private BillingProperties billingProperties;

    private int visitsOpened;

    @BeforeEach
    void billingCircle() {
        // Sarah may see billing; the other caregiver follows the patient's visits but has no billing flag
        grantAccess(caregiver, patient, a -> a.setCanViewBilling(true));
        grantAccess(otherCaregiver, patient, a -> {
            a.setCanViewAppointments(true);
            a.setCanViewConsultations(true);
        });
        visitsOpened = 0;
    }

    // ==================== HELPERS ====================

    /** A visit that started a little earlier today, with an IN_PROGRESS consultation; returns the consultation id. */
    private int openVisit(String type) throws Exception {
        visitsOpened++;
        int appointmentId = insertAppointment(jdbc, patient.getSystemId(), doctor.getSystemId(),
                clinicNow().minusMinutes(20L + 60L * visitsOpened), 30, type, "SCHEDULED");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appointmentId", appointmentId);
        body.put("status", "IN_PROGRESS");
        body.put("chiefComplaint", "Follow-up of blood pressure");
        body.put("diagnosis", "Essential hypertension, controlled");
        MvcResult created = doPost(CONSULTATIONS, doctor, body);
        assertEquals(201, created.getResponse().getStatus(), created.getResponse().getContentAsString());
        return json(created).path("consultationId").asInt();
    }

    private void complete(int consultationId) throws Exception {
        MvcResult completed = doPatch(DOCTOR_CONSULTATIONS + "/" + consultationId + "/mark-completed", doctor, null);
        assertEquals(200, completed.getResponse().getStatus(), completed.getResponse().getContentAsString());
        assertEquals("COMPLETED", json(completed).path("status").asText());
    }

    /** Invoice ids of a consultation (any status). */
    private List<Integer> invoicesOf(int consultationId) {
        return jdbc.queryForList("SELECT invoice_id FROM invoices WHERE consultation_id = ? ORDER BY invoice_id",
                Integer.class, consultationId);
    }

    /** Completes a visit of this type and returns its single (auto-created) invoice, issued by the doctor. */
    private JsonNode issuedInvoiceOfNewVisit(String type) throws Exception {
        int consultationId = openVisit(type);
        complete(consultationId);
        List<Integer> invoices = invoicesOf(consultationId);
        assertEquals(1, invoices.size(), "one auto-invoice per completed consultation");
        return ok(doPost(INVOICES + "/" + invoices.get(0) + "/send", doctor, null), 200);
    }

    private JsonNode ok(MvcResult result, int status) throws Exception {
        assertEquals(status, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result);
    }

    private String invoiceStatus(int invoiceId) {
        return jdbc.queryForObject("SELECT status FROM invoices WHERE invoice_id = ?", String.class, invoiceId);
    }

    private BigDecimal amountPaid(int invoiceId) {
        return jdbc.queryForObject("SELECT amount_paid FROM invoices WHERE invoice_id = ?", BigDecimal.class,
                invoiceId);
    }

    private String webhookEvent(String eventId, String reference, String transactionId) throws Exception {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("eventId", eventId);
        node.put("eventType", "PAYMENT_COMPLETED");
        node.put("providerReference", reference);
        node.put("transactionId", transactionId);
        node.putNull("failureReason");
        node.put("occurredAt", "2026-11-10T06:21:07Z");
        return objectMapper.writeValueAsString(node);
    }

    private MvcResult webhook(String body, String signature) throws Exception {
        return mockMvc.perform(post(WEBHOOK).contentType(MediaType.APPLICATION_JSON).content(body)
                .header(LocalWebhookSigner.HEADER, signature)).andReturn();
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "was " + actual);
    }

    private static void assertMoney(String expected, JsonNode node) {
        assertEquals(0, new BigDecimal(expected).compareTo(node.decimalValue()), "was " + node);
    }

    /** Nobody outside the billing circle holds any PAYMENT notification. */
    private void assertNothingOutsideTheBillingCircle() {
        for (User outsider : List.of(otherCaregiver, otherPatient, otherDoctor, admin)) {
            assertEquals(0, ofType(jdbc, outsider.getSystemId(), "PAYMENT"), "PAYMENT rows of " + outsider.getSystemId());
        }
    }

    // ==================== CONSULTATION TO PAID INVOICE ====================

    @Test
    void completedConsultation_isInvoicedOnce_paidThroughTheSignedMpesaWebhook_andNotifiesOncePerKind()
            throws Exception {
        // ---- the consultation is completed: the auto-invoice listener writes exactly one DRAFT invoice ----
        int consultationId = openVisit("VIDEO");
        complete(consultationId);
        List<Integer> invoices = invoicesOf(consultationId);
        assertEquals(1, invoices.size());
        int invoiceId = invoices.get(0);
        Map<String, Object> draft = jdbc.queryForMap("SELECT status, patient_system_id, doctor_system_id, currency, "
                + "total, amount_paid FROM invoices WHERE invoice_id = ?", invoiceId);
        assertEquals("DRAFT", draft.get("status"), "auto-invoice mode " + billingProperties.getAutoInvoice());
        assertEquals(patient.getSystemId(), draft.get("patient_system_id"));
        assertEquals(doctor.getSystemId(), draft.get("doctor_system_id"));
        assertEquals("KES", draft.get("currency"));
        assertMoney("1500.00", (BigDecimal) draft.get("total"));
        assertEquals(0, ofType(jdbc, patient.getSystemId(), "PAYMENT"), "a draft notifies nobody");

        // A repeated completion creates nothing more
        complete(consultationId);
        assertEquals(List.of(invoiceId), invoicesOf(consultationId));

        // ---- the doctor issues it: one "New invoice" per member of the billing circle ----
        JsonNode sent = ok(doPost(INVOICES + "/" + invoiceId + "/send", doctor, null), 200);
        assertEquals("SENT", sent.path("status").asText());
        String invoiceNo = sent.path("invoiceNo").asText();
        for (User member : List.of(patient, caregiver)) {
            Map<String, Object> row = onlyWithKey(jdbc, member.getSystemId(), ISSUED + invoiceId);
            assertEquals("PAYMENT", row.get("type"));
            assertEquals("New invoice", row.get("title"));
            assertTrue(((String) row.get("message")).startsWith("Invoice " + invoiceNo + " for KES "),
                    (String) row.get("message"));
        }
        assertEquals(0, withKeyPrefix(jdbc, doctor.getSystemId(), ISSUED), "the doctor issued it");
        assertNothingOutsideTheBillingCircle();

        // The billing circle reads it; the caregiver without a billing flag and outsiders are refused
        assertEquals(200, doGet(INVOICES + "/" + invoiceId, patient).getResponse().getStatus());
        assertEquals(200, doGet(INVOICES + "/" + invoiceId, caregiver).getResponse().getStatus());
        for (User refused : List.of(otherCaregiver, otherPatient, otherDoctor)) {
            assertEquals(403, doGet(INVOICES + "/" + invoiceId, refused).getResponse().getStatus(),
                    "invoice read by " + refused.getSystemId());
        }

        // ---- the patient pays with M-PESA through the local gateway (idempotent) ----
        String key = "gate-pay-" + System.nanoTime();
        Map<String, Object> pay = Map.of("method", "MPESA", "payerPhone", "+254 712 344 567");
        JsonNode payment = ok(doPostWithHeaders(INVOICES + "/" + invoiceId + "/payments", patient, pay,
                Map.of("Idempotency-Key", key)), 201);
        assertEquals("PENDING", payment.path("status").asText());
        assertEquals("STK_PUSH", payment.path("nextAction").asText());
        int paymentId = payment.path("paymentId").asInt();
        String reference = payment.path("providerReference").asText();
        JsonNode replay = ok(doPostWithHeaders(INVOICES + "/" + invoiceId + "/payments", patient, pay,
                Map.of("Idempotency-Key", key)), 200);
        assertEquals(paymentId, replay.path("paymentId").asInt());
        assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM payments WHERE invoice_id = ?", invoiceId));
        assertEquals("SENT", invoiceStatus(invoiceId), "nothing is settled before the gateway confirms");
        assertMoney("0.00", amountPaid(invoiceId));
        assertEquals(0, withKeyPrefix(jdbc, caregiver.getSystemId(), RECEIVED));

        // ---- the gateway calls back: an unsigned callback is refused, the signed one settles the invoice ----
        String event = webhookEvent("evt_gate_mpesa_1", reference, "LOC-GATE5MPESA1");
        String secret = billingProperties.getLocal().getWebhookSecret();
        MvcResult forged = webhook(event, "sha256=" + "0".repeat(64));
        assertEquals(401, forged.getResponse().getStatus(), forged.getResponse().getContentAsString());
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM payments WHERE payment_id = ?", String.class,
                paymentId));

        MvcResult accepted = webhook(event, LocalWebhookSigner.sign(secret, event));
        assertEquals(200, accepted.getResponse().getStatus(), accepted.getResponse().getContentAsString());
        // The same delivery again is acknowledged and applied once
        assertEquals(200, webhook(event, LocalWebhookSigner.sign(secret, event)).getResponse().getStatus());
        assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM payment_events WHERE event_id = 'evt_gate_mpesa_1'"));

        JsonNode settled = ok(doGet("/api/billing/payments/" + paymentId, patient), 200);
        assertEquals("COMPLETED", settled.path("status").asText());
        assertEquals("LOC-GATE5MPESA1", settled.path("transactionId").asText());
        JsonNode paidInvoice = ok(doGet(INVOICES + "/" + invoiceId, patient), 200);
        assertEquals("PAID", paidInvoice.path("status").asText());
        assertMoney("1500.00", paidInvoice.path("amountPaid"));

        // ---- exactly one notification per kind and recipient ----
        assertEquals(0, withKey(jdbc, patient.getSystemId(), RECEIVED + paymentId), "the payer is not told");
        assertEquals(1, withKey(jdbc, caregiver.getSystemId(), RECEIVED + paymentId));
        assertEquals(1, withKey(jdbc, doctor.getSystemId(), PAID + invoiceId));
        assertEquals(0, withKeyPrefix(jdbc, patient.getSystemId(), PAID));
        assertEquals(0, withKeyPrefix(jdbc, caregiver.getSystemId(), PAID));
        for (User member : List.of(patient, caregiver)) {
            assertEquals(1, withKey(jdbc, member.getSystemId(), ISSUED + invoiceId), member.getSystemId());
            assertEquals(0, withKeyPrefix(jdbc, member.getSystemId(), "PAYMENT_FAILED:"));
        }
        assertEquals(2, ofType(jdbc, caregiver.getSystemId(), "PAYMENT"), "New invoice and Payment received");
        assertEquals(1, ofType(jdbc, patient.getSystemId(), "PAYMENT"), "New invoice only");
        assertEquals(1, ofType(jdbc, doctor.getSystemId(), "PAYMENT"), "Invoice paid only");
        assertNothingOutsideTheBillingCircle();
        assertEquals(403, doGet("/api/billing/payments/" + paymentId, otherCaregiver).getResponse().getStatus());
    }

    // ==================== OVERDUE, MANUAL PAYMENT, CLAIM APPROVAL ====================

    @Test
    void overdueSweeperNotifiesOnce_aManualPaymentAndAnApprovedClaimSettleFurtherInvoices() throws Exception {
        JsonNode first = issuedInvoiceOfNewVisit("IN_PERSON");
        JsonNode second = issuedInvoiceOfNewVisit("PHONE");
        int cashInvoice = first.path("invoiceId").asInt();
        int claimInvoice = second.path("invoiceId").asInt();
        assertMoney("2000.00", first.path("total"));
        assertMoney("1000.00", second.path("total"));

        // ---- the first invoice falls due: the sweeper marks it OVERDUE and notifies once ----
        jdbc.update("UPDATE invoices SET due_date = ? WHERE invoice_id = ?", clinicToday().minusDays(2), cashInvoice);
        assertEquals(1, overdueSweeper.sweepOnce());
        assertEquals(0, overdueSweeper.sweepOnce());
        assertEquals("OVERDUE", invoiceStatus(cashInvoice));
        assertEquals("SENT", invoiceStatus(claimInvoice));
        for (User member : List.of(patient, caregiver)) {
            Map<String, Object> row = onlyWithKey(jdbc, member.getSystemId(), OVERDUE + cashInvoice);
            assertEquals("Invoice overdue", row.get("title"));
            assertEquals("HIGH", row.get("priority"));
            assertEquals(1, withKeyPrefix(jdbc, member.getSystemId(), OVERDUE), member.getSystemId());
        }
        assertEquals(0, withKeyPrefix(jdbc, doctor.getSystemId(), OVERDUE));

        // ---- an administrator records the cash the patient brought in ----
        String manualUrl = "/api/admin/billing/invoices/" + cashInvoice + "/payments/manual";
        Map<String, Object> cashBody = Map.of("method", "CASH", "amount", new BigDecimal("2000.00"),
                "reference", "RCPT-0042");
        Map<String, String> manualKey = Map.of("Idempotency-Key", "gate-cash-" + System.nanoTime());
        MvcResult manual = doPostWithHeaders(manualUrl, admin, cashBody, manualKey);
        JsonNode cash = ok(manual, 201);
        int cashPaymentId = cash.path("paymentId").asInt();
        assertEquals("COMPLETED", cash.path("status").asText());
        assertEquals(admin.getSystemId(), cash.path("initiatedBySystemId").asText());
        assertEquals("PAID", invoiceStatus(cashInvoice));
        assertMoney("2000.00", amountPaid(cashInvoice));
        // The administrator is the payer, so the patient is told too
        assertEquals(1, withKey(jdbc, patient.getSystemId(), RECEIVED + cashPaymentId));
        assertEquals(1, withKey(jdbc, caregiver.getSystemId(), RECEIVED + cashPaymentId));
        assertEquals(1, withKey(jdbc, doctor.getSystemId(), PAID + cashInvoice));
        assertEquals(0, withKeyPrefix(jdbc, admin.getSystemId(), RECEIVED));

        // The same request replayed with the same Idempotency-Key answers the stored payment with 200: no second
        // payment, and no second Payment received or Invoice paid notification for anyone
        JsonNode cashReplay = ok(doPostWithHeaders(manualUrl, admin, cashBody, manualKey), 200);
        assertEquals(cashPaymentId, cashReplay.path("paymentId").asInt());
        assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM payments WHERE invoice_id = ?", cashInvoice));
        assertEquals("PAID", invoiceStatus(cashInvoice));
        assertMoney("2000.00", amountPaid(cashInvoice));
        assertEquals(1, withKey(jdbc, patient.getSystemId(), RECEIVED + cashPaymentId));
        assertEquals(1, withKey(jdbc, caregiver.getSystemId(), RECEIVED + cashPaymentId));
        assertEquals(1, withKey(jdbc, doctor.getSystemId(), PAID + cashInvoice));
        assertEquals(0, withKeyPrefix(jdbc, patient.getSystemId(), PAID));
        assertEquals(0, withKeyPrefix(jdbc, caregiver.getSystemId(), PAID));
        assertEquals(0, withKeyPrefix(jdbc, admin.getSystemId(), RECEIVED));

        // ---- the patient claims the second invoice; the administrator moves it to PENDING, then APPROVED ----
        MvcResult submitted = doPost(INVOICES + "/" + claimInvoice + "/claims", patient, Map.of(
                "insuranceProvider", "Jubilee Health", "policyNumber", "JH-88213-K"));
        JsonNode claim = ok(submitted, 201);
        int claimId = claim.path("claimId").asInt();
        assertEquals("SUBMITTED", claim.path("status").asText());
        assertEquals("PENDING", ok(doPatch(ADMIN_CLAIMS + "/" + claimId, admin, Map.of("status", "PENDING")), 200)
                .path("status").asText());
        JsonNode approved = ok(doPatch(ADMIN_CLAIMS + "/" + claimId, admin, Map.of("status", "APPROVED")), 200);
        assertEquals("APPROVED", approved.path("status").asText());
        assertMoney("1000.00", approved.path("approvedAmount"));

        assertEquals("PAID", invoiceStatus(claimInvoice));
        assertMoney("1000.00", amountPaid(claimInvoice));
        List<Map<String, Object>> insurance = jdbc.queryForList("SELECT payment_id, method, status, amount "
                + "FROM payments WHERE invoice_id = ?", claimInvoice);
        assertEquals(1, insurance.size(), insurance.toString());
        assertEquals("INSURANCE", insurance.get(0).get("method"));
        assertEquals("COMPLETED", insurance.get(0).get("status"));
        int insurancePaymentId = (Integer) insurance.get(0).get("payment_id");

        // Each claim status notifies the circle once; the patient submitted, so is not told about the submission
        for (String status : List.of("SUBMITTED", "PENDING", "APPROVED")) {
            String prefix = CLAIM + claimId + ":" + status + ":";
            assertEquals(1, withKeyPrefix(jdbc, caregiver.getSystemId(), prefix), status);
            assertEquals("SUBMITTED".equals(status) ? 0 : 1, withKeyPrefix(jdbc, patient.getSystemId(), prefix),
                    status);
        }
        assertEquals(1, withKey(jdbc, patient.getSystemId(), RECEIVED + insurancePaymentId));
        assertEquals(1, withKey(jdbc, caregiver.getSystemId(), RECEIVED + insurancePaymentId));
        assertEquals(1, withKey(jdbc, doctor.getSystemId(), PAID + claimInvoice));

        // ---- the totals per kind: nothing was notified twice ----
        assertEquals(2, withKeyPrefix(jdbc, patient.getSystemId(), ISSUED));
        assertEquals(2, withKeyPrefix(jdbc, caregiver.getSystemId(), ISSUED));
        assertEquals(2, withKeyPrefix(jdbc, patient.getSystemId(), RECEIVED));
        assertEquals(2, withKeyPrefix(jdbc, caregiver.getSystemId(), RECEIVED));
        assertEquals(2, withKeyPrefix(jdbc, doctor.getSystemId(), PAID));
        assertEquals(2, ofType(jdbc, doctor.getSystemId(), "PAYMENT"));
        assertNothingOutsideTheBillingCircle();

        // The caregiver without a billing flag reads none of it
        assertEquals(403, doGet(INVOICES + "/" + cashInvoice, otherCaregiver).getResponse().getStatus());
        assertEquals(403, doGet("/api/billing/claims/" + claimId, otherCaregiver).getResponse().getStatus());
        assertFalse(json(doGet("/api/notifications?type=PAYMENT", otherCaregiver)).path("content").elements()
                .hasNext());
    }
}
