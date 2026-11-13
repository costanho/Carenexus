package com.carenexus.api.admin.settings;

import com.carenexus.api.admin.settings.dto.response.PublicSettingsResponse;
import com.carenexus.api.admin.settings.dto.response.SystemSettingResponse;
import com.carenexus.api.admin.settings.repository.StoredSetting;
import com.carenexus.api.admin.settings.repository.SystemSettingRepository;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.ratelimit.MutableClock;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.InsufficientAuthenticationException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * SystemSettingsService without a database: the resolution order (stored value, else application property, else
 * the default in code), the 60 second cache with its eviction and its bypass under the test profile, and the
 * guards of the admin operations. The repository, AuthorizationService and AuditService are mocks; the
 * environment and the clock are test doubles.
 */
public class SystemSettingsServiceFallbackTest {

    private static final LocalDateTime WRITTEN_AT = LocalDateTime.of(2026, 11, 10, 14, 5, 9);

    private SystemSettingRepository repository;
    private AuthorizationService authorizationService;
    private AuditService auditService;
    private MutableClock clock;
    /** What the mocked table holds; tests change it to simulate writes made behind the service's back. */
    private final List<StoredSetting> table = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repository = mock(SystemSettingRepository.class);
        when(repository.findAllStored()).thenAnswer(invocation -> List.copyOf(table));
        when(repository.upsert(anyString(), anyString(), anyString(), any())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            table.removeIf(row -> row.settingKey().equals(key));
            table.add(new StoredSetting(key, invocation.getArgument(1), invocation.getArgument(2), invocation.getArgument(3)));
            return 1;
        });
        when(repository.deleteByKey(anyString())).thenAnswer(invocation ->
                table.removeIf(row -> row.settingKey().equals(invocation.getArgument(0))) ? 1 : 0);
        authorizationService = mock(AuthorizationService.class);
        when(authorizationService.currentSystemId()).thenReturn("ADM-00001");
        auditService = mock(AuditService.class);
        clock = new MutableClock(Instant.parse("2026-11-10T12:00:00Z"));
    }

    /** A service as it runs in a deployed environment: no test profile, so the cache is on. */
    private SystemSettingsService service(MockEnvironment environment) {
        return new SystemSettingsService(repository, authorizationService, auditService, environment, clock);
    }

    private SystemSettingsService serviceUnderTestProfile(MockEnvironment environment) {
        environment.setActiveProfiles("test");
        return service(environment);
    }

    private void store(SettingKey key, String value) {
        table.removeIf(row -> row.settingKey().equals(key.key()));
        table.add(new StoredSetting(key.key(), value, "ADM-00001", WRITTEN_AT));
    }

    // ==================== FALLBACK ORDER ====================

    @Test
    void nothingStoredAndNoProperty_everyKeyReturnsItsCodeDefault() {
        SystemSettingsService settings = service(new MockEnvironment());

        assertEquals("CareNexus", settings.getString(SettingKey.CLINIC_NAME));
        assertEquals("", settings.getString(SettingKey.SUPPORT_CONTACT_EMAIL));
        assertEquals("", settings.getString(SettingKey.SUPPORT_CONTACT_PHONE));
        assertEquals(24, settings.getInt(SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS));
        assertEquals(60, settings.getInt(SettingKey.REMINDERS_SECOND_LEAD_MINUTES));
        assertEquals(30, settings.getInt(SettingKey.APPOINTMENTS_NEEDS_OUTCOME_AFTER_MINUTES));
        assertEquals("KES", settings.getString(SettingKey.BILLING_DEFAULT_CURRENCY));
        assertEquals(14, settings.getInt(SettingKey.BILLING_PAYMENT_TERMS_DAYS));
        assertEquals(0, settings.getDecimal(SettingKey.BILLING_TAX_RATE).compareTo(BigDecimal.ZERO));
    }

    @Test
    void applicationProperty_winsOverTheCodeDefault_forEveryKey() {
        SystemSettingsService settings = service(new MockEnvironment()
                .withProperty("carenexus.clinic.name", "Harare Family Clinic")
                .withProperty("carenexus.support.contact-email", "help@clinic.example")
                .withProperty("carenexus.support.contact-phone", "+263 24 270 0000")
                .withProperty("carenexus.reminders.appointment-lead-hours", "48")
                .withProperty("carenexus.reminders.second-lead-minutes", "90")
                .withProperty("carenexus.scheduling.needs-outcome-after-minutes", "45")
                .withProperty("carenexus.billing.default-currency", "usd")
                .withProperty("carenexus.billing.payment-terms-days", "30")
                .withProperty("carenexus.billing.tax-rate", "0.1500"));

        assertEquals("Harare Family Clinic", settings.getString(SettingKey.CLINIC_NAME));
        assertEquals("help@clinic.example", settings.getString(SettingKey.SUPPORT_CONTACT_EMAIL));
        assertEquals("+263 24 270 0000", settings.getString(SettingKey.SUPPORT_CONTACT_PHONE));
        assertEquals(48, settings.getInt(SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS));
        assertEquals(90, settings.getInt(SettingKey.REMINDERS_SECOND_LEAD_MINUTES));
        assertEquals(45, settings.getInt(SettingKey.APPOINTMENTS_NEEDS_OUTCOME_AFTER_MINUTES));
        // Properties are normalised like stored values.
        assertEquals("USD", settings.getString(SettingKey.BILLING_DEFAULT_CURRENCY));
        assertEquals(30, settings.getInt(SettingKey.BILLING_PAYMENT_TERMS_DAYS));
        assertEquals("0.15", settings.getString(SettingKey.BILLING_TAX_RATE));
        assertEquals(new BigDecimal("0.15"), settings.getDecimal(SettingKey.BILLING_TAX_RATE));
    }

    @Test
    void storedValue_winsOverThePropertyAndTheDefault_andRemovingItFallsBackInOrder() {
        SystemSettingsService settings = serviceUnderTestProfile(new MockEnvironment()
                .withProperty("carenexus.reminders.appointment-lead-hours", "48"));

        // property over default
        assertEquals(48, settings.getInt(SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS));
        // stored over property
        store(SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS, "72");
        assertEquals(72, settings.getInt(SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS));
        // stored over default (no property for this key)
        assertEquals(60, settings.getInt(SettingKey.REMINDERS_SECOND_LEAD_MINUTES));
        store(SettingKey.REMINDERS_SECOND_LEAD_MINUTES, "15");
        assertEquals(15, settings.getInt(SettingKey.REMINDERS_SECOND_LEAD_MINUTES));
        // row gone: back to the property, respectively the default; never an exception
        table.clear();
        assertEquals(48, settings.getInt(SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS));
        assertEquals(60, settings.getInt(SettingKey.REMINDERS_SECOND_LEAD_MINUTES));
    }

    @Test
    void supportContacts_readTheContactPropertyFirst_thenTheLegacyOne() {
        SystemSettingsService legacyOnly = service(new MockEnvironment()
                .withProperty("carenexus.support.email", "legacy@clinic.example")
                .withProperty("carenexus.support.phone", "+263 77 000 0000"));
        assertEquals("legacy@clinic.example", legacyOnly.getString(SettingKey.SUPPORT_CONTACT_EMAIL));
        assertEquals("+263 77 000 0000", legacyOnly.getString(SettingKey.SUPPORT_CONTACT_PHONE));

        SystemSettingsService both = service(new MockEnvironment()
                .withProperty("carenexus.support.contact-email", "new@clinic.example")
                .withProperty("carenexus.support.email", "legacy@clinic.example")
                .withProperty("carenexus.support.contact-phone", "+263 24 270 0000")
                .withProperty("carenexus.support.phone", "+263 77 000 0000"));
        assertEquals("new@clinic.example", both.getString(SettingKey.SUPPORT_CONTACT_EMAIL));
        assertEquals("+263 24 270 0000", both.getString(SettingKey.SUPPORT_CONTACT_PHONE));

        // A blank contact property (the shipped default) does not hide the legacy one.
        SystemSettingsService blankFirst = service(new MockEnvironment()
                .withProperty("carenexus.support.contact-email", "  ")
                .withProperty("carenexus.support.email", "legacy@clinic.example"));
        assertEquals("legacy@clinic.example", blankFirst.getString(SettingKey.SUPPORT_CONTACT_EMAIL));
    }

    @Test
    void invalidOrBlankProperty_isIgnored_andTheCodeDefaultApplies() {
        SystemSettingsService settings = service(new MockEnvironment()
                .withProperty("carenexus.clinic.name", "   ")
                .withProperty("carenexus.support.contact-email", "not-an-address")
                .withProperty("carenexus.reminders.appointment-lead-hours", "500")
                .withProperty("carenexus.reminders.second-lead-minutes", "soon")
                .withProperty("carenexus.scheduling.needs-outcome-after-minutes", "1")
                .withProperty("carenexus.billing.default-currency", "QQQ")
                .withProperty("carenexus.billing.payment-terms-days", "-1")
                .withProperty("carenexus.billing.tax-rate", "16"));

        assertEquals("CareNexus", settings.getString(SettingKey.CLINIC_NAME));
        assertEquals("", settings.getString(SettingKey.SUPPORT_CONTACT_EMAIL));
        assertEquals(24, settings.getInt(SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS));
        assertEquals(60, settings.getInt(SettingKey.REMINDERS_SECOND_LEAD_MINUTES));
        assertEquals(30, settings.getInt(SettingKey.APPOINTMENTS_NEEDS_OUTCOME_AFTER_MINUTES));
        assertEquals("KES", settings.getString(SettingKey.BILLING_DEFAULT_CURRENCY));
        assertEquals(14, settings.getInt(SettingKey.BILLING_PAYMENT_TERMS_DAYS));
        assertEquals("0", settings.getString(SettingKey.BILLING_TAX_RATE));
    }

    @Test
    void theOldLeadMinutesProperty_isNotRead() {
        SystemSettingsService settings = service(new MockEnvironment()
                .withProperty("carenexus.reminders.appointment-lead-minutes", "2880"));
        assertEquals(24, settings.getInt(SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS));
    }

    @Test
    void invalidStoredRow_andRowsOfUnknownKeys_areIgnored_soReadersStayInsideTheRange() {
        SystemSettingsService settings = serviceUnderTestProfile(new MockEnvironment()
                .withProperty("carenexus.billing.payment-terms-days", "30"));
        store(SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS, "9000");
        store(SettingKey.BILLING_PAYMENT_TERMS_DAYS, "tomorrow");
        store(SettingKey.BILLING_TAX_RATE, "");
        table.add(new StoredSetting("retired.setting", "x", null, WRITTEN_AT));

        assertEquals(24, settings.getInt(SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS));
        assertEquals(30, settings.getInt(SettingKey.BILLING_PAYMENT_TERMS_DAYS));
        assertEquals(0, settings.getDecimal(SettingKey.BILLING_TAX_RATE).signum());
    }

    @Test
    void storedValue_isReturnedInCanonicalForm_evenWhenTheRowWasWrittenByHand() {
        SystemSettingsService settings = serviceUnderTestProfile(new MockEnvironment());
        store(SettingKey.BILLING_DEFAULT_CURRENCY, " usd ");
        store(SettingKey.BILLING_TAX_RATE, "0.1600");
        store(SettingKey.BILLING_PAYMENT_TERMS_DAYS, "007");

        assertEquals("USD", settings.getString(SettingKey.BILLING_DEFAULT_CURRENCY));
        assertEquals("0.16", settings.getString(SettingKey.BILLING_TAX_RATE));
        assertEquals(new BigDecimal("0.16"), settings.getDecimal(SettingKey.BILLING_TAX_RATE));
        assertEquals(7, settings.getInt(SettingKey.BILLING_PAYMENT_TERMS_DAYS));
        assertEquals("7", settings.getString(SettingKey.BILLING_PAYMENT_TERMS_DAYS));
    }

    // ==================== TYPED GETTERS ====================

    @Test
    void getDecimal_alsoReadsIntKeys_andGettersRefuseAKeyOfTheWrongType() {
        SystemSettingsService settings = service(new MockEnvironment());

        assertEquals(new BigDecimal("14"), settings.getDecimal(SettingKey.BILLING_PAYMENT_TERMS_DAYS));
        assertThrows(IllegalArgumentException.class, () -> settings.getInt(SettingKey.CLINIC_NAME));
        assertThrows(IllegalArgumentException.class, () -> settings.getInt(SettingKey.BILLING_TAX_RATE));
        assertThrows(IllegalArgumentException.class, () -> settings.getDecimal(SettingKey.BILLING_DEFAULT_CURRENCY));
        assertThrows(NullPointerException.class, () -> settings.getString(null));
    }

    // ==================== CACHE ====================

    @Test
    void cache_servesStoredValuesFor60Seconds_thenReadsTheTableAgain() {
        SystemSettingsService settings = service(new MockEnvironment());
        assertTrue(settings.isCacheEnabled());
        store(SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS, "48");

        assertEquals(48, settings.getInt(SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS));
        assertEquals("CareNexus", settings.getString(SettingKey.CLINIC_NAME));
        verify(repository, times(1)).findAllStored();

        // A write behind the service's back stays invisible while the snapshot is fresh...
        store(SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS, "72");
        clock.advance(Duration.ofSeconds(59));
        assertEquals(48, settings.getInt(SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS));
        verify(repository, times(1)).findAllStored();

        // ...and is picked up once the 60 seconds are over.
        clock.advance(Duration.ofSeconds(1));
        assertEquals(72, settings.getInt(SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS));
        verify(repository, times(2)).findAllStored();
    }

    @Test
    void cache_isEvictedByUpdate_andByReset() {
        SystemSettingsService settings = service(new MockEnvironment());
        assertEquals(24, settings.getInt(SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS));

        settings.update("reminders.appointment-lead-hours", "48");
        assertEquals(48, settings.getInt(SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS), "update must evict the cache");

        settings.reset("reminders.appointment-lead-hours");
        assertEquals(24, settings.getInt(SettingKey.REMINDERS_APPOINTMENT_LEAD_HOURS), "reset must evict the cache");

        // No clock movement: without the evictions both reads above would have returned the first snapshot.
        verify(repository, times(3)).findAllStored();
    }

    @Test
    void cache_isBypassedUnderTheTestProfile_soATruncatedTableIsNeverMasked() {
        SystemSettingsService settings = serviceUnderTestProfile(new MockEnvironment());
        assertFalse(settings.isCacheEnabled());

        store(SettingKey.BILLING_DEFAULT_CURRENCY, "USD");
        assertEquals("USD", settings.getString(SettingKey.BILLING_DEFAULT_CURRENCY));
        table.clear();   // what the committed test base does between tests
        assertEquals("KES", settings.getString(SettingKey.BILLING_DEFAULT_CURRENCY));
        verify(repository, times(2)).findAllStored();
    }

    // ==================== ADMIN OPERATIONS ====================

    @Test
    void update_storesTheCanonicalValue_auditsWithoutTheValue_andReturnsTheSetting() {
        SystemSettingsService settings = service(new MockEnvironment());

        SystemSettingResponse response = settings.update(" Billing.Tax-Rate ", " 0.1600 ");

        LocalDateTime now = LocalDateTime.now(clock);
        verify(repository).upsert("billing.tax-rate", "0.16", "ADM-00001", now);
        verify(auditService).logAccountEvent("ADM-00001", "SETTINGS_UPDATE", "SYSTEM_SETTING", null, "SUCCESS");
        verifyNoMoreInteractions(auditService);

        assertEquals("billing.tax-rate", response.key());
        assertEquals("0.16", response.value());
        assertEquals("0", response.defaultValue());
        assertTrue(response.overridden());
        assertEquals(SettingType.DECIMAL, response.type());
        assertEquals("Tax rate applied to new invoices, as a fraction (0.16 = 16%).", response.description());
        assertEquals(0, response.min().intValueExact());
        assertEquals(1, response.max().intValueExact());
        assertNull(response.maxLength());
        assertEquals(now, response.updatedAt());
        assertEquals("ADM-00001", response.updatedBySystemId());
    }

    @Test
    void update_rejectsAnUnknownKeyAndAnInvalidValue_withoutWritingOrAuditing() {
        SystemSettingsService settings = service(new MockEnvironment());

        BadRequestException unknown = assertThrows(BadRequestException.class, () -> settings.update("billing.vat", "1"));
        assertEquals("Unknown setting key", unknown.getMessage());
        assertNull(unknown.getCode());
        assertEquals("Unknown setting key",
                assertThrows(BadRequestException.class, () -> settings.update("BILLING_TAX_RATE", "0.1")).getMessage());

        BadRequestException invalid = assertThrows(BadRequestException.class,
                () -> settings.update("reminders.appointment-lead-hours", "169"));
        assertEquals("value: must be between 1 and 168", invalid.getMessage());
        assertEquals("VALIDATION_FAILED", invalid.getCode());

        verify(repository, never()).upsert(anyString(), anyString(), anyString(), any());
        verifyNoInteractions(auditService);
    }

    @Test
    void reset_auditsOnlyWhenAnOverrideWasRemoved() {
        SystemSettingsService settings = service(new MockEnvironment());

        assertFalse(settings.reset("clinic.name"));
        verifyNoInteractions(auditService);

        store(SettingKey.CLINIC_NAME, "Harare Family Clinic");
        assertTrue(settings.reset("Clinic.Name"));
        verify(repository, times(2)).deleteByKey("clinic.name");
        verify(auditService).logAccountEvent("ADM-00001", "SETTINGS_UPDATE", "SYSTEM_SETTING", null, "SUCCESS");

        assertEquals("Unknown setting key",
                assertThrows(BadRequestException.class, () -> settings.reset("clinic")).getMessage());
    }

    @Test
    void list_returnsEveryKeyInOrder_withDefaultsFromPropertiesAndOverridesFromTheTable() {
        SystemSettingsService settings = service(new MockEnvironment()
                .withProperty("carenexus.clinic.name", "Property Clinic"));
        store(SettingKey.CLINIC_NAME, "Stored Clinic");
        store(SettingKey.BILLING_PAYMENT_TERMS_DAYS, "not a number");

        List<SystemSettingResponse> all = settings.list();

        assertEquals(SettingKey.values().length, all.size());
        for (int i = 0; i < all.size(); i++) {
            assertEquals(SettingKey.values()[i].key(), all.get(i).key());
        }
        SystemSettingResponse clinic = all.get(0);
        assertEquals("Stored Clinic", clinic.value());
        assertEquals("Property Clinic", clinic.defaultValue());
        assertTrue(clinic.overridden());
        assertEquals(WRITTEN_AT, clinic.updatedAt());
        assertEquals("ADM-00001", clinic.updatedBySystemId());
        assertEquals(120, clinic.maxLength());
        assertNull(clinic.min());
        assertNull(clinic.max());

        SystemSettingResponse email = all.get(1);
        assertEquals("", email.value());
        assertEquals("", email.defaultValue());
        assertFalse(email.overridden());
        assertNull(email.updatedAt());
        assertNull(email.updatedBySystemId());

        // An override that no longer satisfies the key: the default is in force, and the row can still be removed.
        SystemSettingResponse terms = all.get(7);
        assertEquals("14", terms.value());
        assertTrue(terms.overridden());
    }

    @Test
    void adminOperations_refuseANonAdmin_beforeTouchingTheTable() {
        doThrow(new AuthorizationException("Admin role required")).when(authorizationService).requireAdmin();
        SystemSettingsService settings = service(new MockEnvironment());

        assertThrows(AuthorizationException.class, settings::list);
        assertThrows(AuthorizationException.class, () -> settings.update("clinic.name", "Mine now"));
        assertThrows(AuthorizationException.class, () -> settings.reset("clinic.name"));
        // Also for a request that would be rejected anyway: the role is checked first.
        assertThrows(AuthorizationException.class, () -> settings.update("no.such.key", ""));

        verifyNoInteractions(repository);
        verifyNoInteractions(auditService);
    }

    // ==================== PUBLIC SETTINGS ====================

    @Test
    void publicSettings_combineTheSettingsWithTheSchedulingProperties_andHideBlankContacts() {
        SystemSettingsService defaults = service(new MockEnvironment());
        assertEquals(new PublicSettingsResponse("CareNexus", null, null, "Africa/Harare", 8, 18, true),
                defaults.publicSettings());

        SystemSettingsService configured = service(new MockEnvironment()
                .withProperty("carenexus.support.email", "help@clinic.example")
                .withProperty("carenexus.scheduling.time-zone", "Africa/Nairobi")
                .withProperty("carenexus.scheduling.open-hour", "7")
                .withProperty("carenexus.scheduling.close-hour", "20")
                .withProperty("carenexus.scheduling.allow-weekends", "false"));
        store(SettingKey.CLINIC_NAME, "Harare Family Clinic");
        store(SettingKey.SUPPORT_CONTACT_PHONE, "+263 24 270 0000");
        assertEquals(new PublicSettingsResponse("Harare Family Clinic", "help@clinic.example", "+263 24 270 0000",
                "Africa/Nairobi", 7, 20, false), configured.publicSettings());
    }

    @Test
    void publicSettings_requireASignedInCaller() {
        when(authorizationService.currentSystemId())
                .thenThrow(new InsufficientAuthenticationException("Authentication required"));
        SystemSettingsService settings = service(new MockEnvironment());

        assertThrows(InsufficientAuthenticationException.class, settings::publicSettings);
        verifyNoInteractions(repository);
    }
}
