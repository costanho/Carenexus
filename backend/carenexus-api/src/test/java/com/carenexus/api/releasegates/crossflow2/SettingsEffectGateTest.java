package com.carenexus.api.releasegates.crossflow2;

import com.carenexus.api.admin.settings.SettingKey;
import com.carenexus.api.billing.invoice.InvoiceFixtures;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import com.carenexus.api.reminders.auto.job.AppointmentReminderJob;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.carenexus.api.releasegates.crossflow2.CrossFlow2Fixtures.clinicNow;
import static com.carenexus.api.releasegates.crossflow2.CrossFlow2Fixtures.clinicToday;
import static com.carenexus.api.releasegates.crossflow2.CrossFlow2Fixtures.insertAppointment;
import static com.carenexus.api.releasegates.crossflow2.CrossFlow2Fixtures.withKeyPrefix;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-slice gate of the administrator settings (be-system-settings): a value changed through
 * {@code PUT /api/admin/settings/{key}} changes what its reader does in the module that owns it, on the next call and
 * without a restart. Reminder lead times move the automatic appointment reminders (be-reminder-jobs), the
 * needs-outcome threshold moves the doctor's list (be-doctor-schedule), currency, payment terms and tax rate shape the
 * next invoice (be-billing-invoices) and the billing configuration (be-billing-payments), and the clinic name and
 * support contacts reach the public settings payload and the printed receipt (be-billing-notify-receipt).
 * <p>
 * A source scan keeps the list honest: every {@link SettingKey} constant must have a reader outside the settings
 * plumbing, so a setting an administrator can change but nothing reads fails the build.
 */
class SettingsEffectGateTest extends CommittedIntegrationTest {

    private static final String SETTINGS = "/api/admin/settings/";
    private static final String INVOICES = "/api/billing/invoices";
    private static final String NEEDS_OUTCOME = "/api/doctors/appointments/needs-outcome";

    @Autowired
    private AppointmentReminderJob reminderJob;

    private InvoiceFixtures visits;

    @BeforeEach
    void setUpVisits() {
        visits = new InvoiceFixtures(jdbc);
    }

    @AfterEach
    void restoreFees() {
        visits.restoreDefaultFees();
    }

    // ==================== HELPERS ====================

    private void put(String key, String value) throws Exception {
        MvcResult result = doPut(SETTINGS + key, admin, Map.of("value", value));
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    private JsonNode ok(MvcResult result, int status) throws Exception {
        assertEquals(status, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result);
    }

    private static void assertMoney(String expected, JsonNode node) {
        assertEquals(0, new BigDecimal(expected).compareTo(node.decimalValue()), "was " + node);
    }

    /** A draft invoice the doctor creates for a new completed video visit (fee 1,500.00). */
    private JsonNode newInvoice() throws Exception {
        InvoiceFixtures.Visit visit = visits.completedVisit(patient, doctor, "VIDEO");
        return ok(doPost(INVOICES, doctor, Map.of("consultationId", visit.consultationId())), 201);
    }

    // ==================== REMINDERS ====================

    @Test
    void reminderLeadTimes_moveTheAutomaticAppointmentReminders() throws Exception {
        LocalDateTime now = clinicNow();
        int soon = insertAppointment(jdbc, patient.getSystemId(), doctor.getSystemId(), now.plusMinutes(80), 30,
                "IN_PERSON", "SCHEDULED");
        int later = insertAppointment(jdbc, patient.getSystemId(), doctor.getSystemId(), now.plusHours(30), 30,
                "IN_PERSON", "SCHEDULED");

        // Defaults (24 h and 60 min): the visit in 80 minutes gets its first reminder, the one in 30 hours nothing
        assertEquals(1, reminderJob.sweepOnce(now));
        assertEquals(0, reminderJob.sweepOnce(now));
        assertEquals(1, withKeyPrefix(jdbc, patient.getSystemId(), "APPT_REMINDER_24H:" + soon + ":"));
        assertEquals(0, withKeyPrefix(jdbc, patient.getSystemId(), "APPT_REMINDER_1H:" + soon + ":"));
        assertEquals(0, withKeyPrefix(jdbc, patient.getSystemId(), "APPT_REMINDER_24H:" + later + ":"));

        // Second reminder at 90 minutes: the visit in 80 minutes is now due for it
        put("reminders.second-lead-minutes", "90");
        assertEquals(1, reminderJob.sweepOnce(now));
        assertEquals(1, withKeyPrefix(jdbc, patient.getSystemId(), "APPT_REMINDER_1H:" + soon + ":"));

        // First reminder at 48 hours: the visit in 30 hours is now due for it
        put("reminders.appointment-lead-hours", "48");
        assertEquals(1, reminderJob.sweepOnce(now));
        assertEquals(1, withKeyPrefix(jdbc, patient.getSystemId(), "APPT_REMINDER_24H:" + later + ":"));
        assertEquals(0, reminderJob.sweepOnce(now));
    }

    // ==================== NEEDS OUTCOME ====================

    @Test
    void needsOutcomeThreshold_movesTheDoctorsList() throws Exception {
        // Ended 40 minutes ago (started 70 minutes ago, 30 minutes long)
        int ended = insertAppointment(jdbc, patient.getSystemId(), doctor.getSystemId(), clinicNow().minusMinutes(70),
                30, "IN_PERSON", "SCHEDULED");

        JsonNode byDefault = ok(doGet(NEEDS_OUTCOME, doctor), 200);
        assertEquals(1, byDefault.path("totalElements").asInt(), "30 minutes by default");
        assertEquals(ended, byDefault.path("content").get(0).path("appointmentId").asInt());

        put("appointments.needs-outcome-after-minutes", "60");
        assertEquals(0, ok(doGet(NEEDS_OUTCOME, doctor), 200).path("totalElements").asInt());

        put("appointments.needs-outcome-after-minutes", "35");
        assertEquals(1, ok(doGet(NEEDS_OUTCOME, doctor), 200).path("totalElements").asInt());
    }

    // ==================== BILLING ====================

    @Test
    void currencyTermsAndTax_shapeTheNextInvoice_andTheBillingConfiguration() throws Exception {
        LocalDate today = clinicToday();
        JsonNode before = newInvoice();
        assertEquals("KES", before.path("currency").asText());
        assertEquals(today.plusDays(14).toString(), before.path("dueDate").asText());
        assertMoney("0.00", before.path("tax"));
        assertMoney("1500.00", before.path("total"));
        assertEquals("KES", ok(doGet("/api/billing/config", patient), 200).path("currency").asText());

        put("billing.default-currency", "usd");
        put("billing.payment-terms-days", "30");
        put("billing.tax-rate", "0.16");

        JsonNode after = newInvoice();
        assertEquals("USD", after.path("currency").asText());
        assertEquals(today.plusDays(30).toString(), after.path("dueDate").asText());
        assertMoney("1500.00", after.path("subtotal"));
        assertMoney("240.00", after.path("tax"));
        assertMoney("1740.00", after.path("total"));
        assertEquals("USD", ok(doGet("/api/billing/config", patient), 200).path("currency").asText());

        // Issuing applies the terms in force at that moment
        put("billing.payment-terms-days", "7");
        JsonNode sent = ok(doPost(INVOICES + "/" + after.path("invoiceId").asInt() + "/send", doctor, null), 200);
        assertEquals(today.plusDays(7).toString(), sent.path("dueDate").asText());
        assertEquals("USD", sent.path("currency").asText(), "an existing invoice keeps its currency");

        // The invoice created before the change is unchanged
        JsonNode unchanged = ok(doGet(INVOICES + "/" + before.path("invoiceId").asInt(), doctor), 200);
        assertEquals("KES", unchanged.path("currency").asText());
        assertMoney("0.00", unchanged.path("tax"));
    }

    // ==================== PUBLIC SETTINGS AND RECEIPT ====================

    @Test
    void clinicNameAndSupportContacts_reachThePublicPayloadAndTheReceipt() throws Exception {
        JsonNode defaults = ok(doGet("/api/settings/public", caregiver), 200);
        assertEquals("CareNexus", defaults.path("clinicName").asText());

        put("clinic.name", "Harare Family Clinic");
        put("support.contact-email", "help@familyclinic.example");
        put("support.contact-phone", "+263 77 123 4567");

        for (var user : List.of(patient, doctor, caregiver, admin)) {
            JsonNode payload = ok(doGet("/api/settings/public", user), 200);
            assertEquals("Harare Family Clinic", payload.path("clinicName").asText());
            assertEquals("help@familyclinic.example", payload.path("supportEmail").asText(), payload.toString());
            assertEquals("+263 77 123 4567", payload.path("supportPhone").asText(), payload.toString());
        }

        JsonNode invoice = newInvoice();
        int invoiceId = invoice.path("invoiceId").asInt();
        ok(doPost(INVOICES + "/" + invoiceId + "/send", doctor, null), 200);
        MvcResult receipt = doGet(INVOICES + "/" + invoiceId + "/receipt", patient);
        assertEquals(200, receipt.getResponse().getStatus());
        String html = new String(receipt.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertTrue(html.contains("<p id=\"clinic-name\">Harare Family Clinic</p>"), html);
    }

    // ==================== SOURCE SCAN ====================

    /** Main sources of the API (Maven runs the tests from the module directory). */
    private static final Path MAIN_SOURCES = Paths.get("src", "main", "java", "com", "carenexus", "api");
    private static final Path SETTINGS_PACKAGE = MAIN_SOURCES.resolve(Paths.get("admin", "settings"));
    /** The public settings payload is itself a reader: GET /api/settings/public. */
    private static final Path SETTINGS_SERVICE = SETTINGS_PACKAGE.resolve("SystemSettingsService.java");
    private static final Pattern PUBLIC_READER = Pattern.compile(
            "public\\s+PublicSettingsResponse\\s+publicSettings\\s*\\(\\s*\\)\\s*\\{(.*?)\\n    }", Pattern.DOTALL);

    @Test
    void everySettingKeyHasAReaderOutsideTheSettingsPlumbing() throws IOException {
        assertTrue(Files.isDirectory(MAIN_SOURCES), "run from the module directory: " + MAIN_SOURCES.toAbsolutePath());
        assertTrue(Files.isRegularFile(SETTINGS_SERVICE), SETTINGS_SERVICE.toString());

        List<String> readerSources = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (!file.startsWith(SETTINGS_PACKAGE)) {
                    readerSources.add(Files.readString(file, StandardCharsets.UTF_8));
                }
            }
        }
        Matcher publicReader = PUBLIC_READER.matcher(Files.readString(SETTINGS_SERVICE, StandardCharsets.UTF_8));
        assertTrue(publicReader.find(), "SystemSettingsService.publicSettings() not found");
        readerSources.add(publicReader.group(1));

        Set<SettingKey> unread = EnumSet.noneOf(SettingKey.class);
        for (SettingKey key : SettingKey.values()) {
            Pattern qualified = Pattern.compile("\\bSettingKey\\s*\\.\\s*" + key.name() + "\\b");
            Pattern staticImport = Pattern.compile("import\\s+static\\s+com\\.carenexus\\.api\\.admin\\.settings\\."
                    + "SettingKey\\.(" + key.name() + "|\\*)\\s*;");
            Pattern bare = Pattern.compile("\\b" + key.name() + "\\b");
            boolean read = readerSources.stream().anyMatch(source -> qualified.matcher(source).find()
                    || (staticImport.matcher(source).find() && bare.matcher(source).find()));
            if (!read) {
                unread.add(key);
            }
        }
        assertTrue(unread.isEmpty(), "settings an administrator can change but nothing reads: " + unread);
    }
}
