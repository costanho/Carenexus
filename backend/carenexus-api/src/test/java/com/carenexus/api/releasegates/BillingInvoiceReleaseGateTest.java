package com.carenexus.api.releasegates;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.billing.config.BillingProperties;
import com.carenexus.api.billing.invoice.InvoiceFixtures;
import com.carenexus.api.billing.invoice.InvoiceFixtures.Visit;
import com.carenexus.api.billing.invoice.InvoiceService;
import com.carenexus.api.billing.invoice.dto.InvoiceResponse;
import com.carenexus.api.common.event.ConsultationCompletedEvent;
import com.carenexus.api.common.event.InvoiceIssuedEvent;
import com.carenexus.api.common.event.InvoiceStatusChangedEvent;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Release gates of the invoice vertical (be-billing-invoices): generation and totals, per-visit and Idempotency-Key
 * idempotency, the automatic invoice on a published ConsultationCompletedEvent, lookups, the uninvoiced list, who
 * may read and manage invoices (patient, other patient, issuing and unrelated doctor, caregiver with and without the
 * billing flag, after revoke and expiry, admin), the status machine, every facade method, paging, the fee schedule,
 * and the three billing settings. Runs with committed transactions (CommittedIntegrationTest).
 * <p>
 * The real consultation-completion path is asserted by the integration-gate slices of waves 4 and 5; here the event
 * is published by the test.
 */
@RecordApplicationEvents
class BillingInvoiceReleaseGateTest extends CommittedIntegrationTest {

    private static final String INVOICES = "/api/billing/invoices";
    private static final String DOCTOR_INVOICES = "/api/doctors/billing/invoices";
    private static final String UNINVOICED = "/api/doctors/billing/uninvoiced";
    private static final String ADMIN_INVOICES = "/api/admin/billing/invoices";
    private static final String FEES = "/api/billing/fees";
    private static final String DOCTOR_FEES = "/api/doctors/billing/fees";
    private static final String ADMIN_FEES = "/api/admin/billing/fees";
    private static final String URL_GATE = "You don't have permission to access this resource";
    private static final String ROLE_REFUSED = "Your role is not authorized to perform this action";
    private static final ZoneId CLINIC_ZONE = ZoneId.of("Africa/Harare");

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private BillingProperties billingProperties;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ApplicationEvents events;

    private InvoiceFixtures fixtures;

    /** Reads money exactly as serialized (2500.00 stays 2500.00), so the two-decimal wire format is asserted. */
    private ObjectMapper exactMapper;

    @BeforeEach
    void setUpFixtures() {
        fixtures = new InvoiceFixtures(jdbc);
        exactMapper = objectMapper.copy()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
        exactMapper.configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);
    }

    @Override
    protected JsonNode json(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? exactMapper.nullNode() : exactMapper.readTree(content);
    }

    @AfterEach
    void restoreFees() {
        // billing_fee_schedule is reference data kept across tests: put the shipped defaults back.
        fixtures.restoreDefaultFees();
        billingProperties.setAutoInvoice(BillingProperties.AUTO_INVOICE_DRAFT);
    }

    // ==================== HELPERS ====================

    private Map<String, Object> forConsultation(Integer consultationId) {
        Map<String, Object> body = new HashMap<>();
        body.put("consultationId", consultationId);
        return body;
    }

    private Map<String, Object> forAppointment(Integer appointmentId) {
        Map<String, Object> body = new HashMap<>();
        body.put("appointmentId", appointmentId);
        return body;
    }

    private static Map<String, Object> line(String description, int quantity, String unitPrice) {
        Map<String, Object> line = new HashMap<>();
        line.put("description", description);
        line.put("quantity", quantity);
        line.put("unitPrice", new BigDecimal(unitPrice));
        return line;
    }

    private JsonNode created(MvcResult result) throws Exception {
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result);
    }

    private JsonNode ok(MvcResult result) throws Exception {
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result);
    }

    private void assertError(MvcResult result, int status, String message) throws Exception {
        assertEquals(status, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(message, message(result));
    }

    /** DRAFT invoice of a new completed visit of the fixture pair. */
    private JsonNode draft(String type) throws Exception {
        Visit visit = fixtures.completedVisit(patient, doctor, type);
        return created(doPost(INVOICES, doctor, forConsultation(visit.consultationId())));
    }

    /** SENT invoice of a new completed visit of the fixture pair. */
    private JsonNode sent(String type) throws Exception {
        JsonNode draft = draft(type);
        return ok(doPost(INVOICES + "/" + draft.get("invoiceId").asInt() + "/send", doctor, null));
    }

    private void setSetting(String key, String value) throws Exception {
        MvcResult result = doPut("/api/admin/settings/" + key, admin, Map.of("value", value));
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    private long auditRows(String action, Integer invoiceId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ? AND resource_type = 'INVOICE' "
                + "AND resource_id = ? AND status = 'SUCCESS'", Long.class, action, invoiceId);
        return count == null ? 0 : count;
    }

    private long invoiceCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM invoices", Long.class);
        return count == null ? 0 : count;
    }

    private void publishCompleted(Integer consultationId, Integer appointmentId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> eventPublisher.publishEvent(
                new ConsultationCompletedEvent(consultationId, appointmentId, patient.getSystemId(),
                        doctor.getSystemId(), false, null)));
    }

    private LocalDate today() {
        return LocalDate.now(CLINIC_ZONE);
    }

    // ==================== GENERATION AND TOTALS ====================

    @Test
    void generatesDraftFromConsultationWithFeeLine() throws Exception {
        Visit visit = fixtures.completedVisit(patient, doctor, "VIDEO");

        JsonNode invoice = created(doPost(INVOICES, doctor, forConsultation(visit.consultationId())));

        assertEquals("DRAFT", invoice.get("status").asText());
        assertTrue(invoice.get("invoiceNo").asText().matches("^INV-" + today().getYear() + "-\\d{6,}$"));
        assertEquals(patient.getSystemId(), invoice.get("patientSystemId").asText());
        assertEquals("PATIENT User1", invoice.get("patientName").asText());
        assertEquals(doctor.getSystemId(), invoice.get("doctorSystemId").asText());
        assertEquals("DOCTOR User2", invoice.get("doctorName").asText());
        assertEquals(doctor.getSystemId(), invoice.get("createdBySystemId").asText());
        assertEquals(visit.consultationId().intValue(), invoice.get("consultationId").asInt());
        assertEquals(visit.appointmentId().intValue(), invoice.get("appointmentId").asInt());
        assertEquals(1, invoice.get("lineItems").size());
        JsonNode line = invoice.get("lineItems").get(0);
        assertEquals("Video consultation", line.get("description").asText());
        assertEquals(1, line.get("quantity").asInt());
        assertEquals("1500.00", line.get("unitPrice").decimalValue().toPlainString());
        assertEquals("1500.00", line.get("amount").decimalValue().toPlainString());
        assertEquals("1500.00", invoice.get("subtotal").decimalValue().toPlainString());
        assertEquals("0.00", invoice.get("tax").decimalValue().toPlainString());
        assertEquals("0.00", invoice.get("discount").decimalValue().toPlainString());
        assertEquals("1500.00", invoice.get("total").decimalValue().toPlainString());
        assertEquals("0.00", invoice.get("amountPaid").decimalValue().toPlainString());
        assertEquals("0.00", invoice.get("outstanding").decimalValue().toPlainString());
        assertEquals("KES", invoice.get("currency").asText());
        assertEquals(today().plusDays(14).toString(), invoice.get("dueDate").asText());
        assertTrue(invoice.get("issuedAt").isNull());
        assertTrue(invoice.get("paidAt").isNull());
        assertTrue(invoice.has("cancelReason") && invoice.get("cancelReason").isNull());
        assertTrue(invoice.has("notes") && invoice.get("notes").isNull());
        assertFalse(invoice.get("createdAt").isNull());

        int invoiceId = invoice.get("invoiceId").asInt();
        assertEquals(1, auditRows("CREATE", invoiceId));
        // Creation as DRAFT publishes no status event.
        assertEquals(0, events.stream(InvoiceStatusChangedEvent.class).count());
        assertEquals(0, events.stream(InvoiceIssuedEvent.class).count());
    }

    @Test
    void computesTotalsWithLinesDiscountAndTax() throws Exception {
        setSetting("billing.tax-rate", "0.16");
        Visit first = fixtures.completedVisit(patient, doctor, "VIDEO");
        Map<String, Object> body = forConsultation(first.consultationId());
        body.put("lineItems", List.of(line("Video consultation", 1, "2000.00"), line("Medical report", 1, "500")));
        JsonNode invoice = created(doPost(INVOICES, doctor, body));
        assertEquals("2500.00", invoice.get("subtotal").decimalValue().toPlainString());
        assertEquals("400.00", invoice.get("tax").decimalValue().toPlainString());
        assertEquals("2900.00", invoice.get("total").decimalValue().toPlainString());
        assertEquals("500.00", invoice.get("lineItems").get(1).get("amount").decimalValue().toPlainString());

        Visit second = fixtures.completedVisit(patient, doctor, "PHONE");
        Map<String, Object> rounded = forConsultation(second.consultationId());
        rounded.put("lineItems", List.of(line("  Dressing  ", 3, "99.99")));
        rounded.put("discount", new BigDecimal("0.01"));
        rounded.put("notes", "Follow-up visit");
        JsonNode roundedInvoice = created(doPost(INVOICES, doctor, rounded));
        assertEquals("Dressing", roundedInvoice.get("lineItems").get(0).get("description").asText());
        assertEquals("299.97", roundedInvoice.get("subtotal").decimalValue().toPlainString());
        assertEquals("0.01", roundedInvoice.get("discount").decimalValue().toPlainString());
        assertEquals("47.99", roundedInvoice.get("tax").decimalValue().toPlainString());
        assertEquals("347.95", roundedInvoice.get("total").decimalValue().toPlainString());
        assertEquals("Follow-up visit", roundedInvoice.get("notes").asText());

        Visit third = fixtures.completedVisit(patient, doctor, "PHONE");
        Map<String, Object> tooMuch = forConsultation(third.consultationId());
        tooMuch.put("lineItems", List.of(line("Phone consultation", 1, "100.00")));
        tooMuch.put("discount", new BigDecimal("100.01"));
        assertError(doPost(INVOICES, doctor, tooMuch), 400, "Discount cannot exceed the subtotal");
    }

    @Test
    void createRefusesInvalidRequestsInContractOrder() throws Exception {
        Visit visit = fixtures.completedVisit(patient, doctor, "VIDEO");

        Map<String, Object> invalid = forConsultation(visit.consultationId());
        invalid.put("lineItems", List.of(line("Video consultation", 1, "-1.00")));
        MvcResult validation = doPost(INVOICES, patient, invalid);
        assertError(validation, 400, "lineItems[0].unitPrice: must not be negative");
        assertEquals("VALIDATION_FAILED", json(validation).get("code").asText());
        assertEquals("lineItems[0].unitPrice", json(validation).get("errors").get(0).get("field").asText());

        Map<String, Object> badQuantity = forConsultation(visit.consultationId());
        badQuantity.put("lineItems", List.of(line("Video consultation", 101, "1.00")));
        assertError(doPost(INVOICES, doctor, badQuantity), 400, "lineItems[0].quantity: must be between 1 and 100");

        assertError(doPost(INVOICES, patient, forConsultation(visit.consultationId())), 403, ROLE_REFUSED);
        assertError(doPost(INVOICES, caregiver, forConsultation(visit.consultationId())), 403, ROLE_REFUSED);
        assertError(doPost(INVOICES, doctor, new HashMap<>()), 400, "Provide a consultationId or an appointmentId");
        assertError(doPost(INVOICES, doctor, forConsultation(999999)), 404, "Consultation not found");
        assertError(doPost(INVOICES, doctor, forAppointment(999999)), 404, "Appointment not found");

        Visit other = fixtures.completedVisit(patient, doctor, "PHONE");
        Map<String, Object> mismatch = forConsultation(visit.consultationId());
        mismatch.put("appointmentId", other.appointmentId());
        assertError(doPost(INVOICES, doctor, mismatch), 400, "The consultation does not belong to this appointment");

        assertError(doPost(INVOICES, otherDoctor, forConsultation(visit.consultationId())), 403,
                "You can only invoice your own consultations and appointments");

        Integer openAppointment = fixtures.appointment(patient, doctor, "VIDEO", "SCHEDULED");
        Integer inProgress = fixtures.consultation(openAppointment, patient, doctor, "IN_PROGRESS", null);
        assertError(doPost(INVOICES, doctor, forConsultation(inProgress)), 409,
                "Only a completed consultation can be invoiced");
        Integer cancelled = fixtures.appointment(patient, doctor, "VIDEO", "CANCELLED");
        assertError(doPost(INVOICES, doctor, forAppointment(cancelled)), 409,
                "A cancelled appointment cannot be invoiced");

        jdbc.update("UPDATE doctors SET is_active = FALSE WHERE system_id = ?", doctor.getSystemId());
        assertError(doPost(INVOICES, doctor, forConsultation(visit.consultationId())), 403,
                "Your clinician account is not active");
        jdbc.update("UPDATE doctors SET is_active = TRUE WHERE system_id = ?", doctor.getSystemId());
        assertEquals(0, invoiceCount());
    }

    @Test
    void adminInvoicesAnyVisitAndTheIssuerStaysTheVisitDoctor() throws Exception {
        Integer appointmentId = fixtures.appointment(patient, doctor, "IN_PERSON", "SCHEDULED");

        JsonNode invoice = created(doPost(INVOICES, admin, forAppointment(appointmentId)));

        assertEquals(doctor.getSystemId(), invoice.get("doctorSystemId").asText());
        assertEquals(admin.getSystemId(), invoice.get("createdBySystemId").asText());
        assertTrue(invoice.get("consultationId").isNull());
        assertEquals("In-person consultation", invoice.get("lineItems").get(0).get("description").asText());
        assertEquals("2000.00", invoice.get("total").decimalValue().toPlainString());
    }

    // ==================== IDEMPOTENCY ====================

    @Test
    void generationIsIdempotentPerVisitAndCancelledInvoicesCanBeReplaced() throws Exception {
        Visit visit = fixtures.completedVisit(patient, doctor, "VIDEO");
        JsonNode first = created(doPost(INVOICES, doctor, forConsultation(visit.consultationId())));
        int invoiceId = first.get("invoiceId").asInt();

        Map<String, Object> different = forConsultation(visit.consultationId());
        different.put("lineItems", List.of(line("Something else", 2, "10.00")));
        JsonNode again = ok(doPost(INVOICES, doctor, different));
        assertEquals(invoiceId, again.get("invoiceId").asInt());
        assertEquals("1500.00", again.get("total").decimalValue().toPlainString());

        JsonNode byAppointment = ok(doPost(INVOICES, admin, forAppointment(visit.appointmentId())));
        assertEquals(invoiceId, byAppointment.get("invoiceId").asInt());
        assertEquals(1, invoiceCount());

        ok(doPost(INVOICES + "/" + invoiceId + "/cancel", doctor, Map.of("reason", "Wrong visit")));
        JsonNode replacement = created(doPost(INVOICES, doctor, forConsultation(visit.consultationId())));
        assertNotEquals(invoiceId, replacement.get("invoiceId").asInt());
        assertNotEquals(first.get("invoiceNo").asText(), replacement.get("invoiceNo").asText());
    }

    @Test
    void idempotencyKeyReplaysTheStoredResponse() throws Exception {
        Visit visit = fixtures.completedVisit(patient, doctor, "VIDEO");
        String key = "inv-" + clientId().substring(0, 12);

        JsonNode first = created(doPostWithHeaders(INVOICES, doctor, forConsultation(visit.consultationId()),
                Map.of("Idempotency-Key", key)));
        JsonNode replay = ok(doPostWithHeaders(INVOICES, doctor, forConsultation(visit.consultationId()),
                Map.of("Idempotency-Key", key)));

        assertEquals(first.get("invoiceId").asInt(), replay.get("invoiceId").asInt());
        assertEquals(first.get("invoiceNo").asText(), replay.get("invoiceNo").asText());
        assertEquals(1, invoiceCount());
        assertError(doPostWithHeaders(INVOICES, doctor, forConsultation(visit.consultationId()),
                        Map.of("Idempotency-Key", "bad key!")), 400,
                "Idempotency-Key must be 8 to 64 characters: letters, digits, '_' or '-'");
    }

    @Test
    void concurrentGenerationForOneVisitCreatesOneInvoice() throws Exception {
        Visit visit = fixtures.completedVisit(patient, doctor, "VIDEO");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> calls = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                User caller = i == 0 ? doctor : admin;
                Callable<MvcResult> call = () -> {
                    start.await();
                    return doPost(INVOICES, caller, forConsultation(visit.consultationId()));
                };
                calls.add(pool.submit(call));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            List<Integer> ids = new ArrayList<>();
            for (Future<MvcResult> call : calls) {
                MvcResult result = call.get(30, TimeUnit.SECONDS);
                statuses.add(result.getResponse().getStatus());
                ids.add(json(result).get("invoiceId").asInt());
            }
            assertTrue(statuses.contains(201), statuses.toString());
            assertTrue(statuses.contains(200), statuses.toString());
            assertEquals(ids.get(0), ids.get(1));
            assertEquals(1, invoiceCount());
        } finally {
            pool.shutdownNow();
        }
    }

    // ==================== AUTOMATIC INVOICE ====================

    @Test
    void publishedConsultationCompletedEventCreatesOneDraft() throws Exception {
        Visit visit = fixtures.completedVisit(patient, doctor, "PHONE");

        publishCompleted(visit.consultationId(), visit.appointmentId());
        publishCompleted(visit.consultationId(), visit.appointmentId());

        assertEquals(1, invoiceCount());
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM invoices");
        assertEquals("DRAFT", row.get("status"));
        assertEquals(doctor.getSystemId(), row.get("created_by_system_id"));
        assertEquals(doctor.getSystemId(), row.get("doctor_system_id"));
        assertEquals(visit.consultationId(), row.get("consultation_id"));
        assertEquals(0, new BigDecimal("1000.00").compareTo((BigDecimal) row.get("total")));
        assertEquals(0, events.stream(InvoiceIssuedEvent.class).count());
        assertEquals(0, events.stream(InvoiceStatusChangedEvent.class).count());

        // It is the visit's invoice: the doctor's POST returns it.
        JsonNode existing = ok(doPost(INVOICES, doctor, forAppointment(visit.appointmentId())));
        assertEquals(((Number) row.get("invoice_id")).intValue(), existing.get("invoiceId").asInt());
    }

    @Test
    void autoInvoiceInSentModeIssuesTheInvoiceOnceAndPublishesBothEvents() throws Exception {
        billingProperties.setAutoInvoice(BillingProperties.AUTO_INVOICE_SENT);
        Visit visit = fixtures.completedVisit(patient, doctor, "VIDEO");

        publishCompleted(visit.consultationId(), visit.appointmentId());
        publishCompleted(visit.consultationId(), visit.appointmentId());

        assertEquals(1, invoiceCount());
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM invoices");
        assertEquals("SENT", row.get("status"));
        assertTrue(row.get("issued_at") != null);
        List<InvoiceIssuedEvent> issued = events.stream(InvoiceIssuedEvent.class).toList();
        assertEquals(1, issued.size());
        assertEquals(patient.getSystemId(), issued.get(0).patientSystemId());
        assertEquals(doctor.getSystemId(), issued.get(0).doctorSystemId());
        assertEquals(new BigDecimal("1500.00"), issued.get(0).total());
        assertEquals("KES", issued.get(0).currency());
        List<InvoiceStatusChangedEvent> changed = events.stream(InvoiceStatusChangedEvent.class).toList();
        assertEquals(1, changed.size());
        assertEquals("DRAFT", changed.get(0).previousStatus());
        assertEquals("SENT", changed.get(0).status());

        // A free consultation stays DRAFT even in SENT mode.
        ok(doPut(DOCTOR_FEES, doctor, Map.of("fees", List.of(Map.of("appointmentType", "PHONE", "amount", 0)))));
        Visit free = fixtures.completedVisit(patient, doctor, "PHONE");
        publishCompleted(free.consultationId(), free.appointmentId());
        assertEquals("DRAFT", jdbc.queryForObject("SELECT status FROM invoices WHERE consultation_id = ?",
                String.class, free.consultationId()));
        assertEquals(1, events.stream(InvoiceIssuedEvent.class).count());
    }

    @Test
    void autoInvoiceOffCreatesNothing() {
        billingProperties.setAutoInvoice(BillingProperties.AUTO_INVOICE_OFF);
        Visit visit = fixtures.completedVisit(patient, doctor, "VIDEO");
        publishCompleted(visit.consultationId(), visit.appointmentId());
        assertEquals(0, invoiceCount());
    }

    // ==================== LOOKUPS AND LISTS ====================

    @Test
    void looksUpInvoicesByConsultationAndByAppointment() throws Exception {
        Visit visit = fixtures.completedVisit(patient, doctor, "VIDEO");
        Visit other = fixtures.completedVisit(patient, doctor, "PHONE");
        int invoiceId = created(doPost(INVOICES, doctor, forConsultation(visit.consultationId()))).get("invoiceId").asInt();
        created(doPost(INVOICES, doctor, forConsultation(other.consultationId())));

        JsonNode byConsultation = ok(doGet(DOCTOR_INVOICES + "?consultationId=" + visit.consultationId() + "&size=1", doctor));
        assertEquals(1, byConsultation.get("totalElements").asInt());
        assertEquals(invoiceId, byConsultation.get("content").get(0).get("invoiceId").asInt());

        JsonNode byAppointment = ok(doGet(DOCTOR_INVOICES + "?appointmentId=" + visit.appointmentId(), doctor));
        assertEquals(1, byAppointment.get("totalElements").asInt());
        assertEquals(invoiceId, byAppointment.get("content").get(0).get("invoiceId").asInt());

        JsonNode none = ok(doGet(DOCTOR_INVOICES + "?consultationId=999999&size=1", doctor));
        assertEquals(0, none.get("totalElements").asInt());

        JsonNode adminLookup = ok(doGet(INVOICES + "?patientSystemId=" + patient.getSystemId()
                + "&consultationId=" + visit.consultationId(), admin));
        assertEquals(1, adminLookup.get("totalElements").asInt());

        // The other doctor issued nothing; admins use their own list.
        assertEquals(0, ok(doGet(DOCTOR_INVOICES, otherDoctor)).get("totalElements").asInt());
        assertError(doGet(DOCTOR_INVOICES, admin), 403, ROLE_REFUSED);
        assertError(doGet(DOCTOR_INVOICES, patient), 403, URL_GATE);
        assertError(doGet(DOCTOR_INVOICES + "?patientSystemId=nope", doctor), 400,
                "patientSystemId: must be a valid system id");
    }

    @Test
    void uninvoicedListsCompletedConsultationsWithoutALiveInvoice() throws Exception {
        ok(doPut(DOCTOR_FEES, doctor, Map.of("fees", List.of(Map.of("appointmentType", "IN_PERSON", "amount", 1750)))));
        Visit invoiced = fixtures.completedVisit(patient, doctor, "VIDEO");
        Visit open = fixtures.completedVisit(otherPatient, doctor, "IN_PERSON");
        Integer scheduled = fixtures.appointment(patient, doctor, "VIDEO", "SCHEDULED");
        fixtures.consultation(scheduled, patient, doctor, "IN_PROGRESS", null);
        fixtures.completedVisit(otherPatient, otherDoctor, "VIDEO");
        int invoiceId = created(doPost(INVOICES, doctor, forConsultation(invoiced.consultationId()))).get("invoiceId").asInt();

        JsonNode page = ok(doGet(UNINVOICED, doctor));
        assertEquals(1, page.get("totalElements").asInt());
        JsonNode row = page.get("content").get(0);
        assertEquals(open.consultationId().intValue(), row.get("consultationId").asInt());
        assertEquals(open.appointmentId().intValue(), row.get("appointmentId").asInt());
        assertEquals("IN_PERSON", row.get("appointmentType").asText());
        assertEquals(otherPatient.getSystemId(), row.get("patientSystemId").asText());
        assertEquals("PATIENT User4", row.get("patientName").asText());
        assertFalse(row.get("completedAt").isNull());
        assertFalse(row.get("scheduledAt").isNull());
        assertEquals("1750.00", row.get("suggestedFee").decimalValue().toPlainString());
        assertEquals("KES", row.get("currency").asText());

        // A cancelled invoice frees the visit again.
        ok(doPost(INVOICES + "/" + invoiceId + "/cancel", doctor, null));
        JsonNode after = ok(doGet(UNINVOICED + "?size=0", doctor));
        assertEquals(2, after.get("totalElements").asInt());
        assertEquals(1, after.get("size").asInt());
        assertEquals(1, after.get("content").size());

        assertError(doGet(UNINVOICED, admin), 403, ROLE_REFUSED);
        assertError(doGet(UNINVOICED, patient), 403, URL_GATE);
        assertError(doGet(UNINVOICED, caregiver), 403, URL_GATE);
    }

    @Test
    void pagingIsClampedAndStatusFiltersAreValidated() throws Exception {
        sent("VIDEO");
        draft("PHONE");
        draft("IN_PERSON");

        JsonNode big = ok(doGet(ADMIN_INVOICES + "?size=500&page=-1", admin));
        assertEquals(100, big.get("size").asInt());
        assertEquals(0, big.get("page").asInt());
        assertEquals(3, big.get("totalElements").asInt());

        JsonNode tiny = ok(doGet(ADMIN_INVOICES + "?size=0", admin));
        assertEquals(1, tiny.get("size").asInt());
        assertEquals(3, tiny.get("totalPages").asInt());
        assertEquals(1, tiny.get("content").size());

        // Newest first.
        JsonNode all = ok(doGet(ADMIN_INVOICES, admin));
        assertTrue(all.get("content").get(0).get("invoiceId").asInt() > all.get("content").get(2).get("invoiceId").asInt());

        assertEquals(1, ok(doGet(ADMIN_INVOICES + "?status=sent", admin)).get("totalElements").asInt());
        assertEquals(3, ok(doGet(ADMIN_INVOICES + "?status=SENT, draft", admin)).get("totalElements").asInt());
        assertEquals(3, ok(doGet(ADMIN_INVOICES + "?patientSystemId=" + patient.getSystemId(), admin))
                .get("totalElements").asInt());
        assertError(doGet(ADMIN_INVOICES + "?status=BOGUS", admin), 400, "Unknown invoice status 'BOGUS'");
        assertError(doGet(ADMIN_INVOICES + "?patientSystemId=PAT-1", admin), 400,
                "patientSystemId: must be a valid system id");
        assertError(doGet(ADMIN_INVOICES, doctor), 403, URL_GATE);
    }

    // ==================== WHO MAY READ ====================

    @Test
    void patientsAndCaregiversNeverSeeDraftsAndOthersAreRefused() throws Exception {
        JsonNode draft = draft("VIDEO");
        int draftId = draft.get("invoiceId").asInt();
        grantAccess(caregiver, patient, a -> a.setCanViewBilling(true));

        assertError(doGet(INVOICES + "/" + draftId, patient), 404, "Invoice not found");
        assertError(doGet(INVOICES + "/" + draftId, caregiver), 404, "Invoice not found");
        assertEquals(0, ok(doGet(INVOICES + "/me", patient)).get("totalElements").asInt());
        assertEquals(0, ok(doGet(INVOICES + "?patientSystemId=" + patient.getSystemId() + "&status=DRAFT", caregiver))
                .get("totalElements").asInt());
        assertEquals("DRAFT", ok(doGet(INVOICES + "/" + draftId, doctor)).get("status").asText());
        assertEquals("DRAFT", ok(doGet(INVOICES + "/" + draftId, admin)).get("status").asText());

        // Cancelled before it was ever sent: still invisible.
        ok(doPost(INVOICES + "/" + draftId + "/cancel", doctor, null));
        assertError(doGet(INVOICES + "/" + draftId, patient), 404, "Invoice not found");

        JsonNode sent = sent("PHONE");
        int sentId = sent.get("invoiceId").asInt();
        assertEquals(sentId, ok(doGet(INVOICES + "/" + sentId, patient)).get("invoiceId").asInt());
        assertTrue(auditRows("READ", sentId) >= 1);
        JsonNode mine = ok(doGet(INVOICES + "/me", patient));
        assertEquals(1, mine.get("totalElements").asInt());
        assertEquals(1, ok(doGet(INVOICES, patient)).get("totalElements").asInt());

        assertError(doGet(INVOICES + "/" + sentId, otherPatient), 403, "Patients can only access their own data");
        assertError(doGet(INVOICES + "?patientSystemId=" + patient.getSystemId(), otherPatient), 403,
                "Patients can only access their own data");
        MvcResult unrelatedDoctor = doGet(INVOICES + "/" + sentId, otherDoctor);
        assertError(unrelatedDoctor, 403, "You can only view invoices you issued");
        assertEquals("INVOICE", json(unrelatedDoctor).get("resourceType").asText());
        assertError(doGet(INVOICES + "?patientSystemId=" + patient.getSystemId(), otherDoctor), 403,
                "You are not part of this patient's care team");
        assertError(doGet(INVOICES + "/me", doctor), 403, ROLE_REFUSED);
        assertError(doGet(INVOICES, caregiver), 400, "patientSystemId is required");
        assertError(doGet(INVOICES + "/999999", patient), 404, "Invoice not found");

        Long denials = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'ACCESS_DENIED' "
                + "AND resource_type = 'INVOICE' AND system_id = ?", Long.class, otherDoctor.getSystemId());
        assertTrue(denials != null && denials >= 1);
    }

    @Test
    void caregiverNeedsAnActiveGrantWithTheBillingFlag() throws Exception {
        int invoiceId = sent("VIDEO").get("invoiceId").asInt();
        String byId = INVOICES + "/" + invoiceId;
        String list = INVOICES + "?patientSystemId=" + patient.getSystemId();

        assertError(doGet(byId, caregiver), 403, "You don't have active proxy access to this patient");

        grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));
        assertError(doGet(byId, caregiver), 403, "Your proxy access for this patient does not allow viewing billing");
        assertError(doGet(list, caregiver), 403, "Your proxy access for this patient does not allow viewing billing");

        grantAccess(caregiver, patient, a -> a.setCanViewBilling(true));
        assertEquals(invoiceId, ok(doGet(byId, caregiver)).get("invoiceId").asInt());
        assertEquals(1, ok(doGet(list, caregiver)).get("totalElements").asInt());

        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        assertEquals(invoiceId, ok(doGet(byId, caregiver)).get("invoiceId").asInt());

        grantAccess(caregiver, patient, a -> {
            a.setCanViewBilling(true);
            a.setExpiresAt(LocalDateTime.now().minusDays(1));
        });
        assertError(doGet(byId, caregiver), 403, "You don't have active proxy access to this patient");

        grantAccess(caregiver, patient, a -> {
            a.setCanViewBilling(true);
            a.setIsActive(false);
            a.setRevokedAt(LocalDateTime.now());
        });
        assertError(doGet(byId, caregiver), 403, "You don't have active proxy access to this patient");
        assertError(doGet(list, caregiver), 403, "You don't have active proxy access to this patient");

        // A caregiver can never manage invoices.
        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        assertError(doPost(byId + "/cancel", caregiver, null), 403, ROLE_REFUSED);
        assertError(doGet(list, otherCaregiver), 403, "You don't have active proxy access to this patient");
    }

    // ==================== STATUS MACHINE ====================

    @Test
    void draftCanBeEditedSentAndCancelledWithIllegalTransitionsRefused() throws Exception {
        JsonNode draft = draft("VIDEO");
        int id = draft.get("invoiceId").asInt();
        String url = INVOICES + "/" + id;

        Map<String, Object> edit = new HashMap<>();
        edit.put("lineItems", List.of(line("Video consultation", 1, "1800.00")));
        edit.put("discount", new BigDecimal("300.00"));
        edit.put("notes", "Follow-up visit discount");
        JsonNode edited = ok(doPut(url, doctor, edit));
        assertEquals("DRAFT", edited.get("status").asText());
        assertEquals("1500.00", edited.get("total").decimalValue().toPlainString());
        assertEquals("Follow-up visit discount", edited.get("notes").asText());
        assertEquals(1, auditRows("UPDATE", id));

        Map<String, Object> tooMuch = new HashMap<>();
        tooMuch.put("lineItems", List.of(line("Video consultation", 1, "10.00")));
        tooMuch.put("discount", new BigDecimal("10.01"));
        assertError(doPut(url, doctor, tooMuch), 400, "Discount cannot exceed the subtotal");
        assertError(doPut(url, doctor, Map.of("lineItems", List.of())), 400, "lineItems: must not be empty");
        assertError(doPut(url, otherDoctor, edit), 403, "You can only manage invoices you issued");
        assertError(doPut(url, patient, edit), 403, ROLE_REFUSED);
        assertError(doPut(INVOICES + "/999999", doctor, edit), 404, "Invoice not found");
        assertError(doPost(url + "/send", otherDoctor, null), 403, "You can only manage invoices you issued");

        JsonNode sent = ok(doPost(url + "/send", doctor, Map.of()));
        assertEquals("SENT", sent.get("status").asText());
        assertFalse(sent.get("issuedAt").isNull());
        assertEquals(today().plusDays(14).toString(), sent.get("dueDate").asText());
        assertEquals("1500.00", sent.get("outstanding").decimalValue().toPlainString());
        assertEquals(1, events.stream(InvoiceIssuedEvent.class).count());
        assertEquals(1, events.stream(InvoiceStatusChangedEvent.class).count());

        // Same-status send is a no-op that publishes nothing.
        assertEquals("SENT", ok(doPost(url + "/send", doctor, null)).get("status").asText());
        assertEquals(1, events.stream(InvoiceIssuedEvent.class).count());

        assertError(doPut(url, doctor, edit), 409, "Only a draft invoice can be edited");

        JsonNode cancelled = ok(doPost(url + "/cancel", admin, Map.of("reason", "  Issued for the wrong visit ")));
        assertEquals("CANCELLED", cancelled.get("status").asText());
        assertEquals("Issued for the wrong visit", cancelled.get("cancelReason").asText());
        assertEquals("CANCELLED", ok(doPost(url + "/cancel", doctor, null)).get("status").asText());
        assertEquals(2, events.stream(InvoiceStatusChangedEvent.class).count());
        assertError(doPost(url + "/send", doctor, null), 409, "An invoice in status CANCELLED cannot be sent");
        assertError(doPost(url + "/cancel", doctor, Map.of("reason", "x".repeat(501))), 400,
                "reason: size must be between 0 and 500");

        // Patients see a cancelled invoice that was issued before.
        assertEquals("CANCELLED", ok(doGet(url, patient)).get("status").asText());
    }

    @Test
    void zeroTotalInvoiceCannotBeSent() throws Exception {
        ok(doPut(DOCTOR_FEES, doctor, Map.of("fees", List.of(Map.of("appointmentType", "VIDEO", "amount", 0)))));
        JsonNode draft = draft("VIDEO");
        assertEquals("0.00", draft.get("total").decimalValue().toPlainString());
        assertError(doPost(INVOICES + "/" + draft.get("invoiceId").asInt() + "/send", doctor, null), 409,
                "An invoice with a zero total cannot be sent");
    }

    // ==================== FACADE ====================

    @Test
    void applyAndReversePaymentsFollowTheStatusMachine() throws Exception {
        int id = sent("VIDEO").get("invoiceId").asInt();
        events.clear();

        InvoiceResponse partial = invoiceService.applyPayment(id, new BigDecimal("500.00"));
        assertEquals("PARTIALLY_PAID", partial.status());
        assertEquals(new BigDecimal("500.00"), partial.amountPaid());
        assertEquals(new BigDecimal("1000.00"), partial.outstanding());
        assertEquals(new BigDecimal("1000.00"), invoiceService.outstanding(id));

        // A partial payment that leaves the status unchanged publishes nothing.
        invoiceService.applyPayment(id, new BigDecimal("100.00"));
        assertEquals(1, events.stream(InvoiceStatusChangedEvent.class).count());

        ConflictException over = assertThrows(ConflictException.class,
                () -> invoiceService.applyPayment(id, new BigDecimal("900.01")));
        assertEquals("Payment exceeds the outstanding balance", over.getMessage());
        BadRequestException zero = assertThrows(BadRequestException.class,
                () -> invoiceService.applyPayment(id, BigDecimal.ZERO));
        assertEquals("Amount must be greater than zero", zero.getMessage());
        assertThrows(BadRequestException.class, () -> invoiceService.applyPayment(id, null));

        InvoiceResponse paid = invoiceService.applyPayment(id, new BigDecimal("900.00"));
        assertEquals("PAID", paid.status());
        assertTrue(paid.paidAt() != null);
        assertEquals(new BigDecimal("0.00"), paid.outstanding());
        assertEquals(new BigDecimal("0.00"), invoiceService.outstanding(id));
        ConflictException paidAgain = assertThrows(ConflictException.class,
                () -> invoiceService.applyPayment(id, new BigDecimal("1.00")));
        assertEquals("An invoice in status PAID cannot be paid", paidAgain.getMessage());

        // System cancel refused after a payment, and so is the doctor's cancel.
        ConflictException voided = assertThrows(ConflictException.class,
                () -> invoiceService.cancelBySystem(id, "Void"));
        assertEquals("An invoice with payments cannot be cancelled", voided.getMessage());
        assertError(doPost(INVOICES + "/" + id + "/cancel", doctor, null), 409,
                "An invoice with payments cannot be cancelled");

        InvoiceResponse partlyRefunded = invoiceService.reversePayment(id, new BigDecimal("500.00"));
        assertEquals("PARTIALLY_PAID", partlyRefunded.status());
        assertTrue(partlyRefunded.paidAt() == null);
        assertEquals(new BigDecimal("1000.00"), partlyRefunded.amountPaid());
        ConflictException refundTooMuch = assertThrows(ConflictException.class,
                () -> invoiceService.reversePayment(id, new BigDecimal("1000.01")));
        assertEquals("Refund exceeds the amount paid", refundTooMuch.getMessage());

        InvoiceResponse refunded = invoiceService.reversePayment(id, new BigDecimal("1000.00"));
        assertEquals("REFUNDED", refunded.status());
        assertEquals(new BigDecimal("0.00"), refunded.amountPaid());
        assertEquals(new BigDecimal("0.00"), invoiceService.outstanding(id));
        ConflictException refundRefunded = assertThrows(ConflictException.class,
                () -> invoiceService.reversePayment(id, new BigDecimal("1.00")));
        assertEquals("An invoice in status REFUNDED cannot be refunded", refundRefunded.getMessage());
        ConflictException cancelRefunded = assertThrows(ConflictException.class,
                () -> invoiceService.cancelBySystem(id, "Void"));
        assertEquals("An invoice in status REFUNDED cannot be cancelled", cancelRefunded.getMessage());

        List<String> transitions = events.stream(InvoiceStatusChangedEvent.class)
                .map(e -> e.previousStatus() + ">" + e.status()).toList();
        assertEquals(List.of("SENT>PARTIALLY_PAID", "PARTIALLY_PAID>PAID", "PAID>PARTIALLY_PAID",
                "PARTIALLY_PAID>REFUNDED"), transitions);

        // A refunded invoice still counts as the visit's invoice.
        Integer consultationId = jdbc.queryForObject("SELECT consultation_id FROM invoices WHERE invoice_id = ?",
                Integer.class, id);
        assertEquals(id, ok(doPost(INVOICES, doctor, forConsultation(consultationId))).get("invoiceId").asInt());

        assertThrows(ResourceNotFoundException.class, () -> invoiceService.outstanding(999999));
        JsonNode draft = draft("PHONE");
        ConflictException draftPayment = assertThrows(ConflictException.class,
                () -> invoiceService.applyPayment(draft.get("invoiceId").asInt(), BigDecimal.ONE));
        assertEquals("An invoice in status DRAFT cannot be paid", draftPayment.getMessage());
        assertEquals(new BigDecimal("0.00"), invoiceService.outstanding(draft.get("invoiceId").asInt()));
    }

    @Test
    void markOverdueAndSystemCancelWorkWithoutACaller() throws Exception {
        int notDue = sent("VIDEO").get("invoiceId").asInt();
        int due = sent("PHONE").get("invoiceId").asInt();
        int draftId = draft("IN_PERSON").get("invoiceId").asInt();
        jdbc.update("UPDATE invoices SET due_date = ? WHERE invoice_id = ?", today().minusDays(1), due);
        clearAuthentication();
        events.clear();

        assertEquals("SENT", invoiceService.markOverdue(notDue).status());
        assertEquals("DRAFT", invoiceService.markOverdue(draftId).status());
        assertEquals(0, events.stream(InvoiceStatusChangedEvent.class).count());

        InvoiceResponse overdue = invoiceService.markOverdue(due);
        assertEquals("OVERDUE", overdue.status());
        assertEquals("OVERDUE", invoiceService.markOverdue(due).status());
        assertEquals(1, events.stream(InvoiceStatusChangedEvent.class).count());
        assertTrue(auditRows("UPDATE", due) >= 1);

        // OVERDUE takes a part payment (back to PARTIALLY_PAID, which the sweeper can mark again).
        assertEquals("PARTIALLY_PAID", invoiceService.applyPayment(due, new BigDecimal("100.00")).status());
        assertEquals("OVERDUE", invoiceService.markOverdue(due).status());
        assertEquals(new BigDecimal("900.00"), invoiceService.outstanding(due));

        InvoiceResponse voided = invoiceService.cancelBySystem(notDue, "Duplicate charge");
        assertEquals("CANCELLED", voided.status());
        assertEquals("Duplicate charge", voided.cancelReason());
        assertEquals("CANCELLED", invoiceService.cancelBySystem(notDue, "Again").status());
        assertEquals("CANCELLED", invoiceService.cancelBySystem(draftId, null).status());
        assertThrows(ResourceNotFoundException.class, () -> invoiceService.markOverdue(999999));
    }

    // ==================== FEES ====================

    @Test
    void feesResolveDoctorOverrideThenDefaultThenZero() throws Exception {
        JsonNode defaults = ok(doGet(FEES, patient));
        assertEquals(3, defaults.size());
        assertEquals("VIDEO", defaults.get(0).get("appointmentType").asText());
        assertEquals("IN_PERSON", defaults.get(1).get("appointmentType").asText());
        assertEquals("PHONE", defaults.get(2).get("appointmentType").asText());
        assertEquals("1500.00", defaults.get(0).get("amount").decimalValue().toPlainString());
        assertEquals("DEFAULT", defaults.get(0).get("source").asText());
        assertTrue(defaults.get(0).get("doctorSystemId").isNull());
        assertEquals("KES", defaults.get(0).get("currency").asText());

        JsonNode own = ok(doPut(DOCTOR_FEES, doctor, Map.of("fees", List.of(
                Map.of("appointmentType", " video ", "amount", new BigDecimal("2000.00"))))));
        assertEquals("DOCTOR", own.get(0).get("source").asText());
        assertEquals("2000.00", own.get(0).get("amount").decimalValue().toPlainString());
        assertEquals("1500.00", own.get(0).get("defaultAmount").decimalValue().toPlainString());
        assertEquals(doctor.getSystemId(), own.get(0).get("doctorSystemId").asText());
        assertEquals("DEFAULT", own.get(1).get("source").asText());

        jdbc.update("UPDATE billing_fee_schedule SET is_active = FALSE WHERE doctor_system_id IS NULL "
                + "AND appointment_type = 'PHONE'");
        JsonNode forDoctor = ok(doGet(FEES + "?doctorSystemId=" + doctor.getSystemId(), caregiver));
        assertEquals("DOCTOR", forDoctor.get(0).get("source").asText());
        assertEquals("NONE", forDoctor.get(2).get("source").asText());
        assertEquals("0.00", forDoctor.get(2).get("amount").decimalValue().toPlainString());
        assertEquals("0.00", forDoctor.get(2).get("defaultAmount").decimalValue().toPlainString());
        // Another doctor (or an id with no rows) resolves to the defaults.
        assertEquals("DEFAULT", ok(doGet(FEES + "?doctorSystemId=DOC-99999", otherPatient)).get(0).get("source").asText());

        // The override prices the generated line.
        Visit visit = fixtures.completedVisit(patient, doctor, "VIDEO");
        JsonNode invoice = created(doPost(INVOICES, doctor, forConsultation(visit.consultationId())));
        assertEquals("2000.00", invoice.get("total").decimalValue().toPlainString());

        // amount null removes the override.
        Map<String, Object> remove = new HashMap<>();
        remove.put("appointmentType", "VIDEO");
        remove.put("amount", null);
        JsonNode removed = ok(doPut(DOCTOR_FEES, doctor, Map.of("fees", List.of(remove))));
        assertEquals("DEFAULT", removed.get(0).get("source").asText());
        assertEquals("1500.00", removed.get(0).get("amount").decimalValue().toPlainString());

        JsonNode adminDefaults = ok(doPut(ADMIN_FEES, admin, Map.of("fees", List.of(
                Map.of("appointmentType", "PHONE", "amount", new BigDecimal("0.00")),
                Map.of("appointmentType", "VIDEO", "amount", new BigDecimal("1600.50"))))));
        assertEquals("1600.50", adminDefaults.get(0).get("amount").decimalValue().toPlainString());
        assertEquals("DEFAULT", adminDefaults.get(2).get("source").asText());
        assertEquals("0.00", adminDefaults.get(2).get("amount").decimalValue().toPlainString());
        assertTrue(adminDefaults.get(0).get("doctorSystemId").isNull());

        Long feeAudits = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'FEE_UPDATE' "
                + "AND resource_type = 'INVOICE'", Long.class);
        assertEquals(3L, feeAudits);
    }

    @Test
    void feeUpdatesAreValidatedAndRoleGated() throws Exception {
        assertError(doPut(DOCTOR_FEES, doctor, Map.of("fees", List.of(
                        Map.of("appointmentType", "VIDEO", "amount", 1), Map.of("appointmentType", "video", "amount", 2)))),
                400, "Each appointment type may appear only once");
        MvcResult negative = doPut(DOCTOR_FEES, doctor, Map.of("fees", List.of(
                Map.of("appointmentType", "VIDEO", "amount", new BigDecimal("-1")))));
        assertError(negative, 400, "fees[0].amount: must not be negative");
        assertEquals("VALIDATION_FAILED", json(negative).get("code").asText());
        assertError(doPut(DOCTOR_FEES, doctor, Map.of("fees", List.of(
                        Map.of("appointmentType", "VIDEO", "amount", new BigDecimal("1.234"))))),
                400, "fees[0].amount: must have at most 6 digits and 2 decimals");
        assertError(doPut(DOCTOR_FEES, doctor, Map.of("fees", List.of(Map.of("appointmentType", "HOME", "amount", 1)))),
                400, "fees[0].appointmentType: must be one of VIDEO, IN_PERSON, PHONE");
        assertError(doPut(DOCTOR_FEES, doctor, Map.of("fees", List.of())), 400, "fees: must not be empty");

        Map<String, Object> noAmount = new HashMap<>();
        noAmount.put("appointmentType", "VIDEO");
        noAmount.put("amount", null);
        assertError(doPut(ADMIN_FEES, admin, Map.of("fees", List.of(noAmount))), 400, "fees[0].amount: must not be null");

        assertError(doPut(DOCTOR_FEES, admin, Map.of("fees", List.of(Map.of("appointmentType", "VIDEO", "amount", 1)))),
                403, ROLE_REFUSED);
        assertError(doPut(DOCTOR_FEES, patient, Map.of("fees", List.of(Map.of("appointmentType", "VIDEO", "amount", 1)))),
                403, URL_GATE);
        assertError(doPut(ADMIN_FEES, doctor, Map.of("fees", List.of(Map.of("appointmentType", "VIDEO", "amount", 1)))),
                403, URL_GATE);
        assertError(doGet(FEES + "?doctorSystemId=doctor-1", patient), 400, "doctorSystemId: must be a valid system id");

        // Nothing changed.
        JsonNode defaults = ok(doGet(FEES, admin));
        assertEquals("1500.00", defaults.get(0).get("amount").decimalValue().toPlainString());
        assertEquals("2000.00", defaults.get(1).get("amount").decimalValue().toPlainString());
        assertEquals("1000.00", defaults.get(2).get("amount").decimalValue().toPlainString());
    }

    // ==================== SETTINGS ====================

    @Test
    void currencySettingChangesTheNextInvoiceAndTheFees() throws Exception {
        JsonNode before = draft("VIDEO");
        assertEquals("KES", before.get("currency").asText());

        setSetting("billing.default-currency", "USD");

        JsonNode after = draft("VIDEO");
        assertEquals("USD", after.get("currency").asText());
        assertEquals("USD", ok(doGet(FEES, patient)).get(0).get("currency").asText());
        // Stamped at creation, never changed afterwards.
        assertEquals("KES", ok(doGet(INVOICES + "/" + before.get("invoiceId").asInt(), admin)).get("currency").asText());
    }

    @Test
    void paymentTermsSettingChangesTheNextDueDate() throws Exception {
        JsonNode before = draft("VIDEO");
        assertEquals(today().plusDays(14).toString(), before.get("dueDate").asText());

        setSetting("billing.payment-terms-days", "30");

        JsonNode after = draft("VIDEO");
        assertEquals(today().plusDays(30).toString(), after.get("dueDate").asText());
        // Terms count again from the issue day.
        JsonNode sentBefore = ok(doPost(INVOICES + "/" + before.get("invoiceId").asInt() + "/send", doctor, null));
        assertEquals(today().plusDays(30).toString(), sentBefore.get("dueDate").asText());
    }

    @Test
    void taxRateSettingChangesTheNextInvoice() throws Exception {
        JsonNode before = draft("IN_PERSON");
        assertEquals("0.00", before.get("tax").decimalValue().toPlainString());
        assertEquals("2000.00", before.get("total").decimalValue().toPlainString());

        setSetting("billing.tax-rate", "0.1");

        JsonNode after = draft("IN_PERSON");
        assertEquals("200.00", after.get("tax").decimalValue().toPlainString());
        assertEquals("2200.00", after.get("total").decimalValue().toPlainString());
        // A draft edit recomputes with the current rate; sending does not recompute.
        int beforeId = before.get("invoiceId").asInt();
        JsonNode edited = ok(doPut(INVOICES + "/" + beforeId, doctor,
                Map.of("lineItems", List.of(line("In-person consultation", 1, "2000.00")))));
        assertEquals("200.00", edited.get("tax").decimalValue().toPlainString());
        setSetting("billing.tax-rate", "0");
        JsonNode sentAfter = ok(doPost(INVOICES + "/" + beforeId + "/send", doctor, null));
        assertEquals("2200.00", sentAfter.get("total").decimalValue().toPlainString());
    }
}
