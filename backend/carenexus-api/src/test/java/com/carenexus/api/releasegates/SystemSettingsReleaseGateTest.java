package com.carenexus.api.releasegates;

import com.carenexus.api.admin.settings.SettingKey;
import com.carenexus.api.admin.settings.SystemSettingsService;
import com.carenexus.api.auth.model.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Release gates for system settings: who may call the four routes (ADMIN for /api/admin/settings, any signed-in
 * user for /api/settings/public, nobody without a token), the list and its defaults, the PUT / DELETE round trip
 * with normalisation, the 400 envelope for every kind of invalid input, the audit rows, and that the typed
 * readers follow a change. Runs with committed transactions (CommittedIntegrationTest).
 */
class SystemSettingsReleaseGateTest extends CommittedIntegrationTest {

    private static final String SETTINGS = "/api/admin/settings";
    private static final String PUBLIC = "/api/settings/public";
    private static final String FORBIDDEN = "You don't have permission to access this resource";
    private static final String UNAUTHENTICATED = "Authentication required";

    private static final List<String> KEYS = List.of(
            "clinic.name",
            "support.contact-email",
            "support.contact-phone",
            "reminders.appointment-lead-hours",
            "reminders.second-lead-minutes",
            "appointments.needs-outcome-after-minutes",
            "billing.default-currency",
            "billing.payment-terms-days",
            "billing.tax-rate");

    private static final List<String> SETTING_FIELDS = List.of("key", "value", "defaultValue", "overridden", "type",
            "description", "min", "max", "maxLength", "updatedAt", "updatedBySystemId");

    @Autowired
    private SystemSettingsService systemSettingsService;

    // ==================== HELPERS ====================

    private static Map<String, Object> value(String value) {
        Map<String, Object> body = new HashMap<>();
        body.put("value", value);
        return body;
    }

    private MvcResult putValue(String key, User as, String value) throws Exception {
        return doPut(SETTINGS + "/" + key, as, value(value));
    }

    private JsonNode putOk(String key, String value) throws Exception {
        MvcResult result = putValue(key, admin, value);
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result);
    }

    private MvcResult putRaw(String key, User as, String rawBody) throws Exception {
        MockHttpServletRequestBuilder builder = put(SETTINGS + "/" + key).header("Authorization", tokenFor(as));
        if (rawBody != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(rawBody);
        }
        return mockMvc.perform(builder).andReturn();
    }

    private JsonNode listAsAdmin() throws Exception {
        MvcResult result = doGet(SETTINGS, admin);
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode body = json(result);
        assertTrue(body.isArray(), "GET /api/admin/settings returns a plain array");
        return body;
    }

    private JsonNode setting(String key) throws Exception {
        for (JsonNode item : listAsAdmin()) {
            if (key.equals(item.path("key").asText())) {
                return item;
            }
        }
        return fail("no setting " + key + " in the list");
    }

    private JsonNode publicSettings(User as) throws Exception {
        MvcResult result = doGet(PUBLIC, as);
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result);
    }

    /** The field names of a JSON object, sorted (the order of fields in a response is not part of the contract). */
    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        Iterator<String> it = node.fieldNames();
        it.forEachRemaining(names::add);
        return names.stream().sorted().toList();
    }

    private static List<String> sorted(List<String> names) {
        return names.stream().sorted().toList();
    }

    private int storedRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM system_settings", Integer.class);
    }

    private List<Map<String, Object>> settingAudits() {
        return jdbc.queryForList("SELECT * FROM audit_log WHERE action = 'SETTINGS_UPDATE' ORDER BY audit_id");
    }

    private void assertError(MvcResult result, int status, String message, String code) throws Exception {
        assertEquals(status, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode body = json(result);
        assertEquals(message, body.path("message").asText());
        assertEquals(status, body.path("status").asInt());
        assertTrue(body.hasNonNull("timestamp") && body.hasNonNull("error") && body.hasNonNull("path"),
                "standard error envelope expected: " + body);
        if (code == null) {
            assertFalse(body.has("code"), "unexpected code in " + body);
        } else {
            assertEquals(code, body.path("code").asText());
        }
    }

    /** A value the service rejects by the type of the key: code VALIDATION_FAILED, no errors[]. */
    private void assertRejectedByType(String key, String value, String reason) throws Exception {
        MvcResult result = putValue(key, admin, value);
        assertError(result, 400, "value: " + reason, "VALIDATION_FAILED");
        assertFalse(json(result).has("errors"), "type errors carry no errors[]: " + json(result));
        assertEquals(SETTINGS + "/" + key, json(result).path("path").asText());
    }

    // ==================== WHO MAY CALL ====================

    @Test
    void adminRoutes_areForbiddenForPatientDoctorAndCaregiver() throws Exception {
        putOk("clinic.name", "Harare Family Clinic");
        int auditsBefore = settingAudits().size();
        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));

        for (User caller : List.of(patient, doctor, caregiver, otherPatient, otherDoctor, otherCaregiver)) {
            assertError(doGet(SETTINGS, caller), 403, FORBIDDEN, null);
            assertError(putValue("clinic.name", caller, "Taken Over"), 403, FORBIDDEN, null);
            assertError(putValue("billing.tax-rate", caller, "1"), 403, FORBIDDEN, null);
            assertError(doDelete(SETTINGS + "/clinic.name", caller), 403, FORBIDDEN, null);
            // The role is decided before the body or the key are looked at.
            assertError(putRaw("clinic.name", caller, "{not json"), 403, FORBIDDEN, null);
            assertError(putValue("no.such.key", caller, ""), 403, FORBIDDEN, null);
            assertError(doDelete(SETTINGS + "/no.such.key", caller), 403, FORBIDDEN, null);
        }

        assertEquals("Harare Family Clinic", jdbc.queryForObject("SELECT value FROM system_settings", String.class));
        assertEquals(1, storedRows());
        assertEquals(auditsBefore, settingAudits().size());
    }

    @Test
    void everyRoute_answers401WithoutAToken_andWithAnInvalidOne() throws Exception {
        List<MockHttpServletRequestBuilder> anonymous = List.of(
                get(SETTINGS),
                put(SETTINGS + "/clinic.name").contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"Anonymous\"}"),
                delete(SETTINGS + "/clinic.name"),
                get(PUBLIC));
        for (MockHttpServletRequestBuilder request : anonymous) {
            assertError(mockMvc.perform(request).andReturn(), 401, UNAUTHENTICATED, null);
        }

        List<MockHttpServletRequestBuilder> invalidToken = List.of(
                get(SETTINGS).header("Authorization", "Bearer not-a-token"),
                put(SETTINGS + "/clinic.name").header("Authorization", "Bearer not-a-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"Anonymous\"}"),
                delete(SETTINGS + "/clinic.name").header("Authorization", "Bearer not-a-token"),
                get(PUBLIC).header("Authorization", "Bearer not-a-token"));
        for (MockHttpServletRequestBuilder request : invalidToken) {
            assertError(mockMvc.perform(request).andReturn(), 401, UNAUTHENTICATED, null);
        }
        assertEquals(0, storedRows());
    }

    @Test
    void publicRoute_givesEveryRoleTheSameAnswer_whateverTheCaregiversGrants() throws Exception {
        JsonNode expected = publicSettings(admin);
        assertEquals(sorted(List.of("clinicName", "supportEmail", "supportPhone", "clinicTimeZone", "openHour",
                "closeHour", "allowWeekends")), fieldNames(expected));
        // Nothing configured: the documented defaults, contacts sent as JSON null (present, not omitted).
        assertEquals("CareNexus", expected.path("clinicName").asText());
        assertTrue(expected.get("supportEmail").isNull());
        assertTrue(expected.get("supportPhone").isNull());
        assertEquals("Africa/Harare", expected.path("clinicTimeZone").asText());
        assertTrue(expected.get("openHour").isInt());
        assertEquals(8, expected.path("openHour").asInt());
        assertEquals(18, expected.path("closeHour").asInt());
        assertTrue(expected.get("allowWeekends").isBoolean());
        assertTrue(expected.path("allowWeekends").asBoolean());

        // Same hours and zone as the booking rules every client already reads.
        JsonNode bookingRules = json(doGet("/api/appointments/booking-rules", patient));
        assertEquals(bookingRules.path("timeZone").asText(), expected.path("clinicTimeZone").asText());
        assertEquals(bookingRules.path("openHour").asInt(), expected.path("openHour").asInt());
        assertEquals(bookingRules.path("closeHour").asInt(), expected.path("closeHour").asInt());
        assertEquals(bookingRules.path("allowWeekends").asBoolean(), expected.path("allowWeekends").asBoolean());

        // caregiver: no grant at all; otherCaregiver: an expired grant, then a revoked one.
        grantAccess(otherCaregiver, otherPatient, a -> {
            a.setCanViewRecords(true);
            a.setExpiresAt(LocalDateTime.now().minusDays(1));
        });
        for (User caller : List.of(patient, doctor, caregiver, otherPatient, otherDoctor, otherCaregiver)) {
            assertEquals(expected, publicSettings(caller), caller.getSystemId());
        }
        grantAccess(otherCaregiver, otherPatient, a -> a.setIsActive(false));
        assertEquals(expected, publicSettings(otherCaregiver));
        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        assertEquals(expected, publicSettings(caregiver));

        // Reading it is not audited and writes nothing.
        assertEquals(0, storedRows());
        assertTrue(settingAudits().isEmpty());
    }

    // ==================== LIST ====================

    @Test
    void list_returnsTheNineSettingsInOrder_withDefaultsTypesAndLimits() throws Exception {
        JsonNode list = listAsAdmin();

        assertEquals(9, list.size());
        List<String> keys = new ArrayList<>();
        for (JsonNode item : list) {
            keys.add(item.path("key").asText());
            // Every field is present; nullable ones are JSON null, never omitted.
            assertEquals(sorted(SETTING_FIELDS), fieldNames(item), item.toString());
            assertTrue(item.get("value").isTextual(), "values travel as JSON strings: " + item);
            assertTrue(item.get("defaultValue").isTextual());
            assertEquals(item.path("defaultValue").asText(), item.path("value").asText());
            assertTrue(item.get("overridden").isBoolean());
            assertFalse(item.path("overridden").asBoolean());
            assertTrue(item.get("updatedAt").isNull());
            assertTrue(item.get("updatedBySystemId").isNull());
            assertFalse(item.path("description").asText().isBlank());
        }
        assertEquals(KEYS, keys);

        assertSetting(list.get(0), "STRING", "CareNexus", null, null, 120,
                "Name of the clinic shown on Help & Support and on printed invoices and receipts.");
        assertSetting(list.get(1), "EMAIL", "", null, null, 254,
                "Support email address shown on Help & Support.");
        assertSetting(list.get(2), "PHONE", "", null, null, 30,
                "Support phone number shown on Help & Support.");
        assertSetting(list.get(3), "INT", "24", 1, 168, null,
                "Hours before an appointment at which the first reminder is sent.");
        assertSetting(list.get(4), "INT", "60", 5, 720, null,
                "Minutes before an appointment at which the second reminder is sent.");
        assertSetting(list.get(5), "INT", "30", 5, 1440, null,
                "Minutes after the end of an appointment before it is listed as needing an outcome.");
        assertSetting(list.get(6), "CURRENCY", "KES", null, null, 3,
                "Currency of new invoices (ISO 4217 code).");
        assertSetting(list.get(7), "INT", "14", 0, 365, null,
                "Days from issue until a new invoice is due.");
        assertSetting(list.get(8), "DECIMAL", "0", 0, 1, null,
                "Tax rate applied to new invoices, as a fraction (0.16 = 16%).");

        // Unknown query parameters are ignored; the list is never paged.
        MvcResult withParams = doGet(SETTINGS + "?page=3&size=1&foo=bar", admin);
        assertEquals(200, withParams.getResponse().getStatus());
        assertEquals(list, json(withParams));
        // Reading the list is not audited.
        assertTrue(settingAudits().isEmpty());
    }

    private static void assertSetting(JsonNode item, String type, String defaultValue, Integer min, Integer max,
                                      Integer maxLength, String description) {
        String key = item.path("key").asText();
        assertEquals(type, item.path("type").asText(), key);
        assertEquals(defaultValue, item.path("defaultValue").asText(), key);
        assertEquals(description, item.path("description").asText(), key);
        assertNumberOrNull(item.get("min"), min, key + " min");
        assertNumberOrNull(item.get("max"), max, key + " max");
        assertNumberOrNull(item.get("maxLength"), maxLength, key + " maxLength");
    }

    private static void assertNumberOrNull(JsonNode node, Integer expected, String what) {
        if (expected == null) {
            assertTrue(node.isNull(), what + " should be null");
        } else {
            assertTrue(node.isNumber(), what + " should be a JSON number");
            assertEquals(0, node.decimalValue().compareTo(BigDecimal.valueOf(expected)), what);
            assertEquals(expected.toString(), node.asText(), what + " must be written as a plain number");
        }
    }

    // ==================== ROUND TRIP ====================

    @Test
    void roundTrip_putThenListThenPublicThenDelete() throws Exception {
        JsonNode put = putOk("clinic.name", "  Harare Family Clinic ");

        assertEquals(sorted(SETTING_FIELDS), fieldNames(put));
        assertEquals("clinic.name", put.path("key").asText());
        assertEquals("Harare Family Clinic", put.path("value").asText());
        assertEquals("CareNexus", put.path("defaultValue").asText());
        assertTrue(put.path("overridden").asBoolean());
        assertEquals("STRING", put.path("type").asText());
        assertEquals(admin.getSystemId(), put.path("updatedBySystemId").asText());
        // Clinic wall-clock date-time without offset.
        assertTrue(put.path("updatedAt").asText().matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?$"),
                put.path("updatedAt").asText());
        LocalDateTime updatedAt = LocalDateTime.parse(put.path("updatedAt").asText());
        assertTrue(Math.abs(java.time.Duration.between(updatedAt, LocalDateTime.now()).toSeconds()) < 60,
                "updatedAt is the clinic wall-clock time of the write");

        putOk("support.contact-email", "help@harareclinic.example");
        putOk("support.contact-phone", "+263 24 270 0000");

        // The admin reads their own write at once, and the list item equals the PUT answer.
        assertEquals(put, setting("clinic.name"));
        assertEquals("help@harareclinic.example", setting("support.contact-email").path("value").asText());
        assertEquals(3, storedRows());

        // Help & Support follows, for a user who is not an administrator.
        JsonNode shown = publicSettings(patient);
        assertEquals("Harare Family Clinic", shown.path("clinicName").asText());
        assertEquals("help@harareclinic.example", shown.path("supportEmail").asText());
        assertEquals("+263 24 270 0000", shown.path("supportPhone").asText());

        // Restore the defaults: 204 with an empty body.
        for (String key : List.of("clinic.name", "support.contact-email", "support.contact-phone")) {
            MvcResult deleted = doDelete(SETTINGS + "/" + key, admin);
            assertEquals(204, deleted.getResponse().getStatus());
            assertEquals("", deleted.getResponse().getContentAsString());
        }
        assertEquals(0, storedRows());

        JsonNode restored = setting("clinic.name");
        assertEquals("CareNexus", restored.path("value").asText());
        assertFalse(restored.path("overridden").asBoolean());
        assertTrue(restored.get("updatedAt").isNull());
        assertTrue(restored.get("updatedBySystemId").isNull());

        JsonNode shownAfter = publicSettings(caregiver);
        assertEquals("CareNexus", shownAfter.path("clinicName").asText());
        assertTrue(shownAfter.get("supportEmail").isNull());
        assertTrue(shownAfter.get("supportPhone").isNull());
    }

    @Test
    void put_storesTheCanonicalFormOfEachType() throws Exception {
        assertEquals("0.16", putOk("billing.tax-rate", "0.1600").path("value").asText());
        assertEquals("1", putOk("billing.tax-rate", "1.0").path("value").asText());
        assertEquals("0", putOk("billing.tax-rate", "0.0").path("value").asText());
        assertEquals("USD", putOk("billing.default-currency", "usd").path("value").asText());
        assertEquals("24", putOk("reminders.appointment-lead-hours", "024").path("value").asText());
        assertEquals("Support@Clinic.example", putOk("support.contact-email", " Support@Clinic.example ").path("value").asText());
        assertEquals("(024) 270-0000", putOk("support.contact-phone", " (024) 270-0000").path("value").asText());

        // The limits are inclusive.
        assertEquals("1", putOk("reminders.appointment-lead-hours", "1").path("value").asText());
        assertEquals("168", putOk("reminders.appointment-lead-hours", "168").path("value").asText());
        assertEquals("5", putOk("reminders.second-lead-minutes", "5").path("value").asText());
        assertEquals("720", putOk("reminders.second-lead-minutes", "720").path("value").asText());
        assertEquals("1440", putOk("appointments.needs-outcome-after-minutes", "1440").path("value").asText());
        assertEquals("365", putOk("billing.payment-terms-days", "365").path("value").asText());
        assertEquals("0", putOk("billing.payment-terms-days", "0").path("value").asText());
        assertEquals("x".repeat(120), putOk("clinic.name", "x".repeat(120)).path("value").asText());

        // What is stored is the canonical text.
        assertEquals("USD", jdbc.queryForObject(
                "SELECT value FROM system_settings WHERE setting_key = 'billing.default-currency'", String.class));
        assertEquals("0", jdbc.queryForObject(
                "SELECT value FROM system_settings WHERE setting_key = 'billing.tax-rate'", String.class));
        assertEquals("24", setting("reminders.appointment-lead-hours").path("defaultValue").asText());
    }

    @Test
    void put_keyIsCaseInsensitive_unknownBodyFieldsAreIgnored_andAValueEqualToTheDefaultStillOverrides() throws Exception {
        MvcResult mixedCase = putRaw("Billing.Tax-Rate", admin, "{\"value\":\"0.16\",\"comment\":\"ignored\",\"key\":\"clinic.name\"}");
        assertEquals(200, mixedCase.getResponse().getStatus(), mixedCase.getResponse().getContentAsString());
        assertEquals("billing.tax-rate", json(mixedCase).path("key").asText());
        assertEquals("billing.tax-rate", jdbc.queryForObject("SELECT setting_key FROM system_settings", String.class));

        JsonNode sameAsDefault = putOk("billing.default-currency", "KES");
        assertEquals("KES", sameAsDefault.path("value").asText());
        assertEquals("KES", sameAsDefault.path("defaultValue").asText());
        assertTrue(sameAsDefault.path("overridden").asBoolean());
        assertEquals(2, storedRows());

        assertEquals(204, doDelete(SETTINGS + "/BILLING.TAX-RATE", admin).getResponse().getStatus());
        assertEquals(1, storedRows());
    }

    @Test
    void put_alwaysWrites_andASecondAdministratorReplacesAuthorAndTime() throws Exception {
        User secondAdmin = createAndSaveUser(8, "ADM-00002", "second.admin@example.com", "ADMIN");

        JsonNode first = putOk("billing.payment-terms-days", "30");
        MvcResult repeated = putValue("billing.payment-terms-days", secondAdmin, "30");
        assertEquals(200, repeated.getResponse().getStatus(), repeated.getResponse().getContentAsString());
        JsonNode second = json(repeated);

        assertEquals("30", second.path("value").asText());
        assertEquals("ADM-00001", first.path("updatedBySystemId").asText());
        assertEquals("ADM-00002", second.path("updatedBySystemId").asText());
        assertFalse(LocalDateTime.parse(second.path("updatedAt").asText())
                .isBefore(LocalDateTime.parse(first.path("updatedAt").asText())));
        assertEquals(1, storedRows());
        assertEquals("ADM-00002", jdbc.queryForObject("SELECT updated_by_system_id FROM system_settings", String.class));
        // An unchanged value is still a write, and still audited.
        assertEquals(2, settingAudits().size());
    }

    // ==================== 400 ENVELOPES ====================

    @Test
    void put_blankMissingOrOversizedValue_isABeanValidationErrorWithErrorsArray() throws Exception {
        Map<String, Object> nullValue = value(null);
        List<MvcResult> blank = List.of(
                putValue("clinic.name", admin, ""),
                putValue("clinic.name", admin, "   "),
                doPut(SETTINGS + "/clinic.name", admin, nullValue),
                putRaw("clinic.name", admin, "{}"),
                // The body is validated before the key is looked up.
                putValue("no.such.key", admin, " "));
        for (MvcResult result : blank) {
            assertError(result, 400, "value: must not be blank", "VALIDATION_FAILED");
            JsonNode errors = json(result).path("errors");
            assertEquals(1, errors.size(), errors.toString());
            assertEquals("value", errors.get(0).path("field").asText());
            assertEquals("must not be blank", errors.get(0).path("message").asText());
        }

        MvcResult tooLong = putValue("clinic.name", admin, "x".repeat(501));
        assertError(tooLong, 400, "value: must be at most 500 characters", "VALIDATION_FAILED");
        JsonNode errors = json(tooLong).path("errors");
        assertEquals(1, errors.size(), errors.toString());
        assertEquals("value", errors.get(0).path("field").asText());
        assertEquals("must be at most 500 characters", errors.get(0).path("message").asText());

        assertEquals(0, storedRows());
        assertTrue(settingAudits().isEmpty());
    }

    @Test
    void put_malformedOrMissingBody_is400() throws Exception {
        assertError(putRaw("clinic.name", admin, "{not json"), 400, "Malformed request body", null);
        assertError(putRaw("clinic.name", admin, "\"just a string\""), 400, "Malformed request body", null);
        assertError(putRaw("clinic.name", admin, ""), 400, "Malformed request body", null);
        assertEquals(0, storedRows());
    }

    @Test
    void putAndDelete_unknownKey_is400NotFoundIsNeverUsed() throws Exception {
        for (String key : List.of("billing.vat", "BILLING_TAX_RATE", "billing", "clinic.name.extra", "billing.tax_rate")) {
            assertError(putValue(key, admin, "1"), 400, "Unknown setting key", null);
            assertError(doDelete(SETTINGS + "/" + key, admin), 400, "Unknown setting key", null);
        }
        assertEquals(0, storedRows());
        assertTrue(settingAudits().isEmpty());
    }

    @Test
    void put_valueInvalidForTheTypeOfTheKey_is400WithTheReason() throws Exception {
        // STRING
        assertRejectedByType("clinic.name", "x".repeat(121), "must be at most 120 characters");
        // EMAIL
        assertRejectedByType("support.contact-email", "not-an-address", "must be a valid email address");
        assertRejectedByType("support.contact-email", "a".repeat(250) + "@b.co", "must be a valid email address");
        // PHONE
        assertRejectedByType("support.contact-phone", "call us", "must be a valid phone number");
        assertRejectedByType("support.contact-phone", "12345", "must be a valid phone number");
        // CURRENCY
        assertRejectedByType("billing.default-currency", "KE", "must be an ISO 4217 currency code");
        assertRejectedByType("billing.default-currency", "QQQ", "must be an ISO 4217 currency code");
        assertRejectedByType("billing.default-currency", "Kenyan shilling", "must be an ISO 4217 currency code");
        // INT: not a whole number
        assertRejectedByType("reminders.appointment-lead-hours", "12.5", "must be a whole number");
        assertRejectedByType("reminders.appointment-lead-hours", "abc", "must be a whole number");
        // INT: outside the range of the key
        assertRejectedByType("reminders.appointment-lead-hours", "0", "must be between 1 and 168");
        assertRejectedByType("reminders.appointment-lead-hours", "169", "must be between 1 and 168");
        assertRejectedByType("reminders.second-lead-minutes", "4", "must be between 5 and 720");
        assertRejectedByType("reminders.second-lead-minutes", "721", "must be between 5 and 720");
        assertRejectedByType("appointments.needs-outcome-after-minutes", "4", "must be between 5 and 1440");
        assertRejectedByType("appointments.needs-outcome-after-minutes", "1441", "must be between 5 and 1440");
        assertRejectedByType("billing.payment-terms-days", "-1", "must be between 0 and 365");
        assertRejectedByType("billing.payment-terms-days", "366", "must be between 0 and 365");
        // DECIMAL
        assertRejectedByType("billing.tax-rate", "sixteen", "must be a number");
        assertRejectedByType("billing.tax-rate", "0.16%", "must be a number");
        assertRejectedByType("billing.tax-rate", "0.16001", "must have at most 4 decimal places");
        assertRejectedByType("billing.tax-rate", "1.01", "must be between 0 and 1");
        assertRejectedByType("billing.tax-rate", "-0.1", "must be between 0 and 1");
        assertRejectedByType("billing.tax-rate", "16", "must be between 0 and 1");

        assertEquals(0, storedRows());
        assertTrue(settingAudits().isEmpty());
        // A rejected value never replaces a good one.
        putOk("billing.tax-rate", "0.16");
        assertRejectedByType("billing.tax-rate", "2", "must be between 0 and 1");
        assertEquals("0.16", setting("billing.tax-rate").path("value").asText());
    }

    // ==================== DELETE ====================

    @Test
    void delete_withoutAnOverride_isANoOp204_andIsNotAudited() throws Exception {
        MvcResult first = doDelete(SETTINGS + "/billing.tax-rate", admin);
        assertEquals(204, first.getResponse().getStatus());
        assertEquals("", first.getResponse().getContentAsString());

        putOk("billing.tax-rate", "0.16");
        assertEquals(204, doDelete(SETTINGS + "/billing.tax-rate", admin).getResponse().getStatus());
        // Idempotent: the repeat answers the same and changes nothing.
        assertEquals(204, doDelete(SETTINGS + "/billing.tax-rate", admin).getResponse().getStatus());

        assertEquals(0, storedRows());
        List<Map<String, Object>> audits = settingAudits();
        assertEquals(2, audits.size(), "one row for the PUT, one for the DELETE that removed the override");
        assertEquals("PUT", audits.get(0).get("http_method"));
        assertEquals("DELETE", audits.get(1).get("http_method"));
    }

    // ==================== AUDIT ====================

    @Test
    void successfulPutAndDelete_writeAuditRowsThatNameTheKeyButNeverTheValue() throws Exception {
        putOk("support.contact-email", "secret-contact@clinic.example");
        assertEquals(204, doDelete(SETTINGS + "/Support.Contact-Email", admin).getResponse().getStatus());

        List<Map<String, Object>> audits = settingAudits();
        assertEquals(2, audits.size());
        for (Map<String, Object> audit : audits) {
            assertEquals("SETTINGS_UPDATE", audit.get("action"));
            assertEquals("SYSTEM_SETTING", audit.get("resource_type"));
            assertEquals(admin.getSystemId(), audit.get("system_id"));
            assertEquals("ADMIN", audit.get("actor_role"));
            assertEquals("SUCCESS", audit.get("status"));
            assertNull(audit.get("target_system_id"));
            assertNull(audit.get("patient_system_id"));
            assertNull(audit.get("resource_id"));
            assertNotNull(audit.get("timestamp"));
            assertFalse(audit.values().stream().anyMatch(v -> v != null && v.toString().contains("secret-contact")),
                    "the value must never be written to the audit log: " + audit);
        }
        assertEquals("PUT", audits.get(0).get("http_method"));
        assertEquals("/api/admin/settings/support.contact-email", audits.get(0).get("request_path"));
        assertEquals("DELETE", audits.get(1).get("http_method"));
        assertEquals("/api/admin/settings/Support.Contact-Email", audits.get(1).get("request_path"));

        // The administrator finds them in the audit log by action and resource type.
        MvcResult search = doGet("/api/admin/audit-logs?action=SETTINGS_UPDATE&resourceType=SYSTEM_SETTING", admin);
        assertEquals(200, search.getResponse().getStatus(), search.getResponse().getContentAsString());
        JsonNode page = json(search);
        assertEquals(2, page.path("totalElements").asInt());
        assertEquals("/api/admin/settings/Support.Contact-Email", page.path("content").get(0).path("requestPath").asText());
    }

    // ==================== READERS ====================

    @Test
    void typedReaders_followEveryChangeMadeThroughTheApi() throws Exception {
        assertEquals(24, systemSettingsService.getInt(SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS));
        assertEquals(60, systemSettingsService.getInt(SettingKey.REMINDERS_SECOND_LEAD_MINUTES));
        assertEquals(30, systemSettingsService.getInt(SettingKey.APPOINTMENTS_NEEDS_OUTCOME_AFTER_MINUTES));
        assertEquals("KES", systemSettingsService.getString(SettingKey.BILLING_DEFAULT_CURRENCY));
        assertEquals(14, systemSettingsService.getInt(SettingKey.BILLING_PAYMENT_TERMS_DAYS));
        assertEquals(0, systemSettingsService.getDecimal(SettingKey.BILLING_TAX_RATE).signum());

        putOk("reminders.appointment-lead-hours", "48");
        putOk("reminders.second-lead-minutes", "90");
        putOk("appointments.needs-outcome-after-minutes", "45");
        putOk("billing.default-currency", "usd");
        putOk("billing.payment-terms-days", "30");
        putOk("billing.tax-rate", "0.1600");

        assertEquals(48, systemSettingsService.getInt(SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS));
        assertEquals(90, systemSettingsService.getInt(SettingKey.REMINDERS_SECOND_LEAD_MINUTES));
        assertEquals(45, systemSettingsService.getInt(SettingKey.APPOINTMENTS_NEEDS_OUTCOME_AFTER_MINUTES));
        assertEquals("USD", systemSettingsService.getString(SettingKey.BILLING_DEFAULT_CURRENCY));
        assertEquals(30, systemSettingsService.getInt(SettingKey.BILLING_PAYMENT_TERMS_DAYS));
        assertEquals(new BigDecimal("0.16"), systemSettingsService.getDecimal(SettingKey.BILLING_TAX_RATE));

        for (String key : KEYS) {
            assertEquals(204, doDelete(SETTINGS + "/" + key, admin).getResponse().getStatus());
        }
        assertEquals(24, systemSettingsService.getInt(SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS));
        assertEquals("KES", systemSettingsService.getString(SettingKey.BILLING_DEFAULT_CURRENCY));
        assertEquals(0, systemSettingsService.getDecimal(SettingKey.BILLING_TAX_RATE).signum());
    }

    @Test
    void aSettingChangedByOneTest_neverReachesTheNext_partOne() throws Exception {
        assertSettingsAreUntouchedThenChangeOne();
    }

    @Test
    void aSettingChangedByOneTest_neverReachesTheNext_partTwo() throws Exception {
        assertSettingsAreUntouchedThenChangeOne();
    }

    /** Whichever of the two tests runs second proves that the table was emptied and that no value was cached. */
    private void assertSettingsAreUntouchedThenChangeOne() throws Exception {
        assertEquals(0, storedRows());
        assertEquals("CareNexus", systemSettingsService.getString(SettingKey.CLINIC_NAME));
        assertEquals("CareNexus", publicSettings(patient).path("clinicName").asText());
        putOk("clinic.name", "Left Behind Clinic");
        assertEquals("Left Behind Clinic", systemSettingsService.getString(SettingKey.CLINIC_NAME));
    }
}
