package com.carenexus.api.releasegates;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.billing.config.BillingProperties;
import com.carenexus.api.billing.model.Invoice;
import com.carenexus.api.billing.model.Payment;
import com.carenexus.api.billing.model.PaymentMethod;
import com.carenexus.api.billing.payment.PaymentSettlement;
import com.carenexus.api.billing.payment.job.PaymentReconciliationSweeper;
import com.carenexus.api.billing.provider.gateway.LocalWebhookSigner;
import com.carenexus.api.billing.repository.InvoiceRepository;
import com.carenexus.api.billing.repository.PaymentMethodRepository;
import com.carenexus.api.billing.repository.PaymentRepository;
import com.carenexus.api.common.event.PaymentRecordedEvent;
import com.carenexus.api.common.job.JobSupport;
import com.carenexus.api.common.ratelimit.RateLimitGuard;
import com.carenexus.api.common.ratelimit.RateLimitProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Release gate of be-billing-payments: payment initiation through the local gateway, the signed webhook, the
 * dev-only simulation, payment reads, the billing configuration, administrator refunds and the reconciliation
 * sweeper, with committed transactions (api/be-billing-payments.md).
 */
@RecordApplicationEvents
class BillingPaymentReleaseGateTest extends CommittedIntegrationTest {

    private static final String WEBHOOK = "/api/integrations/payments/local/webhook";
    private static final String PHONE = "+254 712 344 567";
    private static final AtomicInteger INVOICE_SEQ = new AtomicInteger(1);

    @Autowired
    private InvoiceRepository invoiceRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private PaymentMethodRepository paymentMethodRepository;
    @Autowired
    private BillingProperties billingProperties;
    @Autowired
    private JobSupport jobSupport;
    @Autowired
    private PaymentSettlement settlement;
    @Autowired
    private Clock clock;
    @Autowired
    private RateLimitProperties rateLimitProperties;
    @Autowired
    private RateLimitGuard rateLimitGuard;
    @Autowired
    private ApplicationEvents events;

    // =====================================================================================================
    // INITIATION, WEBHOOK, IDEMPOTENCY
    // =====================================================================================================

    @Test
    void patientPaysWithMpesa_pendingUntilTheSignedWebhookCompletesIt() throws Exception {
        Invoice invoice = invoice(patient, "2900.00", Invoice.STATUS_SENT);

        MvcResult started = pay(invoice, patient, Map.of("method", "mpesa", "payerPhone", PHONE), null);
        assertEquals(201, started.getResponse().getStatus(), body(started));
        JsonNode payment = json(started);
        assertTrue(body(started).contains("\"amount\":2900.00"), "money has two decimals: " + body(started));
        assertEquals("PENDING", payment.get("status").asText());
        assertEquals("MPESA", payment.get("method").asText());
        assertEquals("STK_PUSH", payment.get("nextAction").asText());
        assertTrue(payment.get("redirectUrl").isNull());
        assertEquals("local", payment.get("gateway").asText());
        assertEquals("**** 4567", payment.get("payerPhone").asText());
        assertEquals(patient.getSystemId(), payment.get("initiatedBySystemId").asText());
        assertEquals(invoice.getInvoiceNo(), payment.get("invoiceNo").asText());
        assertEquals("KES", payment.get("currency").asText());
        assertMoney("0.00", payment.get("refundableAmount"));
        assertTrue(payment.get("transactionId").isNull());
        assertFalse(body(started).contains("gatewayResponse"));
        String reference = payment.get("providerReference").asText();
        assertTrue(reference.startsWith("local_pay_"));
        int paymentId = payment.get("paymentId").asInt();
        assertEquals(1, auditRows("CREATE", paymentId));
        // The invoice does not change until the gateway confirms.
        assertMoney("0.00", invoiceRepository.findById(invoice.getInvoiceId()).orElseThrow().getAmountPaid());
        assertEquals(0, paymentEvents(PaymentRecordedEvent.COMPLETED));

        String event = event("evt_complete_1", "PAYMENT_COMPLETED", reference, "LOC-8K2M4QX7P1ZD", null);
        MvcResult ack = webhook(event, sign(event));
        assertEquals(200, ack.getResponse().getStatus(), body(ack));
        assertTrue(json(ack).get("received").asBoolean());

        JsonNode settled = json(doGet("/api/billing/payments/" + paymentId, patient));
        assertEquals("COMPLETED", settled.get("status").asText());
        assertEquals("NONE", settled.get("nextAction").asText());
        assertEquals("LOC-8K2M4QX7P1ZD", settled.get("transactionId").asText());
        assertFalse(settled.get("paidAt").isNull());
        assertMoney("2900.00", settled.get("refundableAmount"));
        Invoice paid = invoiceRepository.findById(invoice.getInvoiceId()).orElseThrow();
        assertEquals(Invoice.STATUS_PAID, paid.getStatus());
        assertMoney("2900.00", paid.getAmountPaid());
        assertEquals("APPLIED", eventOutcome("evt_complete_1"));
        assertEquals(1, paymentEvents(PaymentRecordedEvent.COMPLETED));
        PaymentRecordedEvent recorded = events.stream(PaymentRecordedEvent.class).findFirst().orElseThrow();
        assertEquals(paymentId, recorded.paymentId());
        assertEquals(patient.getSystemId(), recorded.initiatedBySystemId());
        assertMoney("2900.00", recorded.amount());
        // A webhook state change has no actor: no UPDATE audit row.
        assertEquals(0, auditRows("UPDATE", paymentId));
    }

    @Test
    void idempotencyKey_replaysTheStoredPayment_andAMalformedKeyIsRefused() throws Exception {
        Invoice invoice = invoice(patient, "1500.00", Invoice.STATUS_SENT);
        String key = "pay-dialog-" + System.nanoTime();

        MvcResult first = pay(invoice, patient, Map.of("method", "CARD"), key);
        MvcResult replay = pay(invoice, patient, Map.of("method", "CARD"), key);
        assertEquals(201, first.getResponse().getStatus(), body(first));
        assertEquals(200, replay.getResponse().getStatus(), body(replay));
        assertEquals(json(first).get("paymentId").asInt(), json(replay).get("paymentId").asInt());
        assertEquals("AWAIT_CONFIRMATION", json(replay).get("nextAction").asText());
        assertEquals(1, paymentRepository.count());
        assertEquals(1, auditRows("CREATE", json(first).get("paymentId").asInt()));

        assertError(pay(invoice, patient, Map.of("method", "CARD"), "short"), 400,
                "Idempotency-Key must be 8 to 64 characters: letters, digits, '_' or '-'");
    }

    @Test
    void twoConcurrentFullPayments_endAsOneCreatedAndOneConflict() throws Exception {
        Invoice invoice = invoice(patient, "2000.00", Invoice.STATUS_SENT);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String key = "concurrent-" + i + "-" + System.nanoTime();
                Callable<MvcResult> task = () -> {
                    start.await();
                    return pay(invoice, patient, Map.of("method", "CARD"), key);
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            String conflictMessage = null;
            for (Future<MvcResult> future : futures) {
                MvcResult result = future.get(60, TimeUnit.SECONDS);
                statuses.add(result.getResponse().getStatus());
                if (result.getResponse().getStatus() == 409) {
                    conflictMessage = message(result);
                }
            }
            statuses.sort(Integer::compareTo);
            assertEquals(List.of(201, 409), statuses);
            assertEquals("Another payment for this invoice is still being processed", conflictMessage);
            assertEquals(1, paymentRepository.count());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void amountRules_overpayIsRefused_andPendingPaymentsReserveTheirAmount() throws Exception {
        Invoice invoice = invoice(patient, "3000.00", Invoice.STATUS_SENT);

        assertError(pay(invoice, patient, Map.of("method", "CARD", "amount", 3000.01), null), 409,
                "Payment exceeds the outstanding balance");
        MvcResult part = pay(invoice, patient, Map.of("method", "CARD", "amount", 1000), null);
        assertEquals(201, part.getResponse().getStatus(), body(part));
        assertMoney("1000.00", json(part).get("amount"));
        assertError(pay(invoice, patient, Map.of("method", "CARD", "amount", 2500), null), 409,
                "Another payment for this invoice is still being processed");
        MvcResult rest = pay(invoice, patient, Map.of("method", "CARD", "amount", 2000), null);
        assertEquals(201, rest.getResponse().getStatus(), body(rest));

        MvcResult invalid = pay(invoice, patient, Map.of("method", "CARD", "amount", 0), null);
        assertEquals(400, invalid.getResponse().getStatus());
        assertEquals("VALIDATION_FAILED", json(invalid).get("code").asText());
        assertEquals("amount: must be greater than 0", message(invalid));
        assertError(pay(invoice, patient, Map.of("method", "BITCOIN"), null), 400,
                "method: must be one of CARD, MPESA, BANK_TRANSFER, CASH, INSURANCE");
        assertError(pay(invoice, patient, Map.of("method", "MPESA"), null), 400,
                "A phone number is required for an M-PESA payment");
    }

    @Test
    void invoiceStatusRules_neverIssuedIsHidden_andSettledIsNotPayable() throws Exception {
        Invoice draft = invoice(patient, "1000.00", Invoice.STATUS_DRAFT);
        assertError(pay(draft, patient, Map.of("method", "CARD"), null), 404, "Invoice not found");
        assertError(pay(draft, admin, Map.of("method", "CARD"), null), 409,
                "An invoice in status DRAFT cannot be paid");
        Invoice paid = invoice(patient, "1000.00", Invoice.STATUS_PAID);
        assertError(pay(paid, patient, Map.of("method", "CARD"), null), 409,
                "An invoice in status PAID cannot be paid");
        assertError(doPost("/api/billing/invoices/999999/payments", patient, Map.of("method", "CARD")), 404,
                "Invoice not found");
    }

    @Test
    void offlineMethods_areAdministratorOnly_andSettleAtOnce() throws Exception {
        Invoice invoice = invoice(patient, "2900.00", Invoice.STATUS_OVERDUE);
        MvcResult refused = pay(invoice, patient, Map.of("method", "CASH"), null);
        assertError(refused, 403, "Only an administrator can record a CASH payment");
        assertEquals("INVOICE", json(refused).get("resourceType").asText());

        MvcResult cash = pay(invoice, admin, Map.of("method", "cash"), null);
        assertEquals(201, cash.getResponse().getStatus(), body(cash));
        assertEquals("COMPLETED", json(cash).get("status").asText());
        assertEquals("NONE", json(cash).get("nextAction").asText());
        assertEquals(admin.getSystemId(), json(cash).get("initiatedBySystemId").asText());
        assertEquals(Invoice.STATUS_PAID, invoiceRepository.findById(invoice.getInvoiceId()).orElseThrow().getStatus());
        PaymentRecordedEvent event = events.stream(PaymentRecordedEvent.class).findFirst().orElseThrow();
        assertEquals(PaymentRecordedEvent.COMPLETED, event.status());
        assertEquals(admin.getSystemId(), event.initiatedBySystemId());
        assertEquals("CASH", event.method());
    }

    @Test
    void savedMethods_mustBelongToTheInvoicePatient_matchTheMethod_andSupplyThePhone() throws Exception {
        Invoice invoice = invoice(patient, "2900.00", Invoice.STATUS_SENT);
        PaymentMethod mpesa = savedMethod(patient, PaymentMethod.TYPE_MPESA, "+254700001234", true);
        PaymentMethod foreign = savedMethod(otherPatient, PaymentMethod.TYPE_MPESA, "+254700009999", true);
        PaymentMethod retired = savedMethod(patient, PaymentMethod.TYPE_CARD, null, false);

        assertError(pay(invoice, patient, Map.of("method", "MPESA", "paymentMethodId", foreign.getMethodId()), null),
                404, "Payment method not found");
        assertError(pay(invoice, patient, Map.of("method", "CARD", "paymentMethodId", retired.getMethodId()), null),
                404, "Payment method not found");
        assertError(pay(invoice, patient, Map.of("method", "CARD", "paymentMethodId", mpesa.getMethodId()), null),
                400, "The saved payment method is not a CARD method");

        MvcResult ok = pay(invoice, patient, Map.of("method", "MPESA", "paymentMethodId", mpesa.getMethodId()), null);
        assertEquals(201, ok.getResponse().getStatus(), body(ok));
        assertEquals("**** 1234", json(ok).get("payerPhone").asText());
        assertEquals(mpesa.getMethodId().intValue(), json(ok).get("paymentMethodId").asInt());
        // JSON is written as UTF-8; MockMvc decodes as ISO-8859-1 unless told otherwise.
        JsonNode utf8 = objectMapper.readTree(ok.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals("M-PESA •••• 1234", utf8.get("paymentMethodLabel").asText());
    }

    // =====================================================================================================
    // WEBHOOK RULES
    // =====================================================================================================

    @Test
    void webhook_duplicateDeliveryIsAppliedOnce() throws Exception {
        Invoice invoice = invoice(patient, "1200.00", Invoice.STATUS_SENT);
        JsonNode payment = json(pay(invoice, patient, Map.of("method", "CARD"), null));
        String event = event("evt_dup", "PAYMENT_COMPLETED", payment.get("providerReference").asText(), "LOC-DUP1", null);

        assertEquals(200, webhook(event, sign(event)).getResponse().getStatus());
        assertEquals(200, webhook(event, sign(event)).getResponse().getStatus());

        assertEquals(1, count("SELECT COUNT(*) FROM payment_events WHERE event_id = 'evt_dup'"));
        Invoice paid = invoiceRepository.findById(invoice.getInvoiceId()).orElseThrow();
        assertMoney("1200.00", paid.getAmountPaid());
        assertEquals(1, paymentEvents(PaymentRecordedEvent.COMPLETED));
    }

    @Test
    void webhook_outOfOrderEventsNeverRegressATerminalState() throws Exception {
        Invoice invoice = invoice(patient, "1200.00", Invoice.STATUS_SENT);
        JsonNode payment = json(pay(invoice, patient, Map.of("method", "MPESA", "payerPhone", PHONE), null));
        String reference = payment.get("providerReference").asText();

        String failed = event("evt_fail", "PAYMENT_FAILED", reference, null, null);
        assertEquals(200, webhook(failed, sign(failed)).getResponse().getStatus());
        String late = event("evt_late_success", "PAYMENT_COMPLETED", reference, "LOC-LATE1", null);
        assertEquals(200, webhook(late, sign(late)).getResponse().getStatus());

        Payment stored = paymentRepository.findById(payment.get("paymentId").asInt()).orElseThrow();
        assertEquals(Payment.STATUS_FAILED, stored.getStatus());
        assertEquals("Payment was declined", stored.getFailureReason());
        assertNull(stored.getTransactionId());
        assertEquals("APPLIED", eventOutcome("evt_fail"));
        assertEquals("STALE", eventOutcome("evt_late_success"));
        assertMoney("0.00", invoiceRepository.findById(invoice.getInvoiceId()).orElseThrow().getAmountPaid());

        // An unknown reference is accepted and ignored (the answer never reveals whether a payment exists).
        String unknown = event("evt_unknown", "PAYMENT_COMPLETED", "local_pay_unknown", null, null);
        assertEquals(200, webhook(unknown, sign(unknown)).getResponse().getStatus());
        assertEquals("IGNORED", eventOutcome("evt_unknown"));
    }

    @Test
    void webhook_rejectsBadSignatures_otherProviders_largeAndMalformedBodies() throws Exception {
        Invoice invoice = invoice(patient, "1200.00", Invoice.STATUS_SENT);
        JsonNode payment = json(pay(invoice, patient, Map.of("method", "CARD"), null));
        String event = event("evt_sig", "PAYMENT_COMPLETED", payment.get("providerReference").asText(), null, null);

        assertError(webhook(event, null), 401, "Invalid webhook signature");
        assertError(webhook(event, "sha256=" + "0".repeat(64)), 401, "Invalid webhook signature");
        assertError(webhook(event, sign(event + " ")), 401, "Invalid webhook signature");
        assertError(webhook("/api/integrations/payments/stripe/webhook", event, sign(event)), 404, "Not found");
        String large = "{\"pad\":\"" + "x".repeat(70_000) + "\"}";
        assertError(webhook(large, sign(large)), 400, "Webhook body is too large");
        String malformed = "{\"eventType\":\"PAYMENT_COMPLETED\"}";
        assertError(webhook(malformed, sign(malformed)), 400, "Malformed webhook payload");
        assertError(webhook("not json", sign("not json")), 400, "Malformed webhook payload");

        assertEquals(0, count("SELECT COUNT(*) FROM payment_events"));
        assertEquals(Payment.STATUS_PENDING,
                paymentRepository.findById(payment.get("paymentId").asInt()).orElseThrow().getStatus());
    }

    @Test
    void webhook_completionTheInvoiceCanNoLongerAccept_endsTheFailedWay() throws Exception {
        Invoice invoice = invoice(patient, "1200.00", Invoice.STATUS_SENT);
        JsonNode payment = json(pay(invoice, patient, Map.of("method", "CARD"), null));
        jdbc.update("UPDATE invoices SET status = 'CANCELLED', cancel_reason = 'Voided' WHERE invoice_id = ?",
                invoice.getInvoiceId());

        String event = event("evt_cancelled", "PAYMENT_COMPLETED", payment.get("providerReference").asText(),
                "LOC-CANCEL1", null);
        assertEquals(200, webhook(event, sign(event)).getResponse().getStatus());

        Payment stored = paymentRepository.findById(payment.get("paymentId").asInt()).orElseThrow();
        assertEquals(Payment.STATUS_FAILED, stored.getStatus());
        assertEquals(PaymentSettlement.INVOICE_REFUSED, stored.getFailureReason());
        assertEquals(1, paymentEvents(PaymentRecordedEvent.FAILED));
        assertEquals(Invoice.STATUS_CANCELLED, invoiceRepository.findById(invoice.getInvoiceId()).orElseThrow().getStatus());
    }

    // =====================================================================================================
    // AUTHORIZATION
    // =====================================================================================================

    @Test
    void initiation_ownershipRolesAndCaregiverFlags() throws Exception {
        Invoice invoice = invoice(patient, "5000.00", Invoice.STATUS_SENT);
        Map<String, Object> card = Map.of("method", "CARD", "amount", 100);

        MvcResult otherPatientResult = pay(invoice, otherPatient, card, null);
        assertError(otherPatientResult, 403, "Patients can only access their own data");
        assertEquals("INVOICE", json(otherPatientResult).get("resourceType").asText());
        assertError(pay(invoice, doctor, card, null), 403, "Your role is not authorized to perform this action");
        assertError(pay(invoice, caregiver, card, null), 403, "You don't have active proxy access to this patient");
        assertError(pay(invoice, otherCaregiver, card, null), 403, "You don't have active proxy access to this patient");

        grantAccess(caregiver, patient, a -> a.setCanViewBilling(true));
        assertTrue(message(pay(invoice, caregiver, card, null))
                .startsWith("Your proxy access for this patient does not allow managing billing"));

        grantAccess(caregiver, patient, a -> {
            a.setAccessLevel("VIEW_ONLY");
            a.setCanManageBilling(true);
        });
        assertEquals(403, pay(invoice, caregiver, card, null).getResponse().getStatus());

        grantAccess(caregiver, patient, a -> {
            a.setCanManageBilling(true);
            a.setExpiresAt(LocalDateTime.now().minusDays(1));
        });
        assertError(pay(invoice, caregiver, card, null), 403, "You don't have active proxy access to this patient");

        // Manage without view: the caregiver can start a payment but cannot read it back.
        grantAccess(caregiver, patient, a -> a.setCanManageBilling(true));
        MvcResult created = pay(invoice, caregiver, card, null);
        assertEquals(201, created.getResponse().getStatus(), body(created));
        int paymentId = json(created).get("paymentId").asInt();
        assertEquals(caregiver.getSystemId(), json(created).get("initiatedBySystemId").asText());
        MvcResult unreadable = doGet("/api/billing/payments/" + paymentId, caregiver);
        assertTrue(message(unreadable).startsWith("Your proxy access for this patient does not allow viewing billing"));
        assertEquals("PAYMENT", json(unreadable).get("resourceType").asText());
        assertEquals(paymentId, json(unreadable).get("resourceId").asInt());

        grantAccess(caregiver, patient, a -> {
            a.setCanManageBilling(true);
            a.setCanViewBilling(true);
        });
        assertEquals(200, doGet("/api/billing/payments/" + paymentId, caregiver).getResponse().getStatus());
    }

    @Test
    void reads_paymentByRole_lists_paging_andAudit() throws Exception {
        Invoice invoice = invoice(patient, "2900.00", Invoice.STATUS_SENT);
        int first = json(pay(invoice, patient, Map.of("method", "CARD", "amount", 900), null)).get("paymentId").asInt();
        int second = json(pay(invoice, patient, Map.of("method", "MPESA", "amount", 1000, "payerPhone", PHONE), null))
                .get("paymentId").asInt();
        String failEvent = event("evt_read_fail", "PAYMENT_FAILED",
                paymentRepository.findById(first).orElseThrow().getProviderReference(), null, "Card declined");
        assertEquals(200, webhook(failEvent, sign(failEvent)).getResponse().getStatus());

        assertEquals(200, doGet("/api/billing/payments/" + first, patient).getResponse().getStatus());
        assertEquals(1, auditRows("READ", first));
        assertEquals(200, doGet("/api/billing/payments/" + first, doctor).getResponse().getStatus());
        assertEquals(200, doGet("/api/billing/payments/" + first, admin).getResponse().getStatus());
        assertError(doGet("/api/billing/payments/" + first, otherDoctor), 403,
                "You can only view payments of invoices you issued");
        assertError(doGet("/api/billing/payments/" + first, otherPatient), 403, "Patients can only access their own data");
        assertError(doGet("/api/billing/payments/999999", patient), 404, "Payment not found");
        assertError(doGet("/api/billing/payments/abc", patient), 400, "Invalid value for parameter 'id'");

        JsonNode mine = json(doGet("/api/billing/payments", patient));
        assertEquals(2, mine.get("totalElements").asInt());
        assertEquals(second, mine.get("content").get(0).get("paymentId").asInt());
        assertEquals("Card declined", mine.get("content").get(1).get("failureReason").asText());
        JsonNode clamped = json(doGet("/api/billing/payments?patientSystemId=" + patient.getSystemId() + "&size=500&page=-3",
                admin));
        assertEquals(100, clamped.get("size").asInt());
        assertEquals(0, clamped.get("page").asInt());
        assertError(doGet("/api/billing/payments", admin), 400, "patientSystemId is required");
        assertError(doGet("/api/billing/payments?patientSystemId=" + patient.getSystemId(), doctor), 403,
                "Your role is not authorized to perform this action");
        assertError(doGet("/api/billing/payments?patientSystemId=" + patient.getSystemId(), otherPatient), 403,
                "Patients can only access their own data");
        MvcResult badId = doGet("/api/billing/payments?patientSystemId=nope", admin);
        assertEquals(400, badId.getResponse().getStatus());
        assertEquals("VALIDATION_FAILED", json(badId).get("code").asText());
        assertEquals("patientSystemId: must be a valid system id", message(badId));

        grantAccess(caregiver, patient, a -> a.setCanViewBilling(true));
        assertEquals(2, json(doGet("/api/billing/payments?patientSystemId=" + patient.getSystemId(), caregiver))
                .get("totalElements").asInt());

        JsonNode history = json(doGet("/api/billing/invoices/" + invoice.getInvoiceId() + "/payments", doctor));
        assertEquals(2, history.get("totalElements").asInt());
        assertError(doGet("/api/billing/invoices/" + invoice.getInvoiceId() + "/payments", otherDoctor), 403,
                "You can only view invoices you issued");
        Invoice draft = invoice(patient, "100.00", Invoice.STATUS_DRAFT);
        assertError(doGet("/api/billing/invoices/" + draft.getInvoiceId() + "/payments", patient), 404,
                "Invoice not found");
        JsonNode empty = json(doGet("/api/billing/invoices/" + draft.getInvoiceId() + "/payments", doctor));
        assertEquals(0, empty.get("totalElements").asInt());
    }

    @Test
    void config_describesTheLocalGateway() throws Exception {
        for (User user : List.of(patient, doctor, caregiver, admin)) {
            MvcResult result = doGet("/api/billing/config", user);
            assertEquals(200, result.getResponse().getStatus());
            JsonNode config = json(result);
            assertEquals("local", config.get("provider").asText());
            assertFalse(config.get("liveGateway").asBoolean());
            assertEquals("[\"CARD\",\"INSURANCE\",\"CASH\",\"MPESA\",\"BANK_TRANSFER\"]",
                    config.get("supportedMethods").toString());
            assertEquals("[\"CARD\",\"MPESA\"]", config.get("onlineMethods").toString());
            assertEquals("KES", config.get("currency").asText());
            assertTrue(config.get("simulationEnabled").asBoolean());
        }
        assertEquals(401, mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/billing/config")).andReturn().getResponse().getStatus());
    }

    // =====================================================================================================
    // SIMULATION
    // =====================================================================================================

    @Test
    void simulation_settlesThroughTheWebhookPath_andOnlyOnce() throws Exception {
        Invoice invoice = invoice(patient, "2000.00", Invoice.STATUS_SENT);
        int paymentId = json(pay(invoice, patient, Map.of("method", "MPESA", "payerPhone", PHONE), null))
                .get("paymentId").asInt();
        String url = "/api/billing/payments/" + paymentId + "/simulate";

        MvcResult invalid = doPost(url, patient, Map.of("outcome", "MAYBE"));
        assertEquals(400, invalid.getResponse().getStatus());
        assertEquals("outcome: must be one of COMPLETED, FAILED", message(invalid));
        assertError(doPost(url, doctor, Map.of("outcome", "COMPLETED")), 403,
                "Your role is not authorized to perform this action");
        assertError(doPost(url, otherPatient, Map.of("outcome", "COMPLETED")), 403,
                "Patients can only access their own data");
        assertError(doPost("/api/billing/payments/999999/simulate", patient, Map.of("outcome", "COMPLETED")), 404,
                "Payment not found");

        MvcResult done = doPost(url, patient, Map.of("outcome", "completed"));
        assertEquals(200, done.getResponse().getStatus(), body(done));
        assertEquals("COMPLETED", json(done).get("status").asText());
        assertTrue(json(done).get("transactionId").asText().matches("LOC-[A-Z0-9]{12}"));
        assertEquals(Invoice.STATUS_PAID, invoiceRepository.findById(invoice.getInvoiceId()).orElseThrow().getStatus());
        assertEquals(1, count("SELECT COUNT(*) FROM payment_events WHERE event_id LIKE 'sim_%' AND outcome = 'APPLIED'"));
        assertEquals(1, auditRows("UPDATE", paymentId));
        assertError(doPost(url, patient, Map.of("outcome", "FAILED")), 409, "Only a pending payment can be simulated");

        Invoice second = invoice(patient, "500.00", Invoice.STATUS_SENT);
        int failing = json(pay(second, patient, Map.of("method", "CARD"), null)).get("paymentId").asInt();
        JsonNode failed = json(doPost("/api/billing/payments/" + failing + "/simulate", admin, Map.of("outcome", "FAILED")));
        assertEquals("FAILED", failed.get("status").asText());
        assertEquals("Simulated failure", failed.get("failureReason").asText());
    }

    @Test
    void simulation_byACaregiver_needsTheManageFlagOnAnActiveGrant_andDenialsAreAuditedAsPayment() throws Exception {
        Invoice invoice = invoice(patient, "1200.00", Invoice.STATUS_SENT);
        int paymentId = json(pay(invoice, patient, Map.of("method", "MPESA", "payerPhone", PHONE), null))
                .get("paymentId").asInt();
        String url = "/api/billing/payments/" + paymentId + "/simulate";

        // No grant
        assertError(doPost(url, caregiver, Map.of("outcome", "COMPLETED")), 403,
                "You don't have active proxy access to this patient");
        // Viewing billing is not managing it
        grantAccess(caregiver, patient, a -> a.setCanViewBilling(true));
        MvcResult viewOnly = doPost(url, caregiver, Map.of("outcome", "COMPLETED"));
        assertEquals(403, viewOnly.getResponse().getStatus(), body(viewOnly));
        // VIEW_ONLY blocks the write even with the manage flag
        grantAccess(caregiver, patient, a -> {
            a.setAccessLevel("VIEW_ONLY");
            a.setCanManageBilling(true);
        });
        assertEquals(403, doPost(url, caregiver, Map.of("outcome", "COMPLETED")).getResponse().getStatus());
        // A revoked grant with the flag
        grantAccess(caregiver, patient, a -> {
            a.setCanManageBilling(true);
            a.setIsActive(false);
            a.setRevokedAt(LocalDateTime.now().minusMinutes(1));
        });
        assertError(doPost(url, caregiver, Map.of("outcome", "COMPLETED")), 403,
                "You don't have active proxy access to this patient");
        assertEquals("PENDING", paymentRepository.findById(paymentId).orElseThrow().getStatus());

        // Every refusal is audited against the payment (resource type PAYMENT with the payment id), never INVOICE
        assertEquals(4, count("SELECT COUNT(*) FROM audit_log WHERE action = 'ACCESS_DENIED' AND system_id = '"
                + caregiver.getSystemId() + "' AND resource_type = 'PAYMENT' AND resource_id = " + paymentId));
        assertEquals(0, count("SELECT COUNT(*) FROM audit_log WHERE action = 'ACCESS_DENIED' AND system_id = '"
                + caregiver.getSystemId() + "' AND resource_type = 'INVOICE'"));

        // An active grant with the manage flag settles it
        grantAccess(caregiver, patient, a -> a.setCanManageBilling(true));
        MvcResult done = doPost(url, caregiver, Map.of("outcome", "COMPLETED"));
        assertEquals(200, done.getResponse().getStatus(), body(done));
        assertEquals("COMPLETED", json(done).get("status").asText());
        assertError(doPost(url, caregiver, Map.of("outcome", "FAILED")), 409, "Only a pending payment can be simulated");
    }

    // =====================================================================================================
    // REFUNDS
    // =====================================================================================================

    @Test
    void refund_partialThenFull_updatesInvoiceAndPublishesEachRefund() throws Exception {
        Invoice invoice = invoice(patient, "2900.00", Invoice.STATUS_SENT);
        int paymentId = json(pay(invoice, admin, Map.of("method", "BANK_TRANSFER"), null)).get("paymentId").asInt();
        String url = "/api/admin/billing/payments/" + paymentId + "/refund";

        assertError(doPost(url, patient, Map.of("reason", "x")), 403, "You don't have permission to access this resource");
        MvcResult blank = doPost(url, admin, Map.of("reason", " "));
        assertEquals(400, blank.getResponse().getStatus());
        assertEquals("reason: must not be blank", message(blank));
        assertError(doPost(url, admin, Map.of("amount", 3000, "reason", "Too much")), 409,
                "Refund exceeds the refundable amount");

        String key = "refund-dialog-" + System.nanoTime();
        MvcResult partial = doPostWithHeaders(url, admin, Map.of("amount", 1000, "reason", " Duplicate charge "),
                Map.of("Idempotency-Key", key));
        assertEquals(200, partial.getResponse().getStatus(), body(partial));
        assertEquals("COMPLETED", json(partial).get("status").asText());
        assertMoney("1000.00", json(partial).get("refundedAmount"));
        assertMoney("1900.00", json(partial).get("refundableAmount"));
        assertEquals("Duplicate charge", json(partial).get("refundReason").asText());
        Invoice afterPartial = invoiceRepository.findById(invoice.getInvoiceId()).orElseThrow();
        assertEquals(Invoice.STATUS_PARTIALLY_PAID, afterPartial.getStatus());
        assertMoney("1900.00", afterPartial.getAmountPaid());

        MvcResult replay = doPostWithHeaders(url, admin, Map.of("amount", 1000, "reason", "Duplicate charge"),
                Map.of("Idempotency-Key", key));
        assertEquals(200, replay.getResponse().getStatus());
        assertMoney("1000.00", paymentRepository.findById(paymentId).orElseThrow().getRefundedAmount());

        MvcResult rest = doPost(url, admin, Map.of("reason", "Visit cancelled"));
        assertEquals(200, rest.getResponse().getStatus(), body(rest));
        assertEquals("REFUNDED", json(rest).get("status").asText());
        assertMoney("0.00", json(rest).get("refundableAmount"));
        assertFalse(json(rest).get("paidAt").isNull());
        assertEquals(Invoice.STATUS_REFUNDED, invoiceRepository.findById(invoice.getInvoiceId()).orElseThrow().getStatus());
        assertError(doPost(url, admin, Map.of("reason", "Again")), 409, "This payment has already been refunded in full");

        List<PaymentRecordedEvent> refunds = events.stream(PaymentRecordedEvent.class)
                .filter(e -> PaymentRecordedEvent.REFUNDED.equals(e.status())).toList();
        assertEquals(2, refunds.size());
        assertMoney("1000.00", refunds.get(0).amount());
        assertMoney("1900.00", refunds.get(1).amount());
        assertEquals(admin.getSystemId(), refunds.get(0).initiatedBySystemId());
        assertEquals(2, auditRows("UPDATE", paymentId));
    }

    @Test
    void refund_refusesPendingInsuranceAndUnlinkedPayments() throws Exception {
        Invoice invoice = invoice(patient, "2900.00", Invoice.STATUS_SENT);
        int pending = json(pay(invoice, patient, Map.of("method", "CARD", "amount", 100), null)).get("paymentId").asInt();
        assertError(doPost("/api/admin/billing/payments/" + pending + "/refund", admin, Map.of("reason", "x")), 409,
                "Only a completed payment can be refunded");
        int insurance = json(pay(invoice, admin, Map.of("method", "INSURANCE", "amount", 100), null))
                .get("paymentId").asInt();
        assertError(doPost("/api/admin/billing/payments/" + insurance + "/refund", admin, Map.of("reason", "x")), 409,
                "An insurance settlement cannot be refunded here");
        Payment unlinked = paymentRepository.saveAndFlush(Payment.builder()
                .patientSystemId(patient.getSystemId()).amount(new BigDecimal("50.00")).method("CASH")
                .status(Payment.STATUS_COMPLETED).gateway("MANUAL").providerReference("Receipt 12")
                .createdAt(LocalDateTime.now(clock)).build());
        assertError(doPost("/api/admin/billing/payments/" + unlinked.getPaymentId() + "/refund", admin,
                Map.of("reason", "x")), 409, "This payment is not linked to an invoice");
        assertError(doPost("/api/admin/billing/payments/999999/refund", admin, Map.of("reason", "x")), 404,
                "Payment not found");
    }

    // =====================================================================================================
    // RECONCILIATION AND RATE LIMIT
    // =====================================================================================================

    @Test
    void reconciliation_failsStalePendingPayments_withAFixedClock() throws Exception {
        Invoice invoice = invoice(patient, "2000.00", Invoice.STATUS_SENT);
        JsonNode stale = json(pay(invoice, patient, Map.of("method", "CARD", "amount", 500), null));
        int staleId = stale.get("paymentId").asInt();
        Invoice other = invoice(patient, "300.00", Invoice.STATUS_SENT);
        int fresh = json(pay(other, patient, Map.of("method", "CARD"), null)).get("paymentId").asInt();
        // Created 40 minutes before the sweep (fixed clock), the other one 10 minutes before.
        LocalDateTime now = LocalDateTime.now(clock);
        jdbc.update("UPDATE payments SET created_at = ? WHERE payment_id = ?", now.minusMinutes(40), staleId);
        jdbc.update("UPDATE payments SET created_at = ? WHERE payment_id = ?", now.minusMinutes(10), fresh);

        PaymentReconciliationSweeper sweeper = new PaymentReconciliationSweeper(jobSupport, paymentRepository,
                settlement, Clock.fixed(clock.instant(), clock.getZone()));
        assertEquals(1, sweeper.sweepOnce());
        Payment failed = paymentRepository.findById(staleId).orElseThrow();
        assertEquals(Payment.STATUS_FAILED, failed.getStatus());
        assertEquals(PaymentReconciliationSweeper.TIMED_OUT, failed.getFailureReason());
        assertEquals(Payment.STATUS_PENDING, paymentRepository.findById(fresh).orElseThrow().getStatus());
        assertEquals(1, paymentEvents(PaymentRecordedEvent.FAILED));
        assertEquals(0, sweeper.sweepOnce());

        // A late success after the timeout changes nothing.
        String late = event("evt_after_timeout", "PAYMENT_COMPLETED", stale.get("providerReference").asText(),
                "LOC-LATE2", null);
        assertEquals(200, webhook(late, sign(late)).getResponse().getStatus());
        assertEquals("STALE", eventOutcome("evt_after_timeout"));
        assertEquals(Payment.STATUS_FAILED, paymentRepository.findById(staleId).orElseThrow().getStatus());

        // 31 minutes later the other payment times out too, and its reservation is freed.
        PaymentReconciliationSweeper later = new PaymentReconciliationSweeper(jobSupport, paymentRepository,
                settlement, Clock.offset(Clock.fixed(clock.instant(), clock.getZone()), Duration.ofMinutes(31)));
        assertEquals(1, later.sweepOnce());
        assertEquals(201, pay(other, patient, Map.of("method", "CARD"), null).getResponse().getStatus());
    }

    @Test
    void initiation_isRateLimitedPerCaller() throws Exception {
        Invoice invoice = invoice(patient, "5000.00", Invoice.STATUS_SENT);
        RateLimitProperties.Bucket small = new RateLimitProperties.Bucket();
        small.setLimit(2);
        small.setWindowSeconds(3600L);
        RateLimitProperties.Bucket previous = rateLimitProperties.getBuckets().put("payment-initiate", small);
        rateLimitGuard.reset("payment-initiate", patient.getSystemId());
        rateLimitProperties.setEnabled(true);
        try {
            assertEquals(201, pay(invoice, patient, Map.of("method", "CARD", "amount", 10), null).getResponse().getStatus());
            assertEquals(201, pay(invoice, patient, Map.of("method", "CARD", "amount", 10), null).getResponse().getStatus());
            MvcResult limited = pay(invoice, patient, Map.of("method", "CARD", "amount", 10), null);
            assertEquals(429, limited.getResponse().getStatus());
            assertEquals("RATE_LIMITED", json(limited).get("code").asText());
            assertNotNull(limited.getResponse().getHeader("Retry-After"));
            assertEquals(2, paymentRepository.count());
        } finally {
            rateLimitProperties.setEnabled(false);
            if (previous != null) {
                rateLimitProperties.getBuckets().put("payment-initiate", previous);
            } else {
                rateLimitProperties.getBuckets().remove("payment-initiate");
            }
            rateLimitGuard.reset("payment-initiate", patient.getSystemId());
        }
    }

    // =====================================================================================================
    // HELPERS
    // =====================================================================================================

    private Invoice invoice(User forPatient, String total, String status) {
        LocalDateTime now = LocalDateTime.now(clock);
        BigDecimal amount = new BigDecimal(total);
        boolean issued = !Invoice.STATUS_DRAFT.equals(status);
        return invoiceRepository.saveAndFlush(Invoice.builder()
                .patientSystemId(forPatient.getSystemId())
                .doctorSystemId(doctor.getSystemId())
                .createdBySystemId(doctor.getSystemId())
                .invoiceNo(String.format("INV-2026-%06d", INVOICE_SEQ.getAndIncrement()))
                .lineItems("[{\"description\":\"Consultation\",\"quantity\":1,\"unitPrice\":" + total
                        + ",\"amount\":" + total + "}]")
                .subtotal(amount)
                .total(amount)
                .amountPaid(Invoice.STATUS_PAID.equals(status) ? amount : new BigDecimal("0.00"))
                .currency("KES")
                .status(status)
                .dueDate(LocalDate.now(clock).plusDays(14))
                .issuedAt(issued ? now : null)
                .paidAt(Invoice.STATUS_PAID.equals(status) ? now : null)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    private PaymentMethod savedMethod(User owner, String type, String phone, boolean usable) {
        String digits = phone == null ? "4242" : phone.substring(phone.length() - 4);
        return paymentMethodRepository.saveAndFlush(PaymentMethod.builder()
                .systemId(owner.getSystemId())
                .type(type)
                .provider(PaymentMethod.TYPE_MPESA.equals(type) ? "M-PESA" : "VISA")
                .lastFour(digits)
                .mpesaPhone(phone)
                .displayLabel((PaymentMethod.TYPE_MPESA.equals(type) ? "M-PESA" : "Card") + " •••• " + digits)
                .providerToken("local_pm_" + System.nanoTime())
                .isActive(usable)
                .isDeleted(!usable)
                .createdAt(LocalDateTime.now(clock))
                .build());
    }

    private MvcResult pay(Invoice invoice, User as, Map<String, Object> body, String key) throws Exception {
        Map<String, String> headers = new HashMap<>();
        if (key != null) {
            headers.put("Idempotency-Key", key);
        }
        return doPostWithHeaders("/api/billing/invoices/" + invoice.getInvoiceId() + "/payments", as, body, headers);
    }

    private String event(String eventId, String type, String reference, String transactionId, String reason)
            throws Exception {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("eventId", eventId);
        node.put("eventType", type);
        node.put("providerReference", reference);
        node.put("transactionId", transactionId);
        node.put("failureReason", reason);
        node.put("occurredAt", "2026-11-10T06:21:07Z");
        return objectMapper.writeValueAsString(node);
    }

    private String sign(String body) {
        return LocalWebhookSigner.sign(billingProperties.getLocal().getWebhookSecret(), body);
    }

    private MvcResult webhook(String body, String signature) throws Exception {
        return webhook(WEBHOOK, body, signature);
    }

    private MvcResult webhook(String url, String body, String signature) throws Exception {
        MockHttpServletRequestBuilder builder = post(url).contentType(MediaType.APPLICATION_JSON).content(body);
        if (signature != null) {
            builder.header(LocalWebhookSigner.HEADER, signature);
        }
        return mockMvc.perform(builder).andReturn();
    }

    private void assertError(MvcResult result, int status, String message) throws Exception {
        assertEquals(status, result.getResponse().getStatus(), body(result));
        assertEquals(message, message(result));
    }

    private static void assertMoney(String expected, JsonNode node) {
        assertNotNull(node);
        assertEquals(0, new BigDecimal(expected).compareTo(node.decimalValue()), "was " + node);
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "was " + actual);
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private long auditRows(String action, int paymentId) {
        return count("SELECT COUNT(*) FROM audit_log WHERE action = '" + action
                + "' AND resource_type = 'PAYMENT' AND resource_id = " + paymentId);
    }

    private String eventOutcome(String eventId) {
        return jdbc.queryForObject("SELECT outcome FROM payment_events WHERE event_id = ?", String.class, eventId);
    }

    private long paymentEvents(String status) {
        return events.stream(PaymentRecordedEvent.class).filter(e -> status.equals(e.status())).count();
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
