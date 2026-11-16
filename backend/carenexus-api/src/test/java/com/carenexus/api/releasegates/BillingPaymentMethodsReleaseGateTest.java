package com.carenexus.api.releasegates;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.event.PaymentRecordedEvent;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Release gates of saved payment methods, the administrator payment list and manual payments
 * (be-billing-payment-methods): ownership (another patient's method is 404), caregiver read with and without
 * MANAGE_BILLING (flag off, VIEW_ONLY, FULL_ACCESS, expired, revoked), doctor refusal, default switching and
 * promotion, masked labels with no token, phone, policy or member number in any response, validation per type,
 * duplicate and cap rules, manual payments that settle and partially settle an invoice, non-admin 403 on the admin
 * routes, Idempotency-Key replay on both create endpoints, and paging and filters of the admin list. Runs with
 * committed transactions (CommittedIntegrationTest).
 */
@RecordApplicationEvents
class BillingPaymentMethodsReleaseGateTest extends CommittedIntegrationTest {

    private static final String METHODS = "/api/billing/payment-methods";
    private static final String ADMIN_PAYMENTS = "/api/admin/billing/payments";
    private static final String URL_GATE = "You don't have permission to access this resource";
    private static final String ROLE_REFUSED = "Your role is not authorized to perform this action";
    private static final String NOT_FOUND = "Payment method not found";
    private static final String NO_GRANT = "You don't have active proxy access to this patient";
    private static final String NO_MANAGE = "Your proxy access for this patient does not allow managing billing and payments";
    private static final String DOTS = " •••• ";

    @Autowired
    private ApplicationEvents events;

    /** Reads money exactly as serialized (1500.00 stays 1500.00), so the two-decimal wire format is asserted. */
    private ObjectMapper exactMapper;

    private int invoiceSeq;

    @BeforeEach
    void setUpMapper() {
        exactMapper = objectMapper.copy()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
        exactMapper.configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);
        invoiceSeq = 0;
    }

    /**
     * JSON is UTF-8 (RFC 8259); MockMvc would decode a response without a charset parameter as ISO-8859-1 and mangle
     * the bullets of the masked labels.
     */
    @Override
    protected JsonNode json(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return content.isBlank() ? exactMapper.nullNode() : exactMapper.readTree(content);
    }

    // ==================== HELPERS ====================

    private static Map<String, Object> card(String provider, String lastFour) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "CARD");
        body.put("provider", provider);
        body.put("lastFour", lastFour);
        return body;
    }

    private static Map<String, Object> mpesa(String phone) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "MPESA");
        body.put("mpesaPhone", phone);
        return body;
    }

    private static Map<String, Object> insurance(String insurer, String policy, String member) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "INSURANCE");
        body.put("insurerName", insurer);
        body.put("policyNumber", policy);
        body.put("memberNumber", member);
        return body;
    }

    private static Map<String, Object> withDefault(Map<String, Object> body) {
        body.put("makeDefault", true);
        return body;
    }

    private static Map<String, Object> manual(String method, String amount, String reference) {
        Map<String, Object> body = new HashMap<>();
        body.put("method", method);
        body.put("amount", amount == null ? null : new BigDecimal(amount));
        body.put("reference", reference);
        return body;
    }

    private static String manualUrl(Integer invoiceId) {
        return "/api/admin/billing/invoices/" + invoiceId + "/payments/manual";
    }

    private JsonNode expect(MvcResult result, int status) throws Exception {
        assertEquals(status, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result);
    }

    private void assertError(MvcResult result, int status, String message) throws Exception {
        assertEquals(status, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(message, message(result));
    }

    private void assertValidation(MvcResult result, String message) throws Exception {
        assertError(result, 400, message);
        assertEquals("VALIDATION_FAILED", json(result).path("code").asText());
    }

    private Integer save(User owner, Map<String, Object> body) throws Exception {
        return expect(doPost(METHODS, owner, body), 201).path("methodId").asInt();
    }

    private JsonNode myMethods(User owner) throws Exception {
        return expect(doGet(METHODS, owner), 200);
    }

    private List<Integer> defaultIds(JsonNode list) {
        List<Integer> ids = new ArrayList<>();
        list.forEach(row -> {
            if (row.path("isDefault").asBoolean()) {
                ids.add(row.path("methodId").asInt());
            }
        });
        return ids;
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    /** An invoice of {@code forPatient} issued by the fixture doctor, inserted directly. */
    private Integer invoice(User forPatient, String status, String total, String amountPaid) {
        invoiceSeq++;
        LocalDateTime now = LocalDateTime.now();
        boolean issued = !"DRAFT".equals(status);
        return jdbc.queryForObject("INSERT INTO invoices (patient_system_id, doctor_system_id, created_by_system_id, "
                        + "invoice_no, line_items, subtotal, tax, discount, total, amount_paid, currency, status, due_date, "
                        + "issued_at, created_at, updated_at) VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, 0.00, 0.00, ?, ?, "
                        + "'KES', ?, ?, ?, ?, ?) RETURNING invoice_id",
                Integer.class,
                forPatient.getSystemId(), doctor.getSystemId(), doctor.getSystemId(),
                String.format("INV-2026-%06d", 700000 + invoiceSeq),
                "[{\"description\": \"Video consultation\", \"quantity\": 1, \"unitPrice\": " + total
                        + ", \"amount\": " + total + "}]",
                new BigDecimal(total), new BigDecimal(total), new BigDecimal(amountPaid), status,
                issued ? LocalDate.now().plusDays(14) : null,
                issued ? Timestamp.valueOf(now.minusDays(1)) : null,
                Timestamp.valueOf(now.minusDays(1)), Timestamp.valueOf(now.minusDays(1)));
    }

    private Map<String, Object> invoiceRow(Integer invoiceId) {
        return jdbc.queryForMap("SELECT status, amount_paid, paid_at FROM invoices WHERE invoice_id = ?", invoiceId);
    }

    private long paymentsOf(Integer invoiceId) {
        return count("SELECT COUNT(*) FROM payments WHERE invoice_id = ?", invoiceId);
    }

    private List<PaymentRecordedEvent> recorded() {
        return events.stream(PaymentRecordedEvent.class).toList();
    }

    // ==================== SAVING, MASKING, SECRETS ====================

    @Test
    void patientSavesEachTypeWithAServerBuiltMaskedLabelAndNoSecretInAnyResponse() throws Exception {
        Map<String, Object> visa = card("Visa", "4242");
        // Properties the API has no field for are dropped, never stored or echoed.
        visa.put("cardNumber", "4242424242424242");
        visa.put("cvv", "123");
        visa.put("expiry", "12/29");
        visa.put("token", "tok_supplied_by_client");
        visa.put("providerToken", "tok_supplied_by_client");
        MvcResult first = doPost(METHODS, patient, visa);
        JsonNode card = expect(first, 201);
        assertEquals("CARD", card.path("type").asText());
        assertEquals("Visa", card.path("provider").asText());
        assertEquals("4242", card.path("lastFour").asText());
        assertTrue(card.path("insurerName").isNull());
        assertEquals("Visa" + DOTS + "4242", card.path("displayLabel").asText());
        assertTrue(card.path("isDefault").asBoolean(), "the first method is the default whatever makeDefault says");
        assertEquals(patient.getSystemId(), card.path("systemId").asText());
        assertFalse(card.path("createdAt").isNull());
        assertTrue(card.has("isDefault") && !card.has("default"), "the JSON name keeps the is prefix");

        JsonNode phone = expect(doPost(METHODS, patient, mpesa("  +254 712 345 678  ")), 201);
        assertEquals("M-PESA", phone.path("provider").asText());
        assertEquals("5678", phone.path("lastFour").asText());
        assertEquals("M-PESA" + DOTS + "5678", phone.path("displayLabel").asText());
        assertFalse(phone.path("isDefault").asBoolean());

        JsonNode policy = expect(doPost(METHODS, patient,
                insurance("Jubilee Insurance", "JUB-2026-778841", "M-00231")), 201);
        assertEquals("Jubilee Insurance", policy.path("provider").asText());
        assertEquals("Jubilee Insurance", policy.path("insurerName").asText());
        assertEquals("8841", policy.path("lastFour").asText());
        assertEquals("Jubilee Insurance" + DOTS + "8841", policy.path("displayLabel").asText());

        JsonNode brandless = expect(doPost(METHODS, patient, card(null, "1111")), 201);
        assertTrue(brandless.path("provider").isNull());
        assertEquals("Card" + DOTS + "1111", brandless.path("displayLabel").asText());

        MvcResult listResult = doGet(METHODS, patient);
        JsonNode list = expect(listResult, 200);
        assertEquals(4, list.size());
        assertEquals(card.path("methodId").asInt(), list.get(0).path("methodId").asInt(), "default first");
        assertEquals(List.of(card.path("methodId").asInt()), defaultIds(list));
        // After the default: newest first.
        assertEquals(brandless.path("methodId").asInt(), list.get(1).path("methodId").asInt());

        List<String> bodies = List.of(first.getResponse().getContentAsString(), listResult.getResponse().getContentAsString(),
                doGet(METHODS + "/patient/" + patient.getSystemId(), admin).getResponse().getContentAsString());
        for (String body : bodies) {
            for (String secret : List.of("4242424242424242", "tok_supplied_by_client", "local_pm_", "712 345", "712345",
                    "254712", "JUB-2026", "778841", "M-00231", "mpesaPhone", "policyNumber", "memberNumber",
                    "providerToken", "\"token\"", "cvv", "expiry", "isActive", "isDeleted")) {
                assertFalse(body.contains(secret), "response leaks " + secret + ": " + body);
            }
        }

        // Stored: the trimmed phone, the policy and member numbers, and an opaque local token.
        Map<String, Object> stored = jdbc.queryForMap("SELECT mpesa_phone, provider_token FROM payment_methods "
                + "WHERE method_id = ?", phone.path("methodId").asInt());
        assertEquals("+254 712 345 678", stored.get("mpesa_phone"));
        assertTrue(((String) stored.get("provider_token")).matches("^local_pm_[0-9a-f]{32}$"));
        assertEquals(4, count("SELECT COUNT(DISTINCT provider_token) FROM payment_methods WHERE system_id = ?",
                patient.getSystemId()));
        assertEquals(0, count("SELECT COUNT(*) FROM payment_methods WHERE provider_token LIKE '%tok_supplied%'"));
        Map<String, Object> policyRow = jdbc.queryForMap("SELECT policy_number, member_number, mpesa_phone "
                + "FROM payment_methods WHERE method_id = ?", policy.path("methodId").asInt());
        assertEquals("JUB-2026-778841", policyRow.get("policy_number"));
        assertEquals("M-00231", policyRow.get("member_number"));
        assertEquals(null, policyRow.get("mpesa_phone"));
        // A card stores no field of another type.
        Map<String, Object> cardRow = jdbc.queryForMap("SELECT mpesa_phone, insurer_name, policy_number "
                + "FROM payment_methods WHERE method_id = ?", card.path("methodId").asInt());
        assertTrue(cardRow.values().stream().allMatch(value -> value == null));

        // Audit: CREATE per save, READ for a non-empty list, nothing for an empty one.
        assertEquals(4, count("SELECT COUNT(*) FROM audit_log WHERE action = 'CREATE' "
                + "AND resource_type = 'PAYMENT_METHOD' AND patient_system_id = ?", patient.getSystemId()));
        long reads = count("SELECT COUNT(*) FROM audit_log WHERE action = 'READ' AND resource_type = 'PAYMENT_METHOD'");
        expect(doGet(METHODS, otherPatient), 200);
        assertEquals(reads, count("SELECT COUNT(*) FROM audit_log WHERE action = 'READ' "
                + "AND resource_type = 'PAYMENT_METHOD'"));
    }

    @Test
    void fieldsOfAnotherTypeAreIgnoredAndTheServerSetsProviderAndLastFour() throws Exception {
        Map<String, Object> body = mpesa("0712 345 678");
        body.put("provider", "Safaricom");
        body.put("lastFour", "9999");
        body.put("insurerName", "Jubilee Insurance");
        body.put("policyNumber", "POL-0001");
        JsonNode phone = expect(doPost(METHODS, patient, body), 201);
        assertEquals("M-PESA", phone.path("provider").asText());
        assertEquals("5678", phone.path("lastFour").asText());
        assertTrue(phone.path("insurerName").isNull());
        Map<String, Object> row = jdbc.queryForMap("SELECT insurer_name, policy_number FROM payment_methods "
                + "WHERE method_id = ?", phone.path("methodId").asInt());
        assertTrue(row.values().stream().allMatch(value -> value == null));

        Map<String, Object> claim = insurance("AAR", "  pol-77 12  ", null);
        claim.put("mpesaPhone", "+254700000000");
        JsonNode policy = expect(doPost(METHODS, patient, claim), 201);
        assertEquals("7 12", policy.path("lastFour").asText(), "last four characters of the trimmed policy number");
        assertEquals("AAR" + DOTS + "7 12", policy.path("displayLabel").asText());
    }

    // ==================== VALIDATION PER TYPE ====================

    @Test
    void validationPerTypeAnswers400AndStoresNothing() throws Exception {
        Map<String, Object> noType = card("Visa", "4242");
        noType.remove("type");
        assertValidation(doPost(METHODS, patient, noType), "type: must not be blank");

        Map<String, Object> cash = new HashMap<>();
        cash.put("type", "CASH");
        assertValidation(doPost(METHODS, patient, cash), "type: must be one of CARD, MPESA, INSURANCE");
        Map<String, Object> crypto = new HashMap<>();
        crypto.put("type", "crypto");
        assertValidation(doPost(METHODS, patient, crypto), "type: must be one of CARD, MPESA, INSURANCE");

        assertValidation(doPost(METHODS, patient, card("Visa", "42a2")), "lastFour: must be exactly 4 digits");
        assertValidation(doPost(METHODS, patient, card("Visa", "42424")), "lastFour: must be exactly 4 digits");
        assertValidation(doPost(METHODS, patient, card("V".repeat(101), "4242")),
                "provider: size must be between 0 and 100");
        assertError(doPost(METHODS, patient, card("Visa", null)), 400, "lastFour is required for a card");
        assertError(doPost(METHODS, patient, card("Visa", "   ")), 400, "lastFour is required for a card");

        assertError(doPost(METHODS, patient, mpesa(null)), 400, "mpesaPhone is required for M-PESA");
        assertValidation(doPost(METHODS, patient, mpesa("call me")), "mpesaPhone: must be a valid phone number");
        assertValidation(doPost(METHODS, patient, mpesa("12345")), "mpesaPhone: must be a valid phone number");

        assertError(doPost(METHODS, patient, insurance(null, "POL-1234", null)), 400,
                "insurerName is required for insurance");
        assertError(doPost(METHODS, patient, insurance("AAR", null, null)), 400,
                "policyNumber is required for insurance");
        assertValidation(doPost(METHODS, patient, insurance("AAR", " 12 ", null)),
                "policyNumber: size must be between 4 and 100");
        assertValidation(doPost(METHODS, patient, insurance("AAR", "POL-1234", "M".repeat(101))),
                "memberNumber: size must be between 0 and 100");

        // Several invalid fields: the first one alphabetically is the message, every one is listed.
        Map<String, Object> many = card("V".repeat(101), "12");
        MvcResult both = doPost(METHODS, patient, many);
        assertValidation(both, "lastFour: must be exactly 4 digits");
        assertEquals(2, json(both).path("errors").size());

        assertEquals(0, count("SELECT COUNT(*) FROM payment_methods"));
    }

    @Test
    void sameInstrumentTwiceAndAnEleventhMethodAre409() throws Exception {
        save(patient, card("Visa", "4242"));
        assertError(doPost(METHODS, patient, card(" visa ", "4242")), 409, "This payment method is already saved");
        save(patient, card("Mastercard", "4242"));
        save(patient, card(null, "4242"));
        assertError(doPost(METHODS, patient, card(null, "4242")), 409, "This payment method is already saved");

        save(patient, mpesa("+254 712 345 678"));
        assertError(doPost(METHODS, patient, mpesa("+254-712-345-678")), 409, "This payment method is already saved");

        save(patient, insurance("Jubilee", "POL-1234", null));
        assertError(doPost(METHODS, patient, insurance("JUBILEE", "pol-1234", "other")), 409,
                "This payment method is already saved");

        // The same instrument of another patient is not a duplicate.
        save(otherPatient, card("Visa", "4242"));

        // A removed instrument can be saved again (new row, new id).
        Integer removed = save(patient, card("Amex", "0005"));
        expect(doDelete(METHODS + "/" + removed, patient), 204);
        Integer again = save(patient, card("Amex", "0005"));
        assertTrue(again > removed);

        // Six active now; fill up to ten, then the eleventh is refused.
        assertEquals(6, myMethods(patient).size());
        for (int i = 0; i < 4; i++) {
            save(patient, card("Visa", "100" + i));
        }
        assertEquals(10, myMethods(patient).size());
        assertError(doPost(METHODS, patient, card("Visa", "2000")), 409, "You can save at most 10 payment methods");
        // Duplicate is checked before the cap.
        assertError(doPost(METHODS, patient, card("Visa", "4242")), 409, "This payment method is already saved");
        assertEquals(10, count("SELECT COUNT(*) FROM payment_methods WHERE system_id = ? AND is_active AND NOT is_deleted",
                patient.getSystemId()));
    }

    // ==================== ROLES AND OWNERSHIP ====================

    @Test
    void onlyThePatientCanListAddChangeOrRemoveOwnMethods() throws Exception {
        Integer method = save(patient, card("Visa", "4242"));
        grantAccess(caregiver, patient, access -> access.setAccessLevel("FULL_ACCESS"));

        for (User other : List.of(caregiver, doctor, admin)) {
            assertError(doGet(METHODS, other), 403, ROLE_REFUSED);
            assertError(doPost(METHODS, other, card("Visa", "1234")), 403, ROLE_REFUSED);
            assertError(doPatch(METHODS + "/" + method + "/default", other, null), 403, ROLE_REFUSED);
            assertError(doDelete(METHODS + "/" + method, other), 403, ROLE_REFUSED);
        }
        // Bean Validation runs before the role check.
        assertValidation(doPost(METHODS, caregiver, card("Visa", "12")), "lastFour: must be exactly 4 digits");

        // Another patient's method answers 404, never 403, on PATCH and DELETE; so does an unknown id.
        assertError(doPatch(METHODS + "/" + method + "/default", otherPatient, null), 404, NOT_FOUND);
        assertError(doDelete(METHODS + "/" + method, otherPatient), 404, NOT_FOUND);
        assertError(doPatch(METHODS + "/999999/default", patient, null), 404, NOT_FOUND);
        assertError(doDelete(METHODS + "/999999", patient), 404, NOT_FOUND);
        assertError(doDelete(METHODS + "/abc", patient), 400, "Invalid value for parameter 'id'");
        assertEquals(0, myMethods(otherPatient).size());
        assertEquals(1, myMethods(patient).size(), "nothing was changed by the refused calls");
        assertEquals(1, count("SELECT COUNT(*) FROM payment_methods WHERE is_active AND NOT is_deleted"));

        // Endpoint 5: the patient's own id works, another patient's id is refused.
        assertEquals(1, expect(doGet(METHODS + "/patient/" + patient.getSystemId(), patient), 200).size());
        assertError(doGet(METHODS + "/patient/" + patient.getSystemId(), otherPatient), 403,
                "Patients can only access their own data");
        assertValidation(doGet(METHODS + "/patient/thomas", patient), "patientSystemId: must be a valid system id");
    }

    @Test
    void caregiverReadsMethodsOnlyWithAnActiveGrantThatAllowsManagingBilling() throws Exception {
        save(patient, card("Visa", "4242"));
        save(patient, mpesa("+254712345678"));
        String url = METHODS + "/patient/" + patient.getSystemId();

        assertError(doGet(url, caregiver), 403, NO_GRANT);

        grantAccess(caregiver, patient, access -> access.setCanViewBilling(true));
        assertError(doGet(url, caregiver), 403, NO_MANAGE);

        grantAccess(caregiver, patient, access -> access.setCanManageBilling(true));
        JsonNode list = expect(doGet(url, caregiver), 200);
        assertEquals(2, list.size());
        assertEquals(1, defaultIds(list).size());
        assertFalse(doGet(url, caregiver).getResponse().getContentAsString().contains("712345678"));

        grantAccess(caregiver, patient, access -> {
            access.setCanManageBilling(true);
            access.setAccessLevel("VIEW_ONLY");
        });
        assertError(doGet(url, caregiver), 403, NO_MANAGE);

        grantAccess(caregiver, patient, access -> access.setAccessLevel("FULL_ACCESS"));
        assertEquals(2, expect(doGet(url, caregiver), 200).size());

        grantAccess(caregiver, patient, access -> {
            access.setCanManageBilling(true);
            access.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        });
        assertError(doGet(url, caregiver), 403, NO_GRANT);

        grantAccess(caregiver, patient, access -> {
            access.setCanManageBilling(true);
            access.setIsActive(false);
        });
        assertError(doGet(url, caregiver), 403, NO_GRANT);

        // A grant for one patient opens nothing of another.
        grantAccess(caregiver, patient, access -> access.setCanManageBilling(true));
        assertError(doGet(METHODS + "/patient/" + otherPatient.getSystemId(), caregiver), 403, NO_GRANT);

        // Doctors never see payment methods, with or without a care relationship.
        assertError(doGet(url, doctor), 403, ROLE_REFUSED);
        assertError(doGet(url, otherDoctor), 403, ROLE_REFUSED);

        // Administrators read any patient; an id without methods (or without a user) is an empty list.
        assertEquals(2, expect(doGet(url, admin), 200).size());
        assertEquals(0, expect(doGet(METHODS + "/patient/PAT-99999", admin), 200).size());

        // Denials are audited by the authorization service.
        assertTrue(count("SELECT COUNT(*) FROM audit_log WHERE action = 'ACCESS_DENIED' AND patient_system_id = ?",
                patient.getSystemId()) >= 4);
    }

    // ==================== DEFAULT SWITCHING ====================

    @Test
    void exactlyOneDefaultThroughSwitchingAndDeletion() throws Exception {
        Integer a = save(patient, card("Visa", "1111"));
        Integer b = save(patient, card("Visa", "2222"));
        assertEquals(List.of(a), defaultIds(myMethods(patient)));

        Integer c = save(patient, withDefault(card("Visa", "3333")));
        assertEquals(List.of(c), defaultIds(myMethods(patient)), "makeDefault moves the flag");

        JsonNode switched = expect(doPatch(METHODS + "/" + b + "/default", patient, null), 200);
        assertEquals(b, switched.path("methodId").asInt());
        assertTrue(switched.path("isDefault").asBoolean());
        assertEquals(List.of(b), defaultIds(myMethods(patient)));
        assertEquals(1, count("SELECT COUNT(*) FROM payment_methods WHERE system_id = ? AND is_default",
                patient.getSystemId()));

        // Same state: no-op, 200, still the only default; an empty JSON body is accepted.
        JsonNode again = expect(doPatch(METHODS + "/" + b + "/default", patient, Map.of()), 200);
        assertTrue(again.path("isDefault").asBoolean());
        assertEquals(List.of(b), defaultIds(myMethods(patient)));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'UPDATE' "
                + "AND resource_type = 'PAYMENT_METHOD' AND resource_id = ?", b));

        // Deleting a non-default method keeps the default.
        expect(doDelete(METHODS + "/" + a, patient), 204);
        assertEquals(List.of(b), defaultIds(myMethods(patient)));

        // Deleting the default promotes the newest remaining method.
        Integer d = save(patient, card("Visa", "4444"));
        expect(doDelete(METHODS + "/" + b, patient), 204);
        JsonNode remaining = myMethods(patient);
        assertEquals(2, remaining.size());
        assertEquals(List.of(d), defaultIds(remaining));
        assertEquals(d, remaining.get(0).path("methodId").asInt());

        // Soft delete: the row stays, inactive and deleted; a second delete is 404, so is PATCH on it.
        Map<String, Object> row = jdbc.queryForMap("SELECT is_active, is_deleted, is_default FROM payment_methods "
                + "WHERE method_id = ?", b);
        assertEquals(false, row.get("is_active"));
        assertEquals(true, row.get("is_deleted"));
        assertEquals(false, row.get("is_default"));
        assertError(doDelete(METHODS + "/" + b, patient), 404, NOT_FOUND);
        assertError(doPatch(METHODS + "/" + b + "/default", patient, null), 404, NOT_FOUND);
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'DELETE' "
                + "AND resource_type = 'PAYMENT_METHOD' AND resource_id = ?", b));

        expect(doDelete(METHODS + "/" + c, patient), 204);
        expect(doDelete(METHODS + "/" + d, patient), 204);
        assertEquals(0, myMethods(patient).size());

        // The next method after an empty list is the default again.
        Integer e = save(patient, card("Visa", "5555"));
        assertEquals(List.of(e), defaultIds(myMethods(patient)));
    }

    // ==================== IDEMPOTENCY (payment methods) ====================

    @Test
    void replayedIdempotencyKeyReturnsTheStoredMethodWith200() throws Exception {
        Map<String, String> headers = Map.of("Idempotency-Key", "pm-key-0001");
        JsonNode first = expect(doPostWithHeaders(METHODS, patient, mpesa("+254712345678"), headers), 201);
        JsonNode replay = expect(doPostWithHeaders(METHODS, patient, mpesa("+254712345678"), headers), 200);
        assertEquals(first.path("methodId").asInt(), replay.path("methodId").asInt());
        assertEquals(first.path("displayLabel").asText(), replay.path("displayLabel").asText());
        assertEquals(1, count("SELECT COUNT(*) FROM payment_methods WHERE system_id = ?", patient.getSystemId()));

        // Without a key the same instrument is the duplicate 409.
        assertError(doPost(METHODS, patient, mpesa("+254712345678")), 409, "This payment method is already saved");

        // Keys are per owner: the same key of another patient creates their own method.
        expect(doPostWithHeaders(METHODS, otherPatient, mpesa("+254712345678"), headers), 201);

        assertError(doPostWithHeaders(METHODS, patient, card("Visa", "4242"), Map.of("Idempotency-Key", "bad key!")),
                400, "Idempotency-Key must be 8 to 64 characters: letters, digits, '_' or '-'");
        assertEquals(1, count("SELECT COUNT(*) FROM payment_methods WHERE system_id = ?", patient.getSystemId()));
    }

    // ==================== MANUAL PAYMENTS ====================

    @Test
    void manualPaymentsPartiallyThenFullySettleAnInvoice() throws Exception {
        Integer invoiceId = invoice(patient, "SENT", "1500.00", "0.00");

        MvcResult cashResult = doPost(manualUrl(invoiceId), admin, manual("cash", "500.00", "  "));
        JsonNode cash = expect(cashResult, 201);
        assertNotNull(cash.path("paymentId"));
        assertEquals(invoiceId, cash.path("invoiceId").asInt());
        assertEquals("INV-2026-700001", cash.path("invoiceNo").asText());
        assertEquals(patient.getSystemId(), cash.path("patientSystemId").asText());
        assertEquals(patient.getFirstName() + " " + patient.getLastName(), cash.path("patientName").asText());
        assertEquals(new BigDecimal("500.00"), cash.path("amount").decimalValue());
        assertEquals("500.00", cash.path("amount").decimalValue().toPlainString());
        assertEquals("KES", cash.path("currency").asText());
        assertEquals("CASH", cash.path("method").asText());
        assertEquals("COMPLETED", cash.path("status").asText());
        assertEquals("MANUAL", cash.path("gateway").asText());
        assertTrue(cash.path("providerReference").isNull(), "a blank reference is stored as null");
        assertTrue(cash.path("transactionId").isNull());
        assertTrue(cash.path("paymentMethodId").isNull());
        assertEquals(admin.getSystemId(), cash.path("initiatedBySystemId").asText());
        assertTrue(cash.path("failureReason").isNull());
        assertEquals("0.00", cash.path("refundedAmount").decimalValue().toPlainString());
        assertTrue(cash.path("refundReason").isNull());
        assertFalse(cash.path("paidAt").isNull());
        assertFalse(cash.path("createdAt").isNull());
        assertFalse(cash.path("updatedAt").isNull());
        assertFalse(cashResult.getResponse().getContentAsString().contains("gatewayResponse"));
        assertFalse(cashResult.getResponse().getContentAsString().contains("payerPhone"));

        Map<String, Object> afterCash = invoiceRow(invoiceId);
        assertEquals("PARTIALLY_PAID", afterCash.get("status"));
        assertEquals(0, new BigDecimal("500.00").compareTo((BigDecimal) afterCash.get("amount_paid")));
        assertEquals(null, afterCash.get("paid_at"));

        JsonNode transfer = expect(doPost(manualUrl(invoiceId), admin,
                manual("BANK_TRANSFER", "1000", " FT26314XK9Q2 ")), 201);
        assertEquals("BANK_TRANSFER", transfer.path("method").asText());
        assertEquals("FT26314XK9Q2", transfer.path("providerReference").asText());
        assertEquals("1000.00", transfer.path("amount").decimalValue().toPlainString());
        Map<String, Object> afterTransfer = invoiceRow(invoiceId);
        assertEquals("PAID", afterTransfer.get("status"));
        assertEquals(0, new BigDecimal("1500.00").compareTo((BigDecimal) afterTransfer.get("amount_paid")));
        assertNotNull(afterTransfer.get("paid_at"));

        // A paid invoice takes no more payments.
        assertError(doPost(manualUrl(invoiceId), admin, manual("CASH", "1.00", null)), 409,
                "An invoice in status PAID cannot be paid");
        assertEquals(2, paymentsOf(invoiceId));

        // The invoice read through the API shows the result.
        JsonNode read = expect(doGet("/api/billing/invoices/" + invoiceId, patient), 200);
        assertEquals("PAID", read.path("status").asText());
        assertEquals("0.00", read.path("outstanding").decimalValue().toPlainString());

        // One COMPLETED event per recorded payment, actor = the administrator.
        List<PaymentRecordedEvent> recorded = recorded();
        assertEquals(2, recorded.size());
        PaymentRecordedEvent firstEvent = recorded.get(0);
        assertEquals(cash.path("paymentId").asInt(), firstEvent.paymentId());
        assertEquals(invoiceId, firstEvent.invoiceId());
        assertEquals(patient.getSystemId(), firstEvent.patientSystemId());
        assertEquals(admin.getSystemId(), firstEvent.initiatedBySystemId());
        assertEquals(0, new BigDecimal("500.00").compareTo(firstEvent.amount()));
        assertEquals("KES", firstEvent.currency());
        assertEquals("CASH", firstEvent.method());
        assertEquals(PaymentRecordedEvent.COMPLETED, firstEvent.status());
        assertEquals("BANK_TRANSFER", recorded.get(1).method());

        // Audit: CREATE/PAYMENT with the invoice's patient; the invoice facade's READ and UPDATE rows.
        assertEquals(2, count("SELECT COUNT(*) FROM audit_log WHERE action = 'CREATE' AND resource_type = 'PAYMENT' "
                + "AND patient_system_id = ?", patient.getSystemId()));
        assertTrue(count("SELECT COUNT(*) FROM audit_log WHERE action = 'UPDATE' AND resource_type = 'INVOICE' "
                + "AND resource_id = ?", invoiceId) >= 2);
    }

    @Test
    void anOverdueInvoiceBecomesPartiallyPaidAndTheExactBalanceSettlesIt() throws Exception {
        Integer overdue = invoice(otherPatient, "OVERDUE", "1000.00", "0.00");
        expect(doPost(manualUrl(overdue), admin, manual("CASH", "0.01", "RCPT-1")), 201);
        assertEquals("PARTIALLY_PAID", invoiceRow(overdue).get("status"));
        expect(doPost(manualUrl(overdue), admin, manual("CASH", "999.99", null)), 201);
        assertEquals("PAID", invoiceRow(overdue).get("status"));

        Integer partly = invoice(patient, "PARTIALLY_PAID", "800.00", "300.00");
        assertError(doPost(manualUrl(partly), admin, manual("CASH", "500.01", null)), 409,
                "Payment exceeds the outstanding balance");
        expect(doPost(manualUrl(partly), admin, manual("CASH", "500.00", null)), 201);
        assertEquals("PAID", invoiceRow(partly).get("status"));
    }

    @Test
    void manualPaymentRefusalsStoreNothingAndPublishNothing() throws Exception {
        Integer invoiceId = invoice(patient, "SENT", "1500.00", "0.00");
        String url = manualUrl(invoiceId);

        assertValidation(doPost(url, admin, manual(null, "10.00", null)), "method: must not be blank");
        assertValidation(doPost(url, admin, manual("CARD", "10.00", null)), "method: must be one of CASH, BANK_TRANSFER");
        assertValidation(doPost(url, admin, manual("INSURANCE", "10.00", null)),
                "method: must be one of CASH, BANK_TRANSFER");
        assertValidation(doPost(url, admin, manual("CASH", null, null)), "amount: must not be null");
        assertValidation(doPost(url, admin, manual("CASH", "0", null)), "amount: must be greater than 0");
        assertValidation(doPost(url, admin, manual("CASH", "-5.00", null)), "amount: must be greater than 0");
        assertValidation(doPost(url, admin, manual("CASH", "10.001", null)),
                "amount: must have at most 10 digits and 2 decimals");
        assertValidation(doPost(url, admin, manual("CASH", "10.00", "R".repeat(101))),
                "reference: size must be between 0 and 100");
        assertError(doPost(url, admin, manual("BANK_TRANSFER", "10.00", null)), 400,
                "reference is required for a bank transfer");
        assertError(doPost(url, admin, manual("BANK_TRANSFER", "10.00", "   ")), 400,
                "reference is required for a bank transfer");
        assertError(doPost(url, admin, manual("CASH", "1500.01", null)), 409, "Payment exceeds the outstanding balance");
        assertError(doPost(manualUrl(999999), admin, manual("CASH", "10.00", null)), 404, "Invoice not found");
        assertError(doPost("/api/admin/billing/invoices/abc/payments/manual", admin, manual("CASH", "10.00", null)), 400,
                "Invalid value for parameter 'id'");

        for (String status : List.of("DRAFT", "CANCELLED", "REFUNDED")) {
            Integer closed = invoice(patient, status, "1000.00", "0.00");
            assertError(doPost(manualUrl(closed), admin, manual("CASH", "10.00", null)), 409,
                    "An invoice in status " + status + " cannot be paid");
        }
        Integer paid = invoice(patient, "PAID", "1000.00", "1000.00");
        assertError(doPost(manualUrl(paid), admin, manual("CASH", "10.00", null)), 409,
                "An invoice in status PAID cannot be paid");

        assertEquals(0, count("SELECT COUNT(*) FROM payments"));
        assertEquals("SENT", invoiceRow(invoiceId).get("status"));
        assertTrue(recorded().isEmpty());
    }

    @Test
    void manualPaymentReplayAnswers200WithoutASecondPaymentAndTheKeyIsScopedToTheInvoice() throws Exception {
        Integer first = invoice(patient, "SENT", "1500.00", "0.00");
        Integer second = invoice(patient, "SENT", "1500.00", "0.00");
        Map<String, String> key = Map.of("Idempotency-Key", "manual-pay-0001");

        JsonNode created = expect(doPostWithHeaders(manualUrl(first), admin, manual("CASH", "600.00", null), key), 201);
        JsonNode replay = expect(doPostWithHeaders(manualUrl(first), admin, manual("CASH", "600.00", null), key), 200);
        assertEquals(created.path("paymentId").asInt(), replay.path("paymentId").asInt());
        assertEquals("600.00", replay.path("amount").decimalValue().toPlainString());
        assertEquals(1, paymentsOf(first));
        assertEquals(0, new BigDecimal("600.00").compareTo((BigDecimal) invoiceRow(first).get("amount_paid")));
        assertEquals(1, recorded().size(), "a replay publishes nothing");

        // The same key on another invoice is a new request.
        JsonNode other = expect(doPostWithHeaders(manualUrl(second), admin, manual("CASH", "600.00", null), key), 201);
        assertFalse(other.path("paymentId").asInt() == created.path("paymentId").asInt());
        assertEquals(1, paymentsOf(second));

        // A refused request stores no key: the corrected retry with the same key succeeds.
        Map<String, String> retryKey = Map.of("Idempotency-Key", "manual-pay-0002");
        assertError(doPostWithHeaders(manualUrl(first), admin, manual("CASH", "5000.00", null), retryKey), 409,
                "Payment exceeds the outstanding balance");
        expect(doPostWithHeaders(manualUrl(first), admin, manual("CASH", "900.00", null), retryKey), 201);
        assertEquals("PAID", invoiceRow(first).get("status"));

        assertError(doPostWithHeaders(manualUrl(first), admin, manual("CASH", "1.00", null),
                Map.of("Idempotency-Key", "short")), 400,
                "Idempotency-Key must be 8 to 64 characters: letters, digits, '_' or '-'");
    }

    @Test
    void concurrentManualPaymentsAreSerializedOnTheInvoiceAndNeverOverpayOrLoseAnUpdate() throws Exception {
        // Two part payments at the same moment: both are applied, none overwrites the other.
        Integer shared = invoice(patient, "SENT", "1000.00", "0.00");
        List<Integer> both = runConcurrently(shared, "400.00", "400.00");
        assertEquals(List.of(201, 201), both);
        assertEquals(0, new BigDecimal("800.00").compareTo((BigDecimal) invoiceRow(shared).get("amount_paid")));
        assertEquals("PARTIALLY_PAID", invoiceRow(shared).get("status"));
        assertEquals(2, paymentsOf(shared));

        // Two payments that together exceed the balance: one wins, the other is a 409 and leaves no payment row.
        Integer contested = invoice(patient, "SENT", "1000.00", "0.00");
        List<Integer> statuses = runConcurrently(contested, "600.00", "600.00");
        assertEquals(List.of(201, 409), statuses.stream().sorted().toList());
        assertEquals(0, new BigDecimal("600.00").compareTo((BigDecimal) invoiceRow(contested).get("amount_paid")));
        assertEquals(1, paymentsOf(contested));
    }

    private List<Integer> runConcurrently(Integer invoiceId, String firstAmount, String secondAmount) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (String amount : List.of(firstAmount, secondAmount)) {
                Callable<Integer> task = () -> {
                    start.await();
                    return doPost(manualUrl(invoiceId), admin, manual("CASH", amount, null)).getResponse().getStatus();
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    // ==================== ADMIN ROUTES: ROLE GATE ====================

    @Test
    void nonAdministratorsGet403OnTheAdminRoutes() throws Exception {
        Integer invoiceId = invoice(patient, "SENT", "1500.00", "0.00");
        grantAccess(caregiver, patient, access -> access.setAccessLevel("FULL_ACCESS"));
        for (User other : List.of(patient, doctor, caregiver, otherPatient)) {
            assertError(doGet(ADMIN_PAYMENTS, other), 403, URL_GATE);
            assertError(doPost(manualUrl(invoiceId), other, manual("CASH", "10.00", null)), 403, URL_GATE);
        }
        assertEquals(0, count("SELECT COUNT(*) FROM payments"));
        assertEquals(401, mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get(ADMIN_PAYMENTS)).andReturn().getResponse().getStatus());
    }

    // ==================== ADMIN LIST ====================

    @Test
    void adminListPagesFiltersAndFillsLookupsNewestFirst() throws Exception {
        Integer invoiceA = invoice(patient, "SENT", "3000.00", "0.00");
        Integer invoiceB = invoice(otherPatient, "SENT", "2000.00", "0.00");
        Integer savedMethod = save(otherPatient, mpesa("+254700111222"));

        // An older pending gateway payment of the other patient, with a saved method that is later removed.
        LocalDateTime earlier = LocalDateTime.now().minusHours(2);
        Integer pending = jdbc.queryForObject("INSERT INTO payments (patient_system_id, invoice_id, payment_method_id, "
                        + "amount, currency, status, method, gateway, provider_reference, initiated_by_system_id, "
                        + "refunded_amount, payer_phone, created_at, updated_at) VALUES (?, ?, ?, 2000.00, 'KES', "
                        + "'PENDING', 'MPESA', 'local', 'local_7f3a9c', ?, 0.00, '+254700111222', ?, ?) "
                        + "RETURNING payment_id",
                Integer.class, otherPatient.getSystemId(), invoiceB, savedMethod, otherPatient.getSystemId(),
                Timestamp.valueOf(earlier), Timestamp.valueOf(earlier));
        expect(doDelete(METHODS + "/" + savedMethod, otherPatient), 204);

        Integer p1 = expect(doPost(manualUrl(invoiceA), admin, manual("CASH", "100.00", null)), 201)
                .path("paymentId").asInt();
        Integer p2 = expect(doPost(manualUrl(invoiceA), admin, manual("BANK_TRANSFER", "200.00", "FT-1")), 201)
                .path("paymentId").asInt();
        Integer p3 = expect(doPost(manualUrl(invoiceA), admin, manual("CASH", "300.00", null)), 201)
                .path("paymentId").asInt();

        JsonNode page0 = expect(doGet(ADMIN_PAYMENTS + "?size=2", admin), 200);
        assertEquals(0, page0.path("page").asInt());
        assertEquals(2, page0.path("size").asInt());
        assertEquals(4, page0.path("totalElements").asInt());
        assertEquals(2, page0.path("totalPages").asInt());
        assertEquals(p3, page0.path("content").get(0).path("paymentId").asInt());
        assertEquals(p2, page0.path("content").get(1).path("paymentId").asInt());

        JsonNode page1 = expect(doGet(ADMIN_PAYMENTS + "?size=2&page=1", admin), 200);
        assertEquals(p1, page1.path("content").get(0).path("paymentId").asInt());
        JsonNode oldest = page1.path("content").get(1);
        assertEquals(pending, oldest.path("paymentId").asInt());
        assertEquals("PENDING", oldest.path("status").asText());
        assertEquals("local", oldest.path("gateway").asText());
        assertEquals("local_7f3a9c", oldest.path("providerReference").asText());
        assertEquals(savedMethod, oldest.path("paymentMethodId").asInt());
        assertEquals(otherPatient.getSystemId(), oldest.path("initiatedBySystemId").asText());
        assertEquals(otherPatient.getFirstName() + " " + otherPatient.getLastName(), oldest.path("patientName").asText());
        assertEquals("INV-2026-700002", oldest.path("invoiceNo").asText());
        assertTrue(oldest.path("paidAt").isNull());
        assertFalse(expect(doGet(ADMIN_PAYMENTS + "?size=100", admin), 200).toString().contains("+254700111222"),
                "the payer's phone is never returned");

        // Clamping: size above 100 becomes 100, below 1 becomes 1, a negative page becomes 0.
        assertEquals(100, expect(doGet(ADMIN_PAYMENTS + "?size=500", admin), 200).path("size").asInt());
        assertEquals(1, expect(doGet(ADMIN_PAYMENTS + "?size=0", admin), 200).path("size").asInt());
        assertEquals(0, expect(doGet(ADMIN_PAYMENTS + "?page=-3", admin), 200).path("page").asInt());
        assertError(doGet(ADMIN_PAYMENTS + "?page=abc", admin), 400, "Invalid value for parameter 'page'");

        // Filters: one value or a comma-separated list, case-insensitive; empty means no filter.
        JsonNode pendingOnly = expect(doGet(ADMIN_PAYMENTS + "?status=pending", admin), 200);
        assertEquals(1, pendingOnly.path("totalElements").asInt());
        JsonNode completedOrRefunded = expect(doGet(ADMIN_PAYMENTS + "?status=COMPLETED, refunded", admin), 200);
        assertEquals(3, completedOrRefunded.path("totalElements").asInt());
        assertEquals(1, expect(doGet(ADMIN_PAYMENTS + "?method=bank_transfer", admin), 200)
                .path("totalElements").asInt());
        assertEquals(3, expect(doGet(ADMIN_PAYMENTS + "?method=CASH,BANK_TRANSFER", admin), 200)
                .path("totalElements").asInt());
        assertEquals(4, expect(doGet(ADMIN_PAYMENTS + "?method=&status=", admin), 200).path("totalElements").asInt());
        JsonNode mine = expect(doGet(ADMIN_PAYMENTS + "?patientSystemId=" + patient.getSystemId(), admin), 200);
        assertEquals(3, mine.path("totalElements").asInt());
        mine.path("content").forEach(row -> assertEquals(patient.getSystemId(), row.path("patientSystemId").asText()));
        assertEquals(0, expect(doGet(ADMIN_PAYMENTS + "?patientSystemId=PAT-99999", admin), 200)
                .path("totalElements").asInt());
        assertEquals(1, expect(doGet(ADMIN_PAYMENTS + "?patientSystemId=" + otherPatient.getSystemId()
                + "&status=PENDING&method=MPESA", admin), 200).path("totalElements").asInt());

        assertError(doGet(ADMIN_PAYMENTS + "?status=BOGUS", admin), 400, "Unknown payment status 'BOGUS'");
        assertError(doGet(ADMIN_PAYMENTS + "?method=COMPLETED,Crypto", admin), 400, "Unknown payment method 'COMPLETED'");
        assertError(doGet(ADMIN_PAYMENTS + "?method=CASH,Crypto", admin), 400, "Unknown payment method 'Crypto'");
        assertValidation(doGet(ADMIN_PAYMENTS + "?patientSystemId=thomas", admin),
                "patientSystemId: must be a valid system id");

        // Audit: one READ/PAYMENT per distinct patient on the page.
        long before = count("SELECT COUNT(*) FROM audit_log WHERE action = 'READ' AND resource_type = 'PAYMENT'");
        expect(doGet(ADMIN_PAYMENTS + "?size=100", admin), 200);
        assertEquals(before + 2, count("SELECT COUNT(*) FROM audit_log WHERE action = 'READ' "
                + "AND resource_type = 'PAYMENT'"));
    }

    @Test
    void theRowOfAPaymentWithASavedMethodCarriesTheMaskedLabel() throws Exception {
        Integer invoiceId = invoice(patient, "SENT", "1000.00", "0.00");
        Integer method = save(patient, card("Visa", "4242"));
        jdbc.update("INSERT INTO payments (patient_system_id, invoice_id, payment_method_id, amount, currency, status, "
                        + "method, gateway, transaction_id, initiated_by_system_id, refunded_amount, refund_reason, paid_at, "
                        + "created_at, updated_at) VALUES (?, ?, ?, 1000.00, 'KES', 'COMPLETED', 'CARD', 'local', 'txn-1', "
                        + "?, 250.00, 'Duplicate charge', now(), now(), now())",
                patient.getSystemId(), invoiceId, method, caregiver.getSystemId());
        JsonNode row = expect(doGet(ADMIN_PAYMENTS, admin), 200).path("content").get(0);
        assertEquals("Visa" + DOTS + "4242", row.path("paymentMethodLabel").asText());
        assertEquals("txn-1", row.path("transactionId").asText());
        assertEquals("250.00", row.path("refundedAmount").decimalValue().toPlainString());
        assertEquals("750.00", row.path("refundableAmount").decimalValue().toPlainString());
        assertEquals("Duplicate charge", row.path("refundReason").asText());
        assertEquals(caregiver.getSystemId(), row.path("initiatedBySystemId").asText());
        assertEquals(caregiver.getFirstName() + " " + caregiver.getLastName(), row.path("initiatedByName").asText());
    }
}
