package com.carenexus.api.releasegates;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.billing.claim.ClaimFixtures;
import com.carenexus.api.common.event.InsuranceClaimStatusChangedEvent;
import com.carenexus.api.common.event.InvoiceStatusChangedEvent;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Release gates of the insurance claims slice (be-billing-claims, api/be-billing-claims.md): submission rules and
 * validation, who may submit, read and appeal (patient, other patient, issuing doctor, caregiver with and without
 * each billing flag, VIEW_ONLY, expired grant, admin), the claim state machine, settlement of approved amounts onto
 * the invoice (full and partial, cumulative after an appeal), one open claim per invoice, the administrator's queue
 * and void, the URL gate of the admin routes, Idempotency-Key replay, concurrency, events, notifications and audit
 * rows. Runs with committed transactions (CommittedIntegrationTest).
 */
@RecordApplicationEvents
class BillingClaimsReleaseGateTest extends CommittedIntegrationTest {

    private static final String CLAIMS = "/api/billing/claims";
    private static final String ADMIN_CLAIMS = "/api/admin/billing/claims";
    private static final String URL_GATE = "You don't have permission to access this resource";
    private static final String ROLE_REFUSED = "Your role is not authorized to perform this action";
    private static final String OTHER_PATIENT = "Patients can only access their own data";
    private static final String NO_GRANT = "You don't have active proxy access to this patient";
    private static final String NO_VIEW = "Your proxy access for this patient does not allow viewing billing";
    private static final String NO_MANAGE =
            "Your proxy access for this patient does not allow managing billing and payments";
    private static final String OPEN_CLAIM = "This invoice already has an open insurance claim";

    @Autowired
    private ApplicationEvents events;

    private ClaimFixtures fixtures;

    /** Reads money exactly as serialized (2320.00 stays 2320.00), so the two-decimal wire format is asserted. */
    private ObjectMapper exactMapper;

    @BeforeEach
    void setUpFixtures() {
        fixtures = new ClaimFixtures(jdbc);
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

    // ==================== HELPERS ====================

    private static String submitUrl(Integer invoiceId) {
        return "/api/billing/invoices/" + invoiceId + "/claims";
    }

    private static Map<String, Object> claimBody(String amount, Integer... documentIds) {
        Map<String, Object> body = new HashMap<>();
        body.put("insuranceProvider", "  Jubilee Health ");
        body.put("policyNumber", "JH-88213-K");
        body.put("memberNumber", "0045712");
        if (amount != null) {
            body.put("claimAmount", new BigDecimal(amount));
        }
        if (documentIds.length > 0) {
            body.put("documentIds", Arrays.asList(documentIds));
        }
        return body;
    }

    private static Map<String, Object> decision(String status, String approvedAmount, String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("status", status);
        if (approvedAmount != null) {
            body.put("approvedAmount", new BigDecimal(approvedAmount));
        }
        if (reason != null) {
            body.put("rejectionReason", reason);
        }
        return body;
    }

    private Integer sentInvoice(String total) {
        return fixtures.invoice(patient, doctor, "SENT", total, "0.00");
    }

    private JsonNode submitOk(User as, Integer invoiceId, Map<String, Object> body) throws Exception {
        MvcResult result = doPost(submitUrl(invoiceId), as, body);
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result);
    }

    private MvcResult adjudicate(Integer claimId, Map<String, Object> body) throws Exception {
        return doPatch(ADMIN_CLAIMS + "/" + claimId, admin, body);
    }

    private JsonNode adjudicateOk(Integer claimId, Map<String, Object> body) throws Exception {
        MvcResult result = adjudicate(claimId, body);
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result);
    }

    private JsonNode appealOk(User as, Integer claimId, String reason) throws Exception {
        MvcResult result = doPost(CLAIMS + "/" + claimId + "/appeal", as, Map.of("reason", reason));
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result);
    }

    /** Submits for the patient and moves the claim to PENDING; returns the claim id. */
    private Integer pendingClaim(Integer invoiceId, String amount) throws Exception {
        Integer claimId = submitOk(patient, invoiceId, claimBody(amount)).get("claimId").asInt();
        assertEquals("PENDING", adjudicateOk(claimId, decision("PENDING", null, null)).get("status").asText());
        return claimId;
    }

    private void assertError(MvcResult result, int status, String message) throws Exception {
        assertEquals(status, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(message, message(result));
    }

    private static void assertMoney(String expected, JsonNode value) {
        assertEquals(new BigDecimal(expected), value.decimalValue(), "money " + value);
    }

    private long auditRows(String action, String resourceType, Integer resourceId) {
        Long count = resourceId == null
                ? jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ? AND resource_type = ? "
                + "AND resource_id IS NULL AND status = 'SUCCESS'", Long.class, action, resourceType)
                : jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ? AND resource_type = ? "
                + "AND resource_id = ? AND status = 'SUCCESS'", Long.class, action, resourceType, resourceId);
        return count == null ? 0 : count;
    }

    private int payments(Integer invoiceId) {
        return fixtures.count("SELECT COUNT(*) FROM payments WHERE invoice_id = ?", invoiceId);
    }

    private List<InsuranceClaimStatusChangedEvent> claimEvents(int claimId) {
        return events.stream(InsuranceClaimStatusChangedEvent.class)
                .filter(e -> e.claimId() == claimId)
                .toList();
    }

    // ==================== SUBMIT ====================

    @Test
    void patientSubmitsAClaim_withDefaultsDocumentsEventNotificationAndAudit() throws Exception {
        User secondAdmin = createAndSaveUser(8, "ADM-00002", "second.admin@example.com", "ADMIN");
        Integer invoiceId = sentInvoice("2320.00");
        Integer card = fixtures.document(patient, "Insurance card");
        Integer letter = fixtures.document(patient, "Pre-authorisation letter");

        JsonNode claim = submitOk(patient, invoiceId, claimBody(null, card, letter, card));

        int claimId = claim.get("claimId").asInt();
        String claimNo = claim.get("claimNo").asText();
        assertTrue(claimNo.matches("^CLM-\\d{4}-\\d{6,}$"), claimNo);
        assertEquals("MANUAL-" + claimNo, claim.get("providerReference").asText());
        assertEquals(invoiceId, claim.get("invoiceId").asInt());
        assertTrue(claim.get("invoiceNo").asText().startsWith("INV-2099-"));
        assertEquals(patient.getSystemId(), claim.get("patientSystemId").asText());
        assertFalse(claim.get("patientName").isNull());
        assertEquals("Jubilee Health", claim.get("insuranceProvider").asText());
        assertEquals("JH-88213-K", claim.get("policyNumber").asText());
        assertEquals("0045712", claim.get("memberNumber").asText());
        assertMoney("2320.00", claim.get("claimAmount"));
        assertTrue(claim.get("approvedAmount").isNull());
        assertEquals("KES", claim.get("currency").asText());
        assertMoney("2320.00", claim.get("invoiceOutstanding"));
        assertEquals("SUBMITTED", claim.get("status").asText());
        assertTrue(claim.get("rejectionReason").isNull());
        assertTrue(claim.get("appealReason").isNull());
        assertEquals(2, claim.get("documentIds").size());
        assertEquals(card, claim.get("documentIds").get(0).asInt());
        assertEquals(letter, claim.get("documentIds").get(1).asInt());
        assertEquals("Insurance card", claim.get("documents").get(0).get("title").asText());
        assertEquals("Pre-authorisation letter", claim.get("documents").get(1).get("title").asText());
        assertTrue(claim.get("paymentId").isNull());
        assertEquals(patient.getSystemId(), claim.get("submittedBySystemId").asText());
        assertTrue(claim.get("reviewedBySystemId").isNull());
        assertFalse(claim.get("submittedAt").isNull());
        assertTrue(claim.get("resolvedAt").isNull());
        assertTrue(claim.has("updatedAt"));

        // Event with previousStatus null, CREATE audit row, both administrators notified with the claims-tab link.
        List<InsuranceClaimStatusChangedEvent> published = claimEvents(claimId);
        assertEquals(1, published.size());
        assertEquals(null, published.get(0).previousStatus());
        assertEquals("SUBMITTED", published.get(0).status());
        assertEquals(invoiceId, published.get(0).invoiceId());
        assertEquals(1, auditRows("CREATE", "INSURANCE_CLAIM", claimId));
        for (User recipient : List.of(admin, secondAdmin)) {
            assertEquals(1, fixtures.count("SELECT COUNT(*) FROM notifications WHERE system_id = ? AND type = 'SYSTEM' "
                            + "AND title = 'New insurance claim' AND dedupe_key = ? AND action_url = ? "
                            + "AND message = ?", recipient.getSystemId(), "CLAIM_SUBMITTED:" + claimId,
                    "/dashboard/admin?section=billing&id=" + claimId + "&tab=claims",
                    "Claim " + claimNo + " for invoice " + claim.get("invoiceNo").asText()
                            + " is waiting for review."));
        }
        assertEquals(0, fixtures.count("SELECT COUNT(*) FROM notifications WHERE system_id = ?",
                patient.getSystemId()));

        // A document deleted later stays in documentIds but leaves the documents array.
        fixtures.softDeleteDocument(letter);
        JsonNode reread = json(doGet(CLAIMS + "/" + claimId, patient));
        assertEquals(2, reread.get("documentIds").size());
        assertEquals(1, reread.get("documents").size());
        assertEquals(card, reread.get("documents").get(0).get("documentId").asInt());
    }

    @Test
    void submitValidation_andBusinessRules() throws Exception {
        Integer invoiceId = sentInvoice("2000.00");

        Map<String, Object> noInsurer = claimBody(null);
        noInsurer.remove("insuranceProvider");
        MvcResult result = doPost(submitUrl(invoiceId), patient, noInsurer);
        assertError(result, 400, "insuranceProvider: must not be blank");
        assertEquals("VALIDATION_FAILED", json(result).get("code").asText());

        Map<String, Object> blankPolicy = claimBody(null);
        blankPolicy.put("policyNumber", "   ");
        assertError(doPost(submitUrl(invoiceId), patient, blankPolicy), 400, "policyNumber: must not be blank");

        Map<String, Object> longPolicy = claimBody(null);
        longPolicy.put("policyNumber", "P".repeat(101));
        assertError(doPost(submitUrl(invoiceId), patient, longPolicy), 400,
                "policyNumber: size must be between 0 and 100");

        assertError(doPost(submitUrl(invoiceId), patient, claimBody("0")), 400,
                "claimAmount: must be greater than 0");
        assertError(doPost(submitUrl(invoiceId), patient, claimBody("10.123")), 400,
                "claimAmount: must have at most 10 digits and 2 decimals");

        Integer[] eleven = IntStream.rangeClosed(1, 11).boxed().toArray(Integer[]::new);
        assertError(doPost(submitUrl(invoiceId), patient, claimBody(null, eleven)), 400,
                "documentIds: size must be between 0 and 10");
        Map<String, Object> negativeDocument = claimBody(null);
        negativeDocument.put("documentIds", List.of(-4));
        assertError(doPost(submitUrl(invoiceId), patient, negativeDocument), 400,
                "documentIds[0]: must be greater than 0");

        assertError(doPostWithHeaders(submitUrl(invoiceId), patient, claimBody(null), Map.of("Idempotency-Key", "short")),
                400, "Idempotency-Key must be 8 to 64 characters: letters, digits, '_' or '-'");

        // Amount above the balance (409), documents of another patient, deleted or unknown (400).
        assertError(doPost(submitUrl(invoiceId), patient, claimBody("2000.01")), 409,
                "Claim amount exceeds the outstanding balance");
        Integer foreign = fixtures.document(otherPatient, "Someone else's card");
        assertError(doPost(submitUrl(invoiceId), patient, claimBody(null, foreign)), 400,
                "One or more documents do not belong to this patient");
        Integer deleted = fixtures.document(patient, "Old card");
        fixtures.softDeleteDocument(deleted);
        assertError(doPost(submitUrl(invoiceId), patient, claimBody(null, deleted)), 400,
                "One or more documents do not belong to this patient");
        assertError(doPost(submitUrl(invoiceId), patient, claimBody(null, 987654)), 400,
                "One or more documents do not belong to this patient");

        // Unknown invoice; a DRAFT is invisible to the patient but a status conflict for the admin.
        assertError(doPost(submitUrl(999999), patient, claimBody(null)), 404, "Invoice not found");
        Integer draft = fixtures.invoice(patient, doctor, "DRAFT", "500.00", "0.00");
        assertError(doPost(submitUrl(draft), patient, claimBody(null)), 404, "Invoice not found");
        assertError(doPost(submitUrl(draft), admin, claimBody(null)), 409,
                "An insurance claim cannot be submitted for an invoice in status DRAFT");
        Integer paid = fixtures.invoice(patient, doctor, "PAID", "500.00", "500.00");
        assertError(doPost(submitUrl(paid), patient, claimBody(null)), 409,
                "An insurance claim cannot be submitted for an invoice in status PAID");

        assertEquals(0, fixtures.count("SELECT COUNT(*) FROM insurance_claims"));

        // A part-paid invoice: the default amount is what is left.
        Integer partlyPaid = fixtures.invoice(patient, doctor, "PARTIALLY_PAID", "1000.00", "400.00");
        JsonNode claim = submitOk(patient, partlyPaid, claimBody(null));
        assertMoney("600.00", claim.get("claimAmount"));
        assertMoney("600.00", claim.get("invoiceOutstanding"));
        Map<String, Object> blankMember = claimBody("100.00");
        blankMember.put("memberNumber", "  ");
        Integer overdue = fixtures.invoice(patient, doctor, "OVERDUE", "300.00", "0.00");
        JsonNode overdueClaim = submitOk(patient, overdue, blankMember);
        assertTrue(overdueClaim.get("memberNumber").isNull());
        assertMoney("100.00", overdueClaim.get("claimAmount"));
        assertEquals(0, overdueClaim.get("documents").size());
    }

    @Test
    void duplicateOpenClaim_is409_andANewClaimIsAllowedAfterADecision() throws Exception {
        Integer invoiceId = sentInvoice("1500.00");
        Integer first = submitOk(patient, invoiceId, claimBody(null)).get("claimId").asInt();

        assertError(doPost(submitUrl(invoiceId), patient, claimBody("100.00")), 409, OPEN_CLAIM);
        assertError(doPost(submitUrl(invoiceId), admin, claimBody("100.00")), 409, OPEN_CLAIM);

        adjudicateOk(first, decision("PENDING", null, null));
        assertError(doPost(submitUrl(invoiceId), patient, claimBody(null)), 409, OPEN_CLAIM);
        JsonNode rejected = adjudicateOk(first, decision("REJECTED", null, "The policy had lapsed"));
        assertEquals("REJECTED", rejected.get("status").asText());

        Integer second = submitOk(patient, invoiceId, claimBody(null)).get("claimId").asInt();
        assertNotEquals(first, second);

        // Appealing the old decision while the newer claim is open is refused.
        MvcResult appeal = doPost(CLAIMS + "/" + first + "/appeal", patient, Map.of("reason", "Please look again"));
        assertError(appeal, 409, OPEN_CLAIM);
        assertEquals("REJECTED", json(doGet(CLAIMS + "/" + first, admin)).get("status").asText());
    }

    @Test
    void concurrentSubmissionsForOneInvoice_giveOneClaimAndOneConflict() throws Exception {
        Integer invoiceId = sentInvoice("900.00");
        List<Integer> statuses = runConcurrently(2, () -> doPost(submitUrl(invoiceId), patient, claimBody(null)));
        statuses.sort(Integer::compareTo);
        assertEquals(List.of(201, 409), statuses);
        assertEquals(1, fixtures.count("SELECT COUNT(*) FROM insurance_claims WHERE invoice_id = ?", invoiceId));
    }

    @Test
    void idempotentReplay_returnsTheStoredClaimWith200() throws Exception {
        Integer invoiceId = sentInvoice("750.00");
        Map<String, String> key = Map.of("Idempotency-Key", "claim-key-" + clientId().substring(0, 8));

        MvcResult first = doPostWithHeaders(submitUrl(invoiceId), patient, claimBody(null), key);
        assertEquals(201, first.getResponse().getStatus(), first.getResponse().getContentAsString());
        MvcResult replay = doPostWithHeaders(submitUrl(invoiceId), patient, claimBody(null), key);
        assertEquals(200, replay.getResponse().getStatus(), replay.getResponse().getContentAsString());

        assertEquals(json(first).get("claimId").asInt(), json(replay).get("claimId").asInt());
        assertEquals(json(first).get("claimNo").asText(), json(replay).get("claimNo").asText());
        assertEquals(1, fixtures.count("SELECT COUNT(*) FROM insurance_claims WHERE invoice_id = ?", invoiceId));
        assertEquals(1, auditRows("CREATE", "INSURANCE_CLAIM", json(first).get("claimId").asInt()));
        assertEquals(1, claimEvents(json(first).get("claimId").asInt()).size());

        // A new key is a new request: the open-claim rule refuses it.
        assertError(doPostWithHeaders(submitUrl(invoiceId), patient, claimBody(null),
                Map.of("Idempotency-Key", "claim-key-" + clientId().substring(0, 8))), 409, OPEN_CLAIM);
    }

    // ==================== OWNERSHIP AND CAREGIVER FLAGS ====================

    @Test
    void doctorsAndOtherPatients_areRefusedOnEveryClaimEndpoint() throws Exception {
        Integer invoiceId = sentInvoice("1200.00");
        Integer claimId = submitOk(patient, invoiceId, claimBody(null)).get("claimId").asInt();
        adjudicateOk(claimId, decision("PENDING", null, null));
        adjudicateOk(claimId, decision("REJECTED", null, "Not covered"));

        // The issuing doctor and an unrelated doctor: role refused before anything is loaded (also for unknown ids).
        for (User doc : List.of(doctor, otherDoctor)) {
            assertError(doPost(submitUrl(invoiceId), doc, claimBody(null)), 403, ROLE_REFUSED);
            assertError(doGet(CLAIMS + "/" + claimId, doc), 403, ROLE_REFUSED);
            assertError(doGet(CLAIMS + "/999999", doc), 403, ROLE_REFUSED);
            assertError(doGet(CLAIMS + "?patientSystemId=" + patient.getSystemId(), doc), 403, ROLE_REFUSED);
            assertError(doPost(CLAIMS + "/" + claimId + "/appeal", doc, Map.of("reason", "x")), 403, ROLE_REFUSED);
        }

        assertError(doPost(submitUrl(invoiceId), otherPatient, claimBody(null)), 403, OTHER_PATIENT);
        assertError(doGet(CLAIMS + "/" + claimId, otherPatient), 403, OTHER_PATIENT);
        assertError(doGet(CLAIMS + "?patientSystemId=" + patient.getSystemId(), otherPatient), 403, OTHER_PATIENT);
        assertError(doPost(CLAIMS + "/" + claimId + "/appeal", otherPatient, Map.of("reason", "x")), 403,
                OTHER_PATIENT);
        assertEquals(0, json(doGet(CLAIMS, otherPatient)).get("totalElements").asInt());

        // Unknown claim: 404 for the patient.
        assertError(doGet(CLAIMS + "/999999", patient), 404, "Claim not found");
        assertError(doPost(CLAIMS + "/999999/appeal", patient, Map.of("reason", "x")), 404, "Claim not found");

        assertEquals("REJECTED", json(doGet(CLAIMS + "/" + claimId, patient)).get("status").asText());
    }

    @Test
    void caregiverFlags_viewAndManageAreSeparate_andExpiryOrViewOnlyRefuse() throws Exception {
        Integer invoiceId = sentInvoice("1000.00");
        String listUrl = CLAIMS + "?patientSystemId=" + patient.getSystemId();

        // No grant at all.
        assertError(doPost(submitUrl(invoiceId), caregiver, claimBody(null)), 403, NO_GRANT);
        assertError(doGet(listUrl, caregiver), 403, NO_GRANT);

        // View flag only: may read, may not submit.
        grantAccess(caregiver, patient, a -> a.setCanViewBilling(true));
        assertError(doPost(submitUrl(invoiceId), caregiver, claimBody(null)), 403, NO_MANAGE);
        assertEquals(0, json(doGet(listUrl, caregiver)).get("totalElements").asInt());

        // Manage flag but VIEW_ONLY: writes refused.
        grantAccess(caregiver, patient, a -> {
            a.setAccessLevel("VIEW_ONLY");
            a.setCanViewBilling(true);
            a.setCanManageBilling(true);
        });
        assertError(doPost(submitUrl(invoiceId), caregiver, claimBody(null)), 403, NO_MANAGE);

        // Manage flag only: the write answers with the claim, the reads are refused.
        grantAccess(caregiver, patient, a -> a.setCanManageBilling(true));
        JsonNode claim = submitOk(caregiver, invoiceId, claimBody("800.00"));
        int claimId = claim.get("claimId").asInt();
        assertEquals(caregiver.getSystemId(), claim.get("submittedBySystemId").asText());
        assertError(doGet(CLAIMS + "/" + claimId, caregiver), 403, NO_VIEW);
        assertError(doGet(listUrl, caregiver), 403, NO_VIEW);
        assertError(doGet(CLAIMS, caregiver), 400, "patientSystemId is required");

        adjudicateOk(claimId, decision("PENDING", null, null));
        adjudicateOk(claimId, decision("REJECTED", null, "Missing pre-authorisation"));

        // Both flags: reads and the appeal work.
        grantAccess(caregiver, patient, a -> {
            a.setCanViewBilling(true);
            a.setCanManageBilling(true);
        });
        assertEquals(claimId, json(doGet(CLAIMS + "/" + claimId, caregiver)).get("claimId").asInt());
        assertEquals(1, json(doGet(listUrl, caregiver)).get("totalElements").asInt());

        // Another patient's claims stay closed to this caregiver.
        assertError(doGet(CLAIMS + "?patientSystemId=" + otherPatient.getSystemId(), caregiver), 403, NO_GRANT);
        // An unrelated caregiver has no grant.
        assertError(doGet(CLAIMS + "/" + claimId, otherCaregiver), 403, NO_GRANT);

        // Expired grant: refused on the next request.
        grantAccess(caregiver, patient, a -> {
            a.setAccessLevel("FULL_ACCESS");
            a.setExpiresAt(LocalDateTime.now().minusDays(1));
        });
        assertError(doGet(CLAIMS + "/" + claimId, caregiver), 403, NO_GRANT);
        assertError(doPost(CLAIMS + "/" + claimId + "/appeal", caregiver, Map.of("reason", "Please review")), 403,
                NO_GRANT);

        // FULL_ACCESS grants both.
        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        JsonNode appealed = appealOk(caregiver, claimId, "The letter was attached late");
        assertEquals("APPEALED", appealed.get("status").asText());
        // The claim keeps the caregiver as its submitter.
        assertEquals(caregiver.getSystemId(), appealed.get("submittedBySystemId").asText());
    }

    @Test
    void caregiverAppeal_needsTheManageFlag_andARevokedGrantRefusesEveryClaimRoute() throws Exception {
        Integer invoiceId = sentInvoice("900.00");
        Integer claimId = pendingClaim(invoiceId, "900.00");
        adjudicateOk(claimId, decision("REJECTED", null, "Not covered by the plan"));
        String appealUrl = CLAIMS + "/" + claimId + "/appeal";

        // Viewing billing is not enough to appeal
        grantAccess(caregiver, patient, a -> a.setCanViewBilling(true));
        assertError(doPost(appealUrl, caregiver, Map.of("reason", "Please review")), 403, NO_MANAGE);
        assertEquals("REJECTED", json(doGet(CLAIMS + "/" + claimId, caregiver)).get("status").asText());

        // A revoked grant refuses every claim route, whatever flags it carried
        grantAccess(caregiver, patient, a -> {
            a.setCanViewBilling(true);
            a.setCanManageBilling(true);
        });
        jdbc.update("UPDATE dependent_access SET is_active = FALSE, revoked_at = now() "
                + "WHERE caregiver_system_id = ? AND patient_system_id = ?", caregiver.getSystemId(), patient.getSystemId());
        assertError(doPost(appealUrl, caregiver, Map.of("reason", "Please review")), 403, NO_GRANT);
        assertError(doGet(CLAIMS + "/" + claimId, caregiver), 403, NO_GRANT);
        assertError(doGet(CLAIMS + "?patientSystemId=" + patient.getSystemId(), caregiver), 403, NO_GRANT);
        assertError(doPost(submitUrl(sentInvoice("300.00")), caregiver, claimBody(null)), 403, NO_GRANT);
        assertEquals("REJECTED", json(doGet(CLAIMS + "/" + claimId, patient)).get("status").asText());
        assertEquals(0, claimEvents(claimId).stream().filter(e -> "APPEALED".equals(e.status())).count());

        // The patient still appeals their own claim
        assertEquals("APPEALED", appealOk(patient, claimId, "The pre-authorisation letter is attached")
                .get("status").asText());
    }

    @Test
    void adminSubmitsOnBehalfOfThePatient_andIsNotNotifiedOfTheirOwnClaim() throws Exception {
        User secondAdmin = createAndSaveUser(8, "ADM-00002", "second.admin@example.com", "ADMIN");
        Integer invoiceId = sentInvoice("640.00");
        JsonNode claim = submitOk(admin, invoiceId, claimBody(null));
        int claimId = claim.get("claimId").asInt();
        assertEquals(admin.getSystemId(), claim.get("submittedBySystemId").asText());
        assertEquals(patient.getSystemId(), claim.get("patientSystemId").asText());
        assertEquals(0, fixtures.count("SELECT COUNT(*) FROM notifications WHERE system_id = ? AND dedupe_key = ?",
                admin.getSystemId(), "CLAIM_SUBMITTED:" + claimId));
        assertEquals(1, fixtures.count("SELECT COUNT(*) FROM notifications WHERE system_id = ? AND dedupe_key = ?",
                secondAdmin.getSystemId(), "CLAIM_SUBMITTED:" + claimId));
    }

    // ==================== LISTS ====================

    @Test
    void patientList_filtersPagesAndAudits() throws Exception {
        Integer a = sentInvoice("100.00");
        Integer b = sentInvoice("200.00");
        Integer c = sentInvoice("300.00");
        int first = submitOk(patient, a, claimBody(null)).get("claimId").asInt();
        int second = submitOk(patient, b, claimBody(null)).get("claimId").asInt();
        int third = submitOk(patient, c, claimBody(null)).get("claimId").asInt();
        adjudicateOk(second, decision("PENDING", null, null));
        Integer otherInvoice = fixtures.invoice(otherPatient, otherDoctor, "SENT", "50.00", "0.00");
        submitOk(otherPatient, otherInvoice, claimBody(null));

        // The patient's own list without an id: newest first, only their claims.
        JsonNode page = json(doGet(CLAIMS, patient));
        assertEquals(3, page.get("totalElements").asInt());
        assertEquals(third, page.get("content").get(0).get("claimId").asInt());
        assertEquals(first, page.get("content").get(2).get("claimId").asInt());
        assertEquals(0, page.get("page").asInt());
        assertEquals(20, page.get("size").asInt());
        assertEquals(1, page.get("totalPages").asInt());

        JsonNode open = json(doGet(CLAIMS + "?status=submitted,Appealed", patient));
        assertEquals(2, open.get("totalElements").asInt());
        JsonNode pending = json(doGet(CLAIMS + "?patientSystemId=" + patient.getSystemId() + "&status=PENDING", patient));
        assertEquals(1, pending.get("totalElements").asInt());
        assertEquals(second, pending.get("content").get(0).get("claimId").asInt());
        JsonNode byInvoice = json(doGet(CLAIMS + "?invoiceId=" + c, patient));
        assertEquals(1, byInvoice.get("totalElements").asInt());
        assertEquals(third, byInvoice.get("content").get(0).get("claimId").asInt());
        assertEquals(0, json(doGet(CLAIMS + "?invoiceId=" + otherInvoice, patient)).get("totalElements").asInt());

        JsonNode clamped = json(doGet(CLAIMS + "?size=1000&page=-3", patient));
        assertEquals(100, clamped.get("size").asInt());
        assertEquals(0, clamped.get("page").asInt());
        JsonNode small = json(doGet(CLAIMS + "?size=1&page=1", patient));
        assertEquals(1, small.get("content").size());
        assertEquals(3, small.get("totalPages").asInt());
        assertEquals(second, small.get("content").get(0).get("claimId").asInt());

        assertError(doGet(CLAIMS + "?status=BOGUS", patient), 400, "Unknown claim status 'BOGUS'");
        MvcResult malformed = doGet(CLAIMS + "?patientSystemId=nobody", admin);
        assertError(malformed, 400, "patientSystemId: must be a valid system id");
        assertEquals("VALIDATION_FAILED", json(malformed).get("code").asText());
        assertError(doGet(CLAIMS, admin), 400, "patientSystemId is required");
        assertError(doGet(CLAIMS + "?page=abc", patient), 400, "Invalid value for parameter 'page'");

        assertEquals(3, json(doGet(CLAIMS + "?patientSystemId=" + patient.getSystemId(), admin))
                .get("totalElements").asInt());
        assertTrue(fixtures.count("SELECT COUNT(*) FROM audit_log WHERE action = 'READ' AND resource_type = "
                + "'INSURANCE_CLAIM' AND resource_id IS NULL AND patient_system_id = ?", patient.getSystemId()) >= 1);
    }

    @Test
    void adminQueue_listsEveryPatient_withFiltersAndOneReadRowPerPatient() throws Exception {
        Integer mine = sentInvoice("100.00");
        Integer theirs = fixtures.invoice(otherPatient, otherDoctor, "SENT", "200.00", "0.00");
        int a = submitOk(patient, mine, claimBody(null)).get("claimId").asInt();
        int b = submitOk(otherPatient, theirs, claimBody(null)).get("claimId").asInt();
        adjudicateOk(a, decision("PENDING", null, null));
        adjudicateOk(a, decision("REJECTED", null, "Not covered"));

        JsonNode all = json(doGet(ADMIN_CLAIMS, admin));
        assertEquals(2, all.get("totalElements").asInt());
        assertEquals(b, all.get("content").get(0).get("claimId").asInt());
        // logListRead: one READ row per distinct patient on the page.
        for (User owner : List.of(patient, otherPatient)) {
            assertEquals(1, fixtures.count("SELECT COUNT(*) FROM audit_log WHERE action = 'READ' "
                    + "AND resource_type = 'INSURANCE_CLAIM' AND patient_system_id = ?", owner.getSystemId()));
        }

        JsonNode queue = json(doGet(ADMIN_CLAIMS + "?status=SUBMITTED,PENDING,APPEALED&size=1", admin));
        assertEquals(1, queue.get("totalElements").asInt());
        assertEquals(b, queue.get("content").get(0).get("claimId").asInt());
        assertEquals(1, json(doGet(ADMIN_CLAIMS + "?patientSystemId=" + patient.getSystemId(), admin))
                .get("totalElements").asInt());
        assertEquals(1, json(doGet(ADMIN_CLAIMS + "?invoiceId=" + theirs, admin)).get("totalElements").asInt());
        assertError(doGet(ADMIN_CLAIMS + "?status=OPEN", admin), 400, "Unknown claim status 'OPEN'");
        assertError(doGet(ADMIN_CLAIMS + "?patientSystemId=PAT-1", admin), 400,
                "patientSystemId: must be a valid system id");
    }

    @Test
    void nonAdmins_get403OnEveryAdminRoute() throws Exception {
        Integer invoiceId = sentInvoice("500.00");
        Integer claimId = submitOk(patient, invoiceId, claimBody(null)).get("claimId").asInt();
        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        for (User user : List.of(patient, doctor, caregiver, otherPatient, otherDoctor)) {
            assertError(doGet(ADMIN_CLAIMS, user), 403, URL_GATE);
            assertError(doPatch(ADMIN_CLAIMS + "/" + claimId, user, decision("PENDING", null, null)), 403, URL_GATE);
            assertError(doPost("/api/admin/billing/invoices/" + invoiceId + "/void", user,
                    Map.of("reason", "Billed in error")), 403, URL_GATE);
        }
        assertEquals("SUBMITTED", json(doGet(CLAIMS + "/" + claimId, patient)).get("status").asText());
        assertEquals("SENT", fixtures.invoiceStatus(invoiceId));
    }

    // ==================== STATE MACHINE ====================

    @Test
    void transitions_followTheTable_andSameStatusIsANoOp() throws Exception {
        Integer invoiceId = sentInvoice("1000.00");
        int claimId = submitOk(patient, invoiceId, claimBody(null)).get("claimId").asInt();

        // Body validation and body rules that need no claim.
        MvcResult missing = adjudicate(claimId, new HashMap<>());
        assertError(missing, 400, "status: must not be blank");
        assertEquals("VALIDATION_FAILED", json(missing).get("code").asText());
        assertError(adjudicate(claimId, decision("APPEALED", null, null)), 400,
                "status: must be one of PENDING, APPROVED, PARTIAL, REJECTED");
        assertError(adjudicate(claimId, decision("PARTIAL", "-5", null)), 400, "approvedAmount: must be greater than 0");
        assertError(adjudicate(claimId, decision("PARTIAL", null, null)), 400,
                "approvedAmount is required for a partial approval");
        assertError(adjudicate(claimId, decision("REJECTED", null, "   ")), 400,
                "rejectionReason is required when rejecting a claim");
        assertError(adjudicate(999999, decision("PENDING", null, null)), 404, "Claim not found");

        // SUBMITTED cannot be decided directly, nor appealed.
        assertError(adjudicate(claimId, decision("APPROVED", null, null)), 409,
                "A claim cannot move from SUBMITTED to APPROVED");
        assertError(adjudicate(claimId, decision("rejected", null, "No")), 409,
                "A claim cannot move from SUBMITTED to REJECTED");
        assertError(doPost(CLAIMS + "/" + claimId + "/appeal", patient, Map.of("reason", "Why")), 409,
                "A claim in status SUBMITTED cannot be appealed");
        // Start review; repeating it is a no-op.
        JsonNode same = adjudicateOk(claimId, decision("PENDING", null, null));
        assertEquals("PENDING", same.get("status").asText());
        assertEquals(admin.getSystemId(), same.get("reviewedBySystemId").asText());
        int eventsBefore = claimEvents(claimId).size();
        long updatesBefore = auditRows("UPDATE", "INSURANCE_CLAIM", claimId);
        JsonNode noop = adjudicateOk(claimId, decision(" pending ", null, null));
        assertEquals("PENDING", noop.get("status").asText());
        assertEquals(eventsBefore, claimEvents(claimId).size());
        assertEquals(updatesBefore, auditRows("UPDATE", "INSURANCE_CLAIM", claimId));
        assertError(doPost(CLAIMS + "/" + claimId + "/appeal", patient, Map.of("reason", "Why")), 409,
                "A claim in status PENDING cannot be appealed");

        // PENDING -> PARTIAL needs an amount below the claim.
        assertError(adjudicate(claimId, decision("PARTIAL", "1000.00", null)), 400,
                "A partial approval must be below the claimed amount");
        assertError(adjudicate(claimId, decision("PARTIAL", "1200.00", null)), 400,
                "A partial approval must be below the claimed amount");

        // PENDING -> REJECTED, then appeal -> APPEALED -> PENDING.
        JsonNode rejected = adjudicateOk(claimId, decision("REJECTED", null, "The policy had lapsed"));
        assertEquals("REJECTED", rejected.get("status").asText());
        assertEquals("The policy had lapsed", rejected.get("rejectionReason").asText());
        assertTrue(rejected.get("approvedAmount").isNull());
        assertFalse(rejected.get("resolvedAt").isNull());
        assertEquals(0, payments(invoiceId));
        assertEquals("SENT", fixtures.invoiceStatus(invoiceId));
        assertError(adjudicate(claimId, decision("APPROVED", null, null)), 409,
                "A claim cannot move from REJECTED to APPROVED");
        assertError(adjudicate(claimId, decision("PENDING", null, null)), 409,
                "A claim cannot move from REJECTED to PENDING");

        JsonNode appealed = appealOk(patient, claimId, "  The policy was renewed on time.  ");
        assertEquals("APPEALED", appealed.get("status").asText());
        assertEquals("The policy was renewed on time.", appealed.get("appealReason").asText());
        assertEquals("The policy had lapsed", appealed.get("rejectionReason").asText());
        assertTrue(appealed.get("resolvedAt").isNull());
        assertEquals(1, fixtures.count("SELECT COUNT(*) FROM notifications WHERE system_id = ? AND title = "
                        + "'Insurance claim appealed' AND dedupe_key LIKE ? AND action_url = ?", admin.getSystemId(),
                "CLAIM_APPEALED:" + claimId + ":%", "/dashboard/admin?section=billing&id=" + claimId + "&tab=claims"));

        // Appealing again is a no-op that keeps the first reason.
        JsonNode again = appealOk(patient, claimId, "Another reason");
        assertEquals("The policy was renewed on time.", again.get("appealReason").asText());
        assertError(adjudicate(claimId, decision("APPROVED", null, null)), 409,
                "A claim cannot move from APPEALED to APPROVED");
        assertEquals("PENDING", adjudicateOk(claimId, decision("PENDING", null, null)).get("status").asText());

        List<String> path = claimEvents(claimId).stream().map(InsuranceClaimStatusChangedEvent::status).toList();
        assertEquals(List.of("SUBMITTED", "PENDING", "REJECTED", "APPEALED", "PENDING"), path);
        assertEquals("REJECTED", claimEvents(claimId).get(3).previousStatus());
        // One UPDATE row per real change (PENDING, REJECTED, APPEALED, PENDING); the no-ops wrote none.
        assertEquals(4, auditRows("UPDATE", "INSURANCE_CLAIM", claimId));

        assertError(doPost(CLAIMS + "/" + claimId + "/appeal", patient, Map.of("reason", " ")), 400,
                "reason: must not be blank");
    }

    @Test
    void approvalInFull_settlesTheInvoice_withAnInsurancePayment() throws Exception {
        Integer invoiceId = sentInvoice("2320.00");
        int claimId = pendingClaim(invoiceId, null);

        JsonNode approved = adjudicateOk(claimId, decision("APPROVED", "5.00", "ignored"));
        assertEquals("APPROVED", approved.get("status").asText());
        assertMoney("2320.00", approved.get("approvedAmount"));
        assertTrue(approved.get("rejectionReason").isNull());
        assertMoney("0.00", approved.get("invoiceOutstanding"));
        assertFalse(approved.get("resolvedAt").isNull());
        assertEquals(admin.getSystemId(), approved.get("reviewedBySystemId").asText());
        int paymentId = approved.get("paymentId").asInt();

        assertEquals("PAID", fixtures.invoiceStatus(invoiceId));
        assertEquals(0, new BigDecimal("2320.00").compareTo(fixtures.amountPaid(invoiceId)));
        Map<String, Object> payment = jdbc.queryForMap("SELECT * FROM payments WHERE payment_id = ?", paymentId);
        assertEquals("COMPLETED", payment.get("status"));
        assertEquals("INSURANCE", payment.get("method"));
        assertEquals("INSURANCE", payment.get("gateway"));
        assertEquals(approved.get("claimNo").asText(), payment.get("provider_reference"));
        assertEquals(admin.getSystemId(), payment.get("initiated_by_system_id"));
        assertEquals(patient.getSystemId(), payment.get("patient_system_id"));
        assertEquals("KES", payment.get("currency"));
        assertEquals(0, new BigDecimal("2320.00").compareTo((BigDecimal) payment.get("amount")));
        assertEquals(null, payment.get("transaction_id"));
        assertEquals(null, payment.get("payment_method_id"));
        assertFalse(payment.get("paid_at") == null);

        // Events: payment recorded (method INSURANCE, actor = admin), invoice paid, claim approved.
        List<PaymentRecordedEvent> recorded = events.stream(PaymentRecordedEvent.class).toList();
        assertEquals(1, recorded.size());
        assertEquals(paymentId, recorded.get(0).paymentId());
        assertEquals(admin.getSystemId(), recorded.get(0).initiatedBySystemId());
        assertEquals("INSURANCE", recorded.get(0).method());
        assertEquals("COMPLETED", recorded.get(0).status());
        assertEquals(0, new BigDecimal("2320.00").compareTo(recorded.get(0).amount()));
        assertTrue(events.stream(InvoiceStatusChangedEvent.class)
                .anyMatch(e -> e.invoiceId().equals(invoiceId) && "PAID".equals(e.status())));
        InsuranceClaimStatusChangedEvent last = claimEvents(claimId).get(claimEvents(claimId).size() - 1);
        assertEquals("PENDING", last.previousStatus());
        assertEquals("APPROVED", last.status());
        assertEquals(1, auditRows("CREATE", "PAYMENT", paymentId));

        // Approving again is a no-op (no second payment); APPROVED is terminal.
        JsonNode again = adjudicateOk(claimId, decision("APPROVED", null, null));
        assertEquals(paymentId, again.get("paymentId").asInt());
        assertEquals(1, payments(invoiceId));
        assertError(adjudicate(claimId, decision("PENDING", null, null)), 409,
                "A claim cannot move from APPROVED to PENDING");
        assertError(doPost(CLAIMS + "/" + claimId + "/appeal", patient, Map.of("reason", "More")), 409,
                "A claim in status APPROVED cannot be appealed");

        // The patient reads the settled claim.
        JsonNode read = json(doGet(CLAIMS + "/" + claimId, patient));
        assertEquals("APPROVED", read.get("status").asText());
        assertEquals(1, auditRows("READ", "INSURANCE_CLAIM", claimId));
    }

    @Test
    void partialApproval_leavesABalance_andTheAppealSettlesOnlyTheDifference() throws Exception {
        Integer invoiceId = sentInvoice("2000.00");
        int claimId = pendingClaim(invoiceId, null);

        JsonNode partial = adjudicateOk(claimId,
                decision("PARTIAL", "1500.00", "The policy covers consultations up to KES 1,500"));
        assertEquals("PARTIAL", partial.get("status").asText());
        assertMoney("1500.00", partial.get("approvedAmount"));
        assertMoney("500.00", partial.get("invoiceOutstanding"));
        assertEquals("The policy covers consultations up to KES 1,500", partial.get("rejectionReason").asText());
        int firstPayment = partial.get("paymentId").asInt();
        assertEquals("PARTIALLY_PAID", fixtures.invoiceStatus(invoiceId));
        assertEquals(0, new BigDecimal("1500.00").compareTo(fixtures.amountPaid(invoiceId)));

        // Appeal, reopen; a partly paid claim cannot be rejected nor lowered.
        appealOk(patient, claimId, "The full fee is covered");
        adjudicateOk(claimId, decision("PENDING", null, null));
        assertError(adjudicate(claimId, decision("REJECTED", null, "No")), 409,
                "A claim with an approved amount cannot be rejected; decide PARTIAL with the same amount instead");
        assertError(adjudicate(claimId, decision("PARTIAL", "1400.00", null)), 400,
                "The approved amount cannot be lower than the amount already approved");

        // Dismissing the appeal with the same amount settles nothing.
        JsonNode dismissed = adjudicateOk(claimId, decision("PARTIAL", "1500.00", " "));
        assertEquals("PARTIAL", dismissed.get("status").asText());
        assertTrue(dismissed.get("rejectionReason").isNull());
        assertEquals(firstPayment, dismissed.get("paymentId").asInt());
        assertEquals(1, payments(invoiceId));
        assertEquals("PARTIALLY_PAID", fixtures.invoiceStatus(invoiceId));

        // A second appeal approved in full pays only the remaining 500.00.
        appealOk(patient, claimId, "Second look please");
        adjudicateOk(claimId, decision("PENDING", null, null));
        JsonNode full = adjudicateOk(claimId, decision("APPROVED", null, null));
        assertMoney("2000.00", full.get("approvedAmount"));
        assertMoney("0.00", full.get("invoiceOutstanding"));
        int secondPayment = full.get("paymentId").asInt();
        assertNotEquals(firstPayment, secondPayment);
        assertEquals(2, payments(invoiceId));
        assertEquals(0, new BigDecimal("500.00").compareTo(jdbc.queryForObject(
                "SELECT amount FROM payments WHERE payment_id = ?", BigDecimal.class, secondPayment)));
        assertEquals("PAID", fixtures.invoiceStatus(invoiceId));
        assertEquals(2, events.stream(PaymentRecordedEvent.class).count());
    }

    @Test
    void settlementAboveTheBalance_is409_andAPaidInvoiceCanOnlyBeRejected() throws Exception {
        Integer invoiceId = sentInvoice("1000.00");
        int claimId = pendingClaim(invoiceId, null);
        // The patient paid part of the invoice meanwhile.
        jdbc.update("UPDATE invoices SET amount_paid = 600.00, status = 'PARTIALLY_PAID' WHERE invoice_id = ?",
                invoiceId);

        assertError(adjudicate(claimId, decision("APPROVED", null, null)), 409,
                "The approved amount exceeds the invoice's outstanding balance");
        assertEquals("PENDING", json(doGet(CLAIMS + "/" + claimId, admin)).get("status").asText());
        assertEquals(0, payments(invoiceId));

        JsonNode partial = adjudicateOk(claimId, decision("PARTIAL", "400.00", null));
        assertEquals("PARTIAL", partial.get("status").asText());
        assertEquals("PAID", fixtures.invoiceStatus(invoiceId));
        assertMoney("0.00", partial.get("invoiceOutstanding"));
    }

    @Test
    void concurrentApprovals_createOnePayment() throws Exception {
        Integer invoiceId = sentInvoice("1100.00");
        int claimId = pendingClaim(invoiceId, null);
        List<Integer> statuses = runConcurrently(2, () -> adjudicate(claimId, decision("APPROVED", null, null)));
        assertEquals(List.of(200, 200), statuses);
        assertEquals(1, payments(invoiceId));
        assertEquals("PAID", fixtures.invoiceStatus(invoiceId));
    }

    // ==================== VOID ====================

    @Test
    void void_cancelsAnUnpaidInvoice_andIsRefusedAfterAPayment() throws Exception {
        Integer unpaid = sentInvoice("700.00");
        Integer claimId = submitOk(patient, unpaid, claimBody(null)).get("claimId").asInt();
        String voidUrl = "/api/admin/billing/invoices/" + unpaid + "/void";

        assertError(doPost(voidUrl, admin, Map.of("reason", "  ")), 400, "reason: must not be blank");
        assertError(doPost(voidUrl, admin, Map.of("reason", "x".repeat(501))), 400,
                "reason: size must be between 0 and 500");
        assertError(doPost("/api/admin/billing/invoices/999999/void", admin, Map.of("reason", "Billed in error")), 404,
                "Invoice not found");

        MvcResult voided = doPost(voidUrl, admin, Map.of("reason", " Billed in error: the visit was free of charge "));
        assertEquals(200, voided.getResponse().getStatus(), voided.getResponse().getContentAsString());
        assertEquals("CANCELLED", json(voided).get("status").asText());
        assertEquals("Billed in error: the visit was free of charge", json(voided).get("cancelReason").asText());
        assertTrue(events.stream(InvoiceStatusChangedEvent.class)
                .anyMatch(e -> e.invoiceId().equals(unpaid) && "CANCELLED".equals(e.status())));

        // Voiding again is a no-op that keeps the stored reason; the claim is untouched.
        MvcResult again = doPost(voidUrl, admin, Map.of("reason", "Another reason"));
        assertEquals(200, again.getResponse().getStatus());
        assertEquals("Billed in error: the visit was free of charge", json(again).get("cancelReason").asText());
        assertEquals("SUBMITTED", json(doGet(CLAIMS + "/" + claimId, admin)).get("status").asText());
        // Its settlement can no longer fit: the open claim can only be rejected.
        adjudicateOk(claimId, decision("PENDING", null, null));
        assertError(adjudicate(claimId, decision("APPROVED", null, null)), 409,
                "The approved amount exceeds the invoice's outstanding balance");

        // An invoice that received an insurance payment cannot be voided.
        Integer settled = sentInvoice("900.00");
        int settledClaim = pendingClaim(settled, "300.00");
        adjudicateOk(settledClaim, decision("APPROVED", null, null));
        assertEquals("PARTIALLY_PAID", fixtures.invoiceStatus(settled));
        assertError(doPost("/api/admin/billing/invoices/" + settled + "/void", admin,
                Map.of("reason", "Billed in error")), 409, "An invoice with payments cannot be cancelled");
        assertEquals("PARTIALLY_PAID", fixtures.invoiceStatus(settled));

        // A DRAFT can be voided by the administrator.
        Integer draft = fixtures.invoice(patient, doctor, "DRAFT", "100.00", "0.00");
        MvcResult draftVoid = doPost("/api/admin/billing/invoices/" + draft + "/void", admin,
                Map.of("reason", "Duplicate"));
        assertEquals(200, draftVoid.getResponse().getStatus(), draftVoid.getResponse().getContentAsString());
        assertEquals("CANCELLED", fixtures.invoiceStatus(draft));
    }

    // ==================== CONCURRENCY HELPER ====================

    private List<Integer> runConcurrently(int threads, Callable<MvcResult> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> calls = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                calls.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<MvcResult> future : calls) {
                statuses.add(future.get(30, TimeUnit.SECONDS).getResponse().getStatus());
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }
}
