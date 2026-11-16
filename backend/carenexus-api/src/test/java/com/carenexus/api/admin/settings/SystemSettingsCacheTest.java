package com.carenexus.api.admin.settings;

import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The settings cache as it runs outside tests (switched on for this class), with really committed requests:
 * PUT and DELETE evict it, and a write that is rolled back leaves nothing behind in it.
 */
public class SystemSettingsCacheTest extends CommittedIntegrationTest {

    private static final String LEAD_HOURS_URL = "/api/admin/settings/reminders.appointment-lead-hours";

    @Autowired
    private SystemSettingsService settings;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void switchCacheOn() {
        settings.setCacheEnabled(true);
    }

    @AfterEach
    void switchCacheOff() {
        settings.setCacheEnabled(false);
    }

    private void writeRowDirectly(String value) {
        jdbc.update("INSERT INTO system_settings (setting_key, value, updated_by_system_id, updated_at) "
                + "VALUES ('reminders.appointment-lead-hours', ?, ?, ?) "
                + "ON CONFLICT (setting_key) DO UPDATE SET value = EXCLUDED.value",
                value, admin.getSystemId(), Timestamp.valueOf(LocalDateTime.now()));
    }

    private int leadHours() {
        return settings.getInt(SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS);
    }

    @Test
    void putAndDelete_evictTheCache_whileDirectWritesStayHiddenUntilItExpires() throws Exception {
        assertEquals(24, leadHours());
        writeRowDirectly("36");
        assertEquals(24, leadHours(), "the cache is not serving: a direct write was seen at once");

        assertEquals(200, doPut(LEAD_HOURS_URL, admin, Map.of("value", "48")).getResponse().getStatus());
        assertEquals(48, leadHours(), "PUT must evict the cache");
        // Help & Support and every other reader share that cache.
        assertEquals(200, doPut("/api/admin/settings/clinic.name", admin, Map.of("value", "Harare Family Clinic"))
                .getResponse().getStatus());
        assertEquals("Harare Family Clinic", json(doGet("/api/settings/public", patient)).path("clinicName").asText());

        writeRowDirectly("72");
        assertEquals(48, leadHours());

        assertEquals(204, doDelete(LEAD_HOURS_URL, admin).getResponse().getStatus());
        assertEquals(24, leadHours(), "DELETE must evict the cache");

        // A DELETE that removes nothing still leaves the cache consistent with the table.
        assertEquals(204, doDelete(LEAD_HOURS_URL, admin).getResponse().getStatus());
        assertEquals(24, leadHours());
    }

    @Test
    void rejectedPut_doesNotChangeWhatReadersSee() throws Exception {
        assertEquals(200, doPut(LEAD_HOURS_URL, admin, Map.of("value", "48")).getResponse().getStatus());
        assertEquals(48, leadHours());

        assertEquals(400, doPut(LEAD_HOURS_URL, admin, Map.of("value", "169")).getResponse().getStatus());
        assertEquals(403, doPut(LEAD_HOURS_URL, patient, Map.of("value", "12")).getResponse().getStatus());
        assertEquals(48, leadHours());
    }

    @Test
    void rolledBackUpdate_leavesNoUncommittedValueInTheCache() {
        authenticateAs(admin);
        assertEquals(24, leadHours());

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            settings.update("reminders.appointment-lead-hours", "48");
            // Inside the transaction the writer reads its own write (and that read fills the cache).
            assertEquals(48, leadHours());
            status.setRollbackOnly();
        });

        assertEquals(24, leadHours(), "a value that was never committed must not be served from the cache");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM system_settings", Integer.class));
        assertTrue(jdbc.queryForList("SELECT audit_id FROM audit_log WHERE action = 'SETTINGS_UPDATE'").isEmpty(),
                "the audit row belongs to the change and rolls back with it");
    }
}
