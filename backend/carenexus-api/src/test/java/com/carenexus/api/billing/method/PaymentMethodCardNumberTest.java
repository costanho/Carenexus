package com.carenexus.api.billing.method;

import com.carenexus.api.common.validation.NoCardNumberValidator;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A card number typed into a free-text name field of a payment method (the card brand, which also feeds
 * {@code displayLabel}, or the insurer's name) is refused with 400 and never stored. The stored identifiers
 * (policy and member number) refuse a Luhn-valid number with a card network prefix only, since long digit strings are
 * normal there.
 */
class PaymentMethodCardNumberTest extends CommittedIntegrationTest {

    private static final String METHODS = "/api/billing/payment-methods";

    private static Map<String, Object> card(String provider) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "CARD");
        body.put("provider", provider);
        body.put("lastFour", "1111");
        return body;
    }

    private static Map<String, Object> insurance(String insurer) {
        return insurance(insurer, "JUB-2026-778841", null);
    }

    private static Map<String, Object> insurance(String insurer, String policyNumber, String memberNumber) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "INSURANCE");
        body.put("insurerName", insurer);
        body.put("policyNumber", policyNumber);
        if (memberNumber != null) {
            body.put("memberNumber", memberNumber);
        }
        return body;
    }

    private void assertRefused(MvcResult result, String field) throws Exception {
        assertEquals(400, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals("VALIDATION_FAILED", json(result).path("code").asText());
        assertEquals(field + ": must not contain a card number", message(result));
    }

    private int storedMethods() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM payment_methods", Integer.class);
        return count == null ? 0 : count;
    }

    @Test
    void aCardNumberInTheBrandOrInsurerName_isRefused_andNothingIsStored() throws Exception {
        assertRefused(doPost(METHODS, patient, card("4111111111111111")), "provider");
        assertRefused(doPost(METHODS, patient, card("Visa 4111 1111 1111 1111")), "provider");
        assertRefused(doPost(METHODS, patient, card("Visa 4111-1111-1111-1111 exp 12/29")), "provider");
        assertRefused(doPost(METHODS, patient, card("Visa 4111 1111 1111 1111 1229")), "provider");
        assertRefused(doPost(METHODS, patient, card("Visa 4111.1111.1111.1111")), "provider");
        assertRefused(doPost(METHODS, patient, card("Visa 4111  1111  1111  1111")), "provider");
        assertRefused(doPost(METHODS, patient, insurance("AAR 5500 0000 0000 0004")), "insurerName");
        assertEquals(0, storedMethods());
        assertEquals(0, count("SELECT COUNT(*) FROM audit_log WHERE resource_type = 'PAYMENT_METHOD' AND action = 'CREATE'"));
    }

    @Test
    void aCardNumberAsPolicyOrMemberNumber_isRefused_andNothingIsStored() throws Exception {
        assertRefused(doPost(METHODS, patient, insurance("Jubilee", "4111 1111 1111 1111", null)), "policyNumber");
        assertRefused(doPost(METHODS, patient, insurance("Jubilee", "4111111111111111 12/29", null)), "policyNumber");
        assertRefused(doPost(METHODS, patient, insurance("Jubilee", "JUB-2026-778841", "5500-0000-0000-0004")),
                "memberNumber");
        assertEquals(0, storedMethods());
    }

    @Test
    void longNumericPolicyAndMemberNumbers_withoutACardNetworkPrefix_areAccepted() throws Exception {
        // Luhn-valid, 16 digits, but no card network starts with 1
        MvcResult numeric = doPost(METHODS, patient, insurance("Jubilee", "1234567812345670", "9000000000000000008"));
        assertEquals(201, numeric.getResponse().getStatus(), numeric.getResponse().getContentAsString());
        MvcResult grouped = doPost(METHODS, patient, insurance("Old Mutual", "POL 2026/0001/778841", "M-00231"));
        assertEquals(201, grouped.getResponse().getStatus(), grouped.getResponse().getContentAsString());
        assertEquals(2, storedMethods());
    }

    @Test
    void ordinaryNamesWithDigits_areAccepted() throws Exception {
        MvcResult visa = doPost(METHODS, patient, card("Visa Gold 2026"));
        assertEquals(201, visa.getResponse().getStatus(), visa.getResponse().getContentAsString());
        String label = objectMapper.readTree(visa.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("displayLabel").asText();
        assertEquals("Visa Gold 2026 \u2022\u2022\u2022\u2022 1111", label);
        // 16 digits that fail the Luhn check are not a card number
        MvcResult insurer = doPost(METHODS, patient, insurance("Insurer 1234567812345678"));
        assertEquals(201, insurer.getResponse().getStatus(), insurer.getResponse().getContentAsString());
        assertEquals(2, storedMethods());
    }

    @Test
    void detector_findsGroupedRunsOfThirteenToNineteenLuhnDigits() {
        assertTrue(NoCardNumberValidator.containsCardNumber("4242424242424242"));
        assertTrue(NoCardNumberValidator.containsCardNumber("card 4242 4242 4242 4242."));
        assertTrue(NoCardNumberValidator.containsCardNumber("378282246310005"));            // 15-digit Amex
        assertTrue(NoCardNumberValidator.containsCardNumber("6011-1111-1111-1117"));
        assertTrue(NoCardNumberValidator.containsCardNumber("4242  4242 4242 4242"));      // double space joins groups
        assertTrue(NoCardNumberValidator.containsCardNumber("4111.1111.1111.1111"));
        assertTrue(NoCardNumberValidator.containsCardNumber("4111/1111/1111/1111"));
        assertTrue(NoCardNumberValidator.containsCardNumber("Visa 4111 1111 1111 1111 1229")); // card + expiry: 20
        assertTrue(NoCardNumberValidator.containsCardNumber("41111111111111111229"));      // the same without spaces
        assertTrue(NoCardNumberValidator.containsCardNumber("4111 1111 1111 1111 5"));     // card + one more group
        assertFalse(NoCardNumberValidator.containsCardNumber("4242424242424241"));         // Luhn fails
        assertFalse(NoCardNumberValidator.containsCardNumber("424242424242"));             // 12 digits
        assertFalse(NoCardNumberValidator.containsCardNumber("4242   4242 4242 4242"));    // three spaces split it
        assertFalse(NoCardNumberValidator.containsCardNumber("Visa"));
        assertFalse(NoCardNumberValidator.containsCardNumber(null));

        // Identifier mode: also needs a card network prefix
        assertTrue(NoCardNumberValidator.containsCardNumber("4111111111111111", true));
        assertTrue(NoCardNumberValidator.containsCardNumber("5500-0000-0000-0004", true));
        assertTrue(NoCardNumberValidator.containsCardNumber("4111 1111 1111 1111 12/29", true));
        assertTrue(NoCardNumberValidator.containsCardNumber("41111111111111111229", true));
        assertFalse(NoCardNumberValidator.containsCardNumber("1234567812345670", true));   // Luhn-valid, no prefix
        assertTrue(NoCardNumberValidator.containsCardNumber("1234567812345670"));           // refused in a name
        assertFalse(NoCardNumberValidator.containsCardNumber("12345678901234567890123", true));
        assertFalse(NoCardNumberValidator.containsCardNumber("JUB-2026-778841", true));
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
