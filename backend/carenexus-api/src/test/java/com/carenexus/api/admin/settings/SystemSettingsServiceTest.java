package com.carenexus.api.admin.settings;

import com.carenexus.api.BaseIntegrationTest;
import com.carenexus.api.admin.settings.dto.response.SystemSettingResponse;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.model.AuditLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SystemSettingsService against the real schema (V33): typed readers over the table, the cache bypass of the
 * test profile, eviction of the cache by update and reset, what update and reset write (row, audit), and the
 * ADMIN-only rule of the admin operations. Each test runs in a transaction that is rolled back.
 */
public class SystemSettingsServiceTest extends BaseIntegrationTest {

    private static final String LEAD_HOURS = "reminders.appointment-lead-hours";

    @Autowired
    private SystemSettingsService settings;

    @Autowired
    private JdbcTemplate jdbc;

    private User admin;
    private User patient;
    private User doctor;
    private User caregiver;

    @BeforeEach
    void createUsers() {
        admin = createAndSaveUser(901, "ADM-00901", "settings.admin@example.com", "ADMIN");
        patient = createAndSaveUser(902, "PAT-00901", "settings.patient@example.com", "PATIENT");
        doctor = createAndSaveUser(903, "DOC-00901", "settings.doctor@example.com", "DOCTOR");
        caregiver = createAndSaveUser(904, "CGV-00901", "settings.caregiver@example.com", "CAREGIVER");
    }

    @AfterEach
    void restoreCacheSwitch() {
        settings.setCacheEnabled(false);
        clearAuthentication();
    }

    private void writeRow(String key, String value) {
        jdbc.update("INSERT INTO system_settings (setting_key, value, updated_by_system_id, updated_at) "
                + "VALUES (?, ?, ?, ?) ON CONFLICT (setting_key) DO UPDATE SET value = EXCLUDED.value",
                key, value, admin.getSystemId(), Timestamp.valueOf(LocalDateTime.now()));
    }

    private int rowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM system_settings", Integer.class);
    }

    private List<AuditLog> settingAudits() {
        return auditLogRepository.findAll().stream()
                .filter(row -> "SETTINGS_UPDATE".equals(row.getAction()))
                .toList();
    }

    // ==================== READERS ====================

    @Test
    void emptyTable_readersReturnTheDefaults_andNeverThrow() {
        assertEquals(0, rowCount());
        for (SettingKey key : SettingKey.values()) {
            assertNotNull(settings.getString(key), key + " has no effective value");
        }
        assertEquals("CareNexus", settings.getString(SettingKey.CLINIC_NAME));
        assertEquals(24, settings.getInt(SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS));
        assertEquals(60, settings.getInt(SettingKey.REMINDERS_SECOND_LEAD_MINUTES));
        assertEquals(30, settings.getInt(SettingKey.APPOINTMENTS_NEEDS_OUTCOME_AFTER_MINUTES));
        assertEquals("KES", settings.getString(SettingKey.BILLING_DEFAULT_CURRENCY));
        assertEquals(14, settings.getInt(SettingKey.BILLING_PAYMENT_TERMS_DAYS));
        assertEquals(0, settings.getDecimal(SettingKey.BILLING_TAX_RATE).signum());
    }

    @Test
    void testProfile_bypassesTheCache_soADirectTableWriteIsSeenOnTheNextCall() {
        assertFalse(settings.isCacheEnabled(), "the cache must be off under the test profile");
        assertEquals(24, settings.getInt(SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS));

        writeRow(LEAD_HOURS, "48");
        assertEquals(48, settings.getInt(SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS));

        writeRow(LEAD_HOURS, "72");
        assertEquals(72, settings.getInt(SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS));

        writeRow("billing.tax-rate", "0.16");
        assertEquals(new BigDecimal("0.16"), settings.getDecimal(SettingKey.BILLING_TAX_RATE));

        // What truncating the table between tests does: the default is back at once.
        jdbc.update("DELETE FROM system_settings");
        assertEquals(24, settings.getInt(SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS));
        assertEquals(0, settings.getDecimal(SettingKey.BILLING_TAX_RATE).signum());
    }

    @Test
    void readers_needNoSignedInUser() {
        clearAuthentication();
        writeRow("clinic.name", "Harare Family Clinic");
        assertEquals("Harare Family Clinic", settings.getString(SettingKey.CLINIC_NAME));
    }

    // ==================== CACHE EVICTION ====================

    @Test
    void cacheOn_updateEvictsIt_andResetEvictsIt() {
        authenticateAs(admin);
        settings.setCacheEnabled(true);

        assertEquals(24, settings.getInt(SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS));
        // Proof that the cache is really serving: a write behind its back is not seen.
        writeRow(LEAD_HOURS, "48");
        assertEquals(24, settings.getInt(SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS));

        settings.update(LEAD_HOURS, "72");
        assertEquals(72, settings.getInt(SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS), "update must evict the cache");

        writeRow(LEAD_HOURS, "96");
        assertEquals(72, settings.getInt(SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS));

        assertTrue(settings.reset(LEAD_HOURS));
        assertEquals(24, settings.getInt(SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS), "reset must evict the cache");

        writeRow(LEAD_HOURS, "100");
        assertEquals(24, settings.getInt(SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS));
    }

    @Test
    void cacheOn_updateOfOneKeyRefreshesEveryKey_andListAlwaysReadsTheTable() {
        authenticateAs(admin);
        settings.setCacheEnabled(true);
        assertEquals("CareNexus", settings.getString(SettingKey.CLINIC_NAME));

        writeRow("clinic.name", "Written Directly");
        assertEquals("CareNexus", settings.getString(SettingKey.CLINIC_NAME));
        // The admin list is not served from the cache.
        SystemSettingResponse listed = settings.list().get(0);
        assertEquals("Written Directly", listed.value());
        assertTrue(listed.overridden());
        assertEquals("CareNexus", settings.getString(SettingKey.CLINIC_NAME), "list must not touch the cache");

        settings.update("billing.payment-terms-days", "30");
        assertEquals("Written Directly", settings.getString(SettingKey.CLINIC_NAME));
        assertEquals(30, settings.getInt(SettingKey.BILLING_PAYMENT_TERMS_DAYS));
    }

    // ==================== UPDATE / RESET ====================

    @Test
    void update_writesTheCanonicalRowWithAuthorAndTime_andOneAuditRowPerCall() {
        authenticateAs(admin);

        SystemSettingResponse first = settings.update(" Billing.Default-Currency ", " usd ");

        assertEquals("billing.default-currency", first.key());
        assertEquals("USD", first.value());
        assertEquals("KES", first.defaultValue());
        assertTrue(first.overridden());
        assertEquals(admin.getSystemId(), first.updatedBySystemId());
        assertNotNull(first.updatedAt());

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM system_settings");
        assertEquals("billing.default-currency", row.get("setting_key"));
        assertEquals("USD", row.get("value"));
        assertEquals(admin.getSystemId(), row.get("updated_by_system_id"));
        assertEquals(first.updatedAt(), ((Timestamp) row.get("updated_at")).toLocalDateTime(),
                "the response carries exactly the stored time");
        assertEquals("USD", settings.getString(SettingKey.BILLING_DEFAULT_CURRENCY));

        // An unchanged value is written and audited again; a second admin replaces the author.
        User secondAdmin = createAndSaveUser(905, "ADM-00902", "settings.admin2@example.com", "ADMIN");
        authenticateAs(secondAdmin);
        SystemSettingResponse second = settings.update("billing.default-currency", "USD");
        assertEquals(secondAdmin.getSystemId(), second.updatedBySystemId());
        assertFalse(second.updatedAt().isBefore(first.updatedAt()));
        assertEquals(1, rowCount());
        assertEquals(secondAdmin.getSystemId(),
                jdbc.queryForObject("SELECT updated_by_system_id FROM system_settings", String.class));

        List<AuditLog> audits = settingAudits();
        assertEquals(2, audits.size());
        for (AuditLog audit : audits) {
            assertEquals("SYSTEM_SETTING", audit.getResourceType());
            assertEquals("ADMIN", audit.getActorRole());
            assertEquals("SUCCESS", audit.getStatus());
            assertNull(audit.getTargetSystemId());
            assertNull(audit.getPatientSystemId());
            assertNull(audit.getResourceId());
            assertNull(audit.getErrorMessage(), "the value must never reach the audit log");
        }
        assertEquals(List.of(admin.getSystemId(), secondAdmin.getSystemId()),
                audits.stream().map(AuditLog::getSystemId).sorted().toList());
    }

    @Test
    void update_invalidValueOrUnknownKey_writesNothing() {
        authenticateAs(admin);

        assertEquals("value: must be between 0 and 1",
                assertThrows(BadRequestException.class, () -> settings.update("billing.tax-rate", "1.5")).getMessage());
        assertEquals("value: must not be blank",
                assertThrows(BadRequestException.class, () -> settings.update("clinic.name", "   ")).getMessage());
        assertEquals("Unknown setting key",
                assertThrows(BadRequestException.class, () -> settings.update("clinic.title", "x")).getMessage());

        assertEquals(0, rowCount());
        assertTrue(settingAudits().isEmpty());
    }

    @Test
    void reset_removesTheOverride_auditsOnce_andIsANoOpWithoutOne() {
        authenticateAs(admin);
        settings.update("clinic.name", "Harare Family Clinic");
        assertEquals(1, settingAudits().size());

        assertTrue(settings.reset("clinic.name"));
        assertEquals(0, rowCount());
        assertEquals("CareNexus", settings.getString(SettingKey.CLINIC_NAME));
        assertEquals(2, settingAudits().size());

        assertFalse(settings.reset("clinic.name"));
        assertEquals(2, settingAudits().size(), "a reset that removed nothing is not audited");

        assertEquals("Unknown setting key",
                assertThrows(BadRequestException.class, () -> settings.reset("CLINIC_NAME")).getMessage());
    }

    @Test
    void list_showsDefaultsThenOverrides_inKeyOrder() {
        authenticateAs(admin);

        List<SystemSettingResponse> before = settings.list();
        assertEquals(9, before.size());
        for (int i = 0; i < before.size(); i++) {
            SystemSettingResponse item = before.get(i);
            SettingKey key = SettingKey.values()[i];
            assertEquals(key.key(), item.key());
            assertEquals(item.defaultValue(), item.value());
            assertFalse(item.overridden());
            assertNull(item.updatedAt());
            assertNull(item.updatedBySystemId());
            assertEquals(key.type(), item.type());
            assertEquals(key.description(), item.description());
        }

        settings.update("billing.tax-rate", "0.1600");
        SystemSettingResponse tax = settings.list().get(8);
        assertEquals("0.16", tax.value());
        assertEquals("0", tax.defaultValue());
        assertTrue(tax.overridden());
        assertEquals(admin.getSystemId(), tax.updatedBySystemId());
        assertNotNull(tax.updatedAt());
    }

    // ==================== AUTHORIZATION ====================

    @Test
    void adminOperations_areRefusedForEveryOtherRole_andWhenNobodyIsSignedIn() {
        writeRow("clinic.name", "Harare Family Clinic");

        for (User caller : List.of(patient, doctor, caregiver)) {
            authenticateAs(caller);
            assertThrows(AuthorizationException.class, () -> settings.list(), caller.getRole());
            assertThrows(AuthorizationException.class, () -> settings.update("clinic.name", "Mine"), caller.getRole());
            assertThrows(AuthorizationException.class, () -> settings.reset("clinic.name"), caller.getRole());
        }
        clearAuthentication();
        assertThrows(AuthorizationException.class, () -> settings.list());
        assertThrows(AuthorizationException.class, () -> settings.update("clinic.name", "Mine"));
        assertThrows(AuthorizationException.class, () -> settings.reset("clinic.name"));

        assertEquals("Harare Family Clinic", jdbc.queryForObject("SELECT value FROM system_settings", String.class));
        assertTrue(settingAudits().isEmpty());
    }

    @Test
    void publicSettings_areTheSameForEveryRole() {
        writeRow("clinic.name", "Harare Family Clinic");
        writeRow("support.contact-email", "help@clinic.example");

        for (User caller : List.of(patient, doctor, caregiver, admin)) {
            authenticateAs(caller);
            var response = settings.publicSettings();
            assertEquals("Harare Family Clinic", response.clinicName(), caller.getRole());
            assertEquals("help@clinic.example", response.supportEmail());
            assertNull(response.supportPhone());
            assertEquals("Africa/Harare", response.clinicTimeZone());
            assertEquals(8, response.openHour());
            assertEquals(18, response.closeHour());
            assertTrue(response.allowWeekends());
        }
    }
}
