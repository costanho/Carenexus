package com.carenexus.api.admin.settings;

import com.carenexus.api.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SettingKey: the closed list with its dotted names, types, limits and defaults, the key lookup, and validation
 * and normalisation of a value for each type. No Spring context, no database.
 */
public class SettingKeyTest {

    private static void assertRejected(SettingKey key, String raw, String expectedMessage) {
        BadRequestException ex = assertThrows(BadRequestException.class, () -> key.normalize(raw),
                () -> key.key() + " accepted '" + raw + "'");
        assertEquals(expectedMessage, ex.getMessage());
        assertEquals("VALIDATION_FAILED", ex.getCode());
    }

    // ==================== THE LIST ====================

    @Test
    void keys_areTheNineOfTheContract_inOrder_withTheirDottedNames() {
        List<String> names = Arrays.stream(SettingKey.values()).map(SettingKey::key).toList();
        assertEquals(List.of(
                "clinic.name",
                "support.contact-email",
                "support.contact-phone",
                "reminders.appointment-lead-hours",
                "reminders.second-lead-minutes",
                "appointments.needs-outcome-after-minutes",
                "billing.default-currency",
                "billing.payment-terms-days",
                "billing.tax-rate"), names);
        assertEquals(List.of("CLINIC_NAME", "SUPPORT_CONTACT_EMAIL", "SUPPORT_CONTACT_PHONE",
                        "REMINDERS_APPOINTMENT_LEAD_HOURS", "REMINDERS_SECOND_LEAD_MINUTES",
                        "APPOINTMENTS_NEEDS_OUTCOME_AFTER_MINUTES", "BILLING_DEFAULT_CURRENCY",
                        "BILLING_PAYMENT_TERMS_DAYS", "BILLING_TAX_RATE"),
                Arrays.stream(SettingKey.values()).map(Enum::name).toList());
    }

    @Test
    void keys_fitTheColumn_andCarryTypeLimitsDescriptionAndDefault() {
        for (SettingKey key : SettingKey.values()) {
            assertTrue(key.key().length() <= 80, key + " does not fit system_settings.setting_key");
            assertEquals(key.key(), key.key().toLowerCase(), key + " dotted name must be lower case");
            assertNotNull(key.type());
            assertFalse(key.description().isBlank());
            assertTrue(key.description().endsWith("."), key + " description is one sentence");
            assertFalse(key.properties().isEmpty(), key + " has no fallback property");
            boolean numeric = key.type() == SettingType.INT || key.type() == SettingType.DECIMAL;
            assertEquals(numeric, key.min() != null, key + " min");
            assertEquals(numeric, key.max() != null, key + " max");
            assertEquals(!numeric, key.maxLength() != null, key + " maxLength");
        }
        assertEquals(SettingType.STRING, SettingKey.CLINIC_NAME.type());
        assertEquals(SettingType.EMAIL, SettingKey.SUPPORT_CONTACT_EMAIL.type());
        assertEquals(SettingType.PHONE, SettingKey.SUPPORT_CONTACT_PHONE.type());
        assertEquals(SettingType.INT, SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS.type());
        assertEquals(SettingType.INT, SettingKey.REMINDERS_SECOND_LEAD_MINUTES.type());
        assertEquals(SettingType.INT, SettingKey.APPOINTMENTS_NEEDS_OUTCOME_AFTER_MINUTES.type());
        assertEquals(SettingType.CURRENCY, SettingKey.BILLING_DEFAULT_CURRENCY.type());
        assertEquals(SettingType.INT, SettingKey.BILLING_PAYMENT_TERMS_DAYS.type());
        assertEquals(SettingType.DECIMAL, SettingKey.BILLING_TAX_RATE.type());

        assertEquals(120, SettingKey.CLINIC_NAME.maxLength());
        assertEquals(254, SettingKey.SUPPORT_CONTACT_EMAIL.maxLength());
        assertEquals(30, SettingKey.SUPPORT_CONTACT_PHONE.maxLength());
        assertEquals(3, SettingKey.BILLING_DEFAULT_CURRENCY.maxLength());
        assertRange(SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS, 1, 168);
        assertRange(SettingKey.REMINDERS_SECOND_LEAD_MINUTES, 5, 720);
        assertRange(SettingKey.APPOINTMENTS_NEEDS_OUTCOME_AFTER_MINUTES, 5, 1440);
        assertRange(SettingKey.BILLING_PAYMENT_TERMS_DAYS, 0, 365);
        assertRange(SettingKey.BILLING_TAX_RATE, 0, 1);
    }

    private static void assertRange(SettingKey key, int min, int max) {
        assertEquals(0, key.min().compareTo(BigDecimal.valueOf(min)), key + " min");
        assertEquals(0, key.max().compareTo(BigDecimal.valueOf(max)), key + " max");
        // Sent as plain JSON numbers: 168, not 1.68E+2.
        assertEquals(Integer.toString(min), key.min().toString());
        assertEquals(Integer.toString(max), key.max().toString());
    }

    @Test
    void codeDefaults_areTheDocumentedOnes_andEveryNonEmptyDefaultIsValidAndCanonical() {
        assertEquals("CareNexus", SettingKey.CLINIC_NAME.codeDefault());
        assertEquals("", SettingKey.SUPPORT_CONTACT_EMAIL.codeDefault());
        assertEquals("", SettingKey.SUPPORT_CONTACT_PHONE.codeDefault());
        assertEquals("24", SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS.codeDefault());
        assertEquals("60", SettingKey.REMINDERS_SECOND_LEAD_MINUTES.codeDefault());
        assertEquals("30", SettingKey.APPOINTMENTS_NEEDS_OUTCOME_AFTER_MINUTES.codeDefault());
        assertEquals("KES", SettingKey.BILLING_DEFAULT_CURRENCY.codeDefault());
        assertEquals("14", SettingKey.BILLING_PAYMENT_TERMS_DAYS.codeDefault());
        assertEquals("0", SettingKey.BILLING_TAX_RATE.codeDefault());
        for (SettingKey key : SettingKey.values()) {
            if (!key.codeDefault().isEmpty()) {
                assertEquals(key.codeDefault(), key.normalize(key.codeDefault()), key + " default");
            }
        }
    }

    @Test
    void fallbackProperties_areTheDocumentedOnes() {
        assertEquals(List.of("carenexus.clinic.name"), SettingKey.CLINIC_NAME.properties());
        assertEquals(List.of("carenexus.support.contact-email", "carenexus.support.email"),
                SettingKey.SUPPORT_CONTACT_EMAIL.properties());
        assertEquals(List.of("carenexus.support.contact-phone", "carenexus.support.phone"),
                SettingKey.SUPPORT_CONTACT_PHONE.properties());
        assertEquals(List.of("carenexus.reminders.appointment-lead-hours"),
                SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS.properties());
        assertEquals(List.of("carenexus.reminders.second-lead-minutes"),
                SettingKey.REMINDERS_SECOND_LEAD_MINUTES.properties());
        assertEquals(List.of("carenexus.scheduling.needs-outcome-after-minutes"),
                SettingKey.APPOINTMENTS_NEEDS_OUTCOME_AFTER_MINUTES.properties());
        assertEquals(List.of("carenexus.billing.default-currency"), SettingKey.BILLING_DEFAULT_CURRENCY.properties());
        assertEquals(List.of("carenexus.billing.payment-terms-days"), SettingKey.BILLING_PAYMENT_TERMS_DAYS.properties());
        assertEquals(List.of("carenexus.billing.tax-rate"), SettingKey.BILLING_TAX_RATE.properties());
    }

    // ==================== LOOKUP ====================

    @Test
    void fromKey_matchesTheDottedName_trimmedAndCaseInsensitive() {
        for (SettingKey key : SettingKey.values()) {
            assertEquals(key, SettingKey.fromKey(key.key()).orElseThrow());
        }
        assertEquals(SettingKey.BILLING_TAX_RATE, SettingKey.fromKey("  Billing.Tax-Rate ").orElseThrow());
        assertEquals(SettingKey.CLINIC_NAME, SettingKey.fromKey("CLINIC.NAME").orElseThrow());
    }

    @Test
    void fromKey_rejectsTheJavaConstantName_unknownNamesBlankAndNull() {
        assertTrue(SettingKey.fromKey("BILLING_TAX_RATE").isEmpty());
        assertTrue(SettingKey.fromKey("billing.taxrate").isEmpty());
        assertTrue(SettingKey.fromKey("billing").isEmpty());
        assertTrue(SettingKey.fromKey("").isEmpty());
        assertTrue(SettingKey.fromKey("   ").isEmpty());
        assertTrue(SettingKey.fromKey(null).isEmpty());
    }

    // ==================== VALIDATION PER TYPE ====================

    @Test
    void anyKey_rejectsBlankAndNull() {
        for (SettingKey key : SettingKey.values()) {
            assertRejected(key, null, "value: must not be blank");
            assertRejected(key, "", "value: must not be blank");
            assertRejected(key, "   \t ", "value: must not be blank");
        }
    }

    @Test
    void string_isTrimmed_andLimitedTo120Characters() {
        assertEquals("Harare Family Clinic", SettingKey.CLINIC_NAME.normalize("  Harare Family Clinic "));
        assertEquals("x".repeat(120), SettingKey.CLINIC_NAME.normalize("x".repeat(120)));
        // Length is measured after trimming.
        assertEquals("x".repeat(120), SettingKey.CLINIC_NAME.normalize(" " + "x".repeat(120) + " "));
        assertRejected(SettingKey.CLINIC_NAME, "x".repeat(121), "value: must be at most 120 characters");
    }

    @Test
    void email_isTrimmed_keepsItsCase_andMustLookLikeAnAddress() {
        assertEquals("Support@Clinic.example", SettingKey.SUPPORT_CONTACT_EMAIL.normalize(" Support@Clinic.example "));
        String longest = "a".repeat(254 - "@clinic.example".length()) + "@clinic.example";
        assertEquals(254, longest.length());
        assertEquals(longest, SettingKey.SUPPORT_CONTACT_EMAIL.normalize(longest));

        for (String bad : List.of("support", "support@clinic", "@clinic.example", "support@", "a b@clinic.example",
                "support@@clinic.example", "support@clinic.", "a" + longest)) {
            assertRejected(SettingKey.SUPPORT_CONTACT_EMAIL, bad, "value: must be a valid email address");
        }
    }

    @Test
    void phone_isTrimmed_andFollowsTheSharedPhonePattern() {
        assertEquals("+263 24 270 0000", SettingKey.SUPPORT_CONTACT_PHONE.normalize("  +263 24 270 0000 "));
        assertEquals("(024) 270-0000", SettingKey.SUPPORT_CONTACT_PHONE.normalize("(024) 270-0000"));
        assertEquals("0242700000", SettingKey.SUPPORT_CONTACT_PHONE.normalize("0242700000"));

        for (String bad : List.of("12345", "call us", "+263 24 270 0000 ext 5", "1234567890123456",
                // 15 digits is allowed by the pattern, but not when the separators push it past 30 characters
                "1 2 3 4 5 6 7 8 9 0 1 2 3 4 5  -")) {
            assertRejected(SettingKey.SUPPORT_CONTACT_PHONE, bad, "value: must be a valid phone number");
        }
    }

    @Test
    void currency_isUpperCased_andMustBeAKnownIso4217Code() {
        assertEquals("KES", SettingKey.BILLING_DEFAULT_CURRENCY.normalize("kes"));
        assertEquals("USD", SettingKey.BILLING_DEFAULT_CURRENCY.normalize(" Usd "));
        assertEquals("ZAR", SettingKey.BILLING_DEFAULT_CURRENCY.normalize("zAr"));

        for (String bad : List.of("KE", "KESH", "K3S", "QQQ", "12", "$")) {
            assertRejected(SettingKey.BILLING_DEFAULT_CURRENCY, bad, "value: must be an ISO 4217 currency code");
        }
    }

    @Test
    void int_mustBeAWholeNumber_andIsStoredWithoutLeadingZeros() {
        SettingKey key = SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS;
        assertEquals("24", key.normalize("024"));
        assertEquals("48", key.normalize(" 48 "));
        assertEquals("0", SettingKey.BILLING_PAYMENT_TERMS_DAYS.normalize("-0"));

        for (String bad : List.of("12.5", "abc", "1e2", "+5", "24h", "1234567890", "1,000", "٢٤")) {
            assertRejected(key, bad, "value: must be a whole number");
        }
    }

    @Test
    void int_rangesAreInclusive_perKey() {
        assertIntRange(SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS, 1, 168);
        assertIntRange(SettingKey.REMINDERS_SECOND_LEAD_MINUTES, 5, 720);
        assertIntRange(SettingKey.APPOINTMENTS_NEEDS_OUTCOME_AFTER_MINUTES, 5, 1440);
        assertIntRange(SettingKey.BILLING_PAYMENT_TERMS_DAYS, 0, 365);
    }

    private static void assertIntRange(SettingKey key, int min, int max) {
        String message = "value: must be between " + min + " and " + max;
        assertEquals(Integer.toString(min), key.normalize(Integer.toString(min)));
        assertEquals(Integer.toString(max), key.normalize(Integer.toString(max)));
        assertRejected(key, Integer.toString(min - 1), message);
        assertRejected(key, Integer.toString(max + 1), message);
        assertRejected(key, "-999999999", message);
        assertRejected(key, "999999999", message);
    }

    @Test
    void decimal_isAFractionBetween0And1_withAtMost4DecimalPlaces_storedWithoutTrailingZeros() {
        SettingKey key = SettingKey.BILLING_TAX_RATE;
        assertEquals("0.16", key.normalize("0.1600"));
        assertEquals("0.16", key.normalize(" 0.16 "));
        assertEquals("1", key.normalize("1.0"));
        assertEquals("1", key.normalize("1"));
        assertEquals("0", key.normalize("0.0"));
        assertEquals("0", key.normalize("0.0000"));
        assertEquals("0", key.normalize("-0"));
        assertEquals("0.0001", key.normalize("0.0001"));
        assertEquals("0.075", key.normalize("00.0750"));

        for (String bad : List.of("abc", ".5", "5.", "1e-2", "0,16", "16%", "1234567890", "+0.1")) {
            assertRejected(key, bad, "value: must be a number");
        }
        assertRejected(key, "0.16000", "value: must have at most 4 decimal places");
        assertRejected(key, "0.00001", "value: must have at most 4 decimal places");
        assertRejected(key, "1.0001", "value: must be between 0 and 1");
        assertRejected(key, "-0.01", "value: must be between 0 and 1");
        assertRejected(key, "16", "value: must be between 0 and 1");
    }
}
