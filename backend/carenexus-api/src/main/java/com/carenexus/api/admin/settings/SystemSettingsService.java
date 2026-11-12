package com.carenexus.api.admin.settings;

import com.carenexus.api.admin.settings.dto.response.PublicSettingsResponse;
import com.carenexus.api.admin.settings.dto.response.SystemSettingResponse;
import com.carenexus.api.admin.settings.repository.StoredSetting;
import com.carenexus.api.admin.settings.repository.SystemSettingRepository;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * System settings an administrator can change at runtime ({@link SettingKey}).
 *
 * <h3>Readers</h3>
 * {@link #getString}, {@link #getInt} and {@link #getDecimal} return the effective value of a key: the stored
 * override, else the application property of the key, else the default in code. They never throw for a missing
 * row and always return a value that satisfies the limits of the key (an invalid property or an invalid stored
 * row is ignored with a WARN). A reader never reads the matching application property itself.
 *
 * Stored overrides are cached for {@link #CACHE_TTL}. Every update and reset on this instance evicts the cache;
 * another API instance may serve the previous value until its own cache expires. When the {@code test} profile is
 * active the cache is bypassed, so a test that writes {@code system_settings} (through the admin endpoint or
 * directly) sees the change on the next call and a truncated table is never masked by a cached value.
 *
 * <h3>Administration</h3>
 * {@link #list}, {@link #update} and {@link #reset} are ADMIN only and always read and write the table directly.
 * A successful update, and a reset that removed an override, write one audit row (action {@code SETTINGS_UPDATE},
 * resource type {@code SYSTEM_SETTING}); the request path of that row names the key. Values are never written to
 * the audit log or to application logs.
 */
@Service
@Slf4j
public class SystemSettingsService {

    public static final String RESOURCE = "SYSTEM_SETTING";
    static final String AUDIT_ACTION = "SETTINGS_UPDATE";

    /** How long stored overrides are served from memory before the table is read again. */
    static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private static final String TIME_ZONE_PROPERTY = "carenexus.scheduling.time-zone";
    private static final String OPEN_HOUR_PROPERTY = "carenexus.scheduling.open-hour";
    private static final String CLOSE_HOUR_PROPERTY = "carenexus.scheduling.close-hour";
    private static final String ALLOW_WEEKENDS_PROPERTY = "carenexus.scheduling.allow-weekends";

    private final SystemSettingRepository repository;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final Environment environment;
    private final Clock clock;

    /** Value of every key when no override is stored: application property, else the default in code. Fixed at startup. */
    private final Map<SettingKey, String> defaults;

    /** False under the test profile. Mutable so the cache itself can be tested (see setCacheEnabled). */
    private volatile boolean cacheEnabled;

    /** Bumped by every eviction; a snapshot taken under an older version is never served. */
    private final AtomicLong cacheVersion = new AtomicLong();
    private volatile Snapshot snapshot;

    /** Keys whose invalid stored row has already been reported, so the WARN is not repeated on every read. */
    private final Set<SettingKey> reportedInvalidRows = ConcurrentHashMap.newKeySet();

    private record Snapshot(Map<SettingKey, String> stored, long loadedAtMillis, long version) {
    }

    public SystemSettingsService(SystemSettingRepository repository,
                                 AuthorizationService authorizationService,
                                 AuditService auditService,
                                 Environment environment,
                                 Clock clock) {
        this.repository = repository;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
        this.environment = environment;
        this.clock = clock;
        this.cacheEnabled = !environment.acceptsProfiles(Profiles.of("test"));

        Map<SettingKey, String> resolved = new EnumMap<>(SettingKey.class);
        for (SettingKey key : SettingKey.values()) {
            resolved.put(key, resolveDefault(key));
        }
        this.defaults = Collections.unmodifiableMap(resolved);
    }

    // ==================== READERS ====================

    /**
     * Effective value of any key as canonical text. Empty only for a support contact that is not configured.
     */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public String getString(SettingKey key) {
        return effective(Objects.requireNonNull(key, "key"));
    }

    /**
     * Effective value of an INT key, always inside the range of the key.
     *
     * @throws IllegalArgumentException when the key is not an INT setting (a programming error)
     */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public int getInt(SettingKey key) {
        requireType(key, SettingType.INT);
        return Integer.parseInt(effective(key));
    }

    /**
     * Effective value of a DECIMAL or INT key, always inside the range of the key. {@code BILLING_TAX_RATE} is a
     * fraction between 0 and 1 (0.16 means 16 %).
     *
     * @throws IllegalArgumentException when the key is neither a DECIMAL nor an INT setting (a programming error)
     */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public BigDecimal getDecimal(SettingKey key) {
        requireType(key, SettingType.DECIMAL, SettingType.INT);
        return new BigDecimal(effective(key));
    }

    // ==================== PUBLIC (ANY SIGNED-IN USER) ====================

    /**
     * Clinic name, support contact and clinic hours for the Help &amp; Support page. Any signed-in user may call
     * it; nothing in the answer depends on the caller. The clinic hours and time zone are the read-only scheduling
     * properties that the booking rules use.
     */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public PublicSettingsResponse publicSettings() {
        authorizationService.currentSystemId();   // 401 when nobody is signed in
        return new PublicSettingsResponse(
                getString(SettingKey.CLINIC_NAME),
                blankToNull(getString(SettingKey.SUPPORT_CONTACT_EMAIL)),
                blankToNull(getString(SettingKey.SUPPORT_CONTACT_PHONE)),
                clinicZone().getId(),
                environment.getProperty(OPEN_HOUR_PROPERTY, Integer.class, 8),
                environment.getProperty(CLOSE_HOUR_PROPERTY, Integer.class, 18),
                environment.getProperty(ALLOW_WEEKENDS_PROPERTY, Boolean.class, true));
    }

    // ==================== ADMINISTRATION ====================

    /**
     * Every setting, in {@link SettingKey} order, with its effective value, default and metadata. ADMIN only.
     * Reads the table directly, so an administrator always sees their own write.
     */
    @Transactional(readOnly = true)
    public List<SystemSettingResponse> list() {
        authorizationService.requireAdmin();
        Map<String, StoredSetting> rows = new HashMap<>();
        for (StoredSetting row : repository.findAllStored()) {
            rows.put(row.settingKey(), row);
        }
        List<SystemSettingResponse> result = new ArrayList<>(SettingKey.values().length);
        for (SettingKey key : SettingKey.values()) {
            result.add(toResponse(key, rows.get(key.key())));
        }
        return result;
    }

    /**
     * Stores (creates or replaces) the override of one setting. ADMIN only. Always writes, also when the value is
     * unchanged, evicts the cache and writes one audit row.
     *
     * @param keyName  dotted name of the setting (trimmed, case-insensitive)
     * @param rawValue the new value as text; validated against the type and limits of the key
     * @throws BadRequestException "Unknown setting key", or code VALIDATION_FAILED with "value: reason"
     */
    @Transactional
    public SystemSettingResponse update(String keyName, String rawValue) {
        authorizationService.requireAdmin();
        String callerSystemId = authorizationService.currentSystemId();
        SettingKey key = requireKey(keyName);
        String value = key.normalize(rawValue);

        // TIMESTAMP columns keep microseconds: return exactly what is stored.
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        repository.upsert(key.key(), value, callerSystemId, now);
        reportedInvalidRows.remove(key);
        evictNowAndAfterCompletion();

        auditService.logAccountEvent(callerSystemId, AUDIT_ACTION, RESOURCE, null, "SUCCESS");
        log.info("System setting {} updated by {}", key.key(), callerSystemId);
        return toResponse(key, new StoredSetting(key.key(), value, callerSystemId, now));
    }

    /**
     * Removes the override of one setting so its default applies again. ADMIN only. A setting without an override
     * is left as it is (no audit row); otherwise the cache is evicted and one audit row is written.
     *
     * @param keyName dotted name of the setting (trimmed, case-insensitive)
     * @return true when an override was removed
     * @throws BadRequestException "Unknown setting key"
     */
    @Transactional
    public boolean reset(String keyName) {
        authorizationService.requireAdmin();
        String callerSystemId = authorizationService.currentSystemId();
        SettingKey key = requireKey(keyName);

        boolean removed = repository.deleteByKey(key.key()) > 0;
        reportedInvalidRows.remove(key);
        evictNowAndAfterCompletion();
        if (removed) {
            auditService.logAccountEvent(callerSystemId, AUDIT_ACTION, RESOURCE, null, "SUCCESS");
            log.info("System setting {} restored to its default by {}", key.key(), callerSystemId);
        }
        return removed;
    }

    // ==================== CACHE ====================

    /**
     * Switches the cache of stored overrides on or off and drops what is cached. The service turns the cache off
     * by itself under the test profile; tests of the cache switch it on for themselves and off again afterwards.
     */
    void setCacheEnabled(boolean enabled) {
        this.cacheEnabled = enabled;
        evict();
    }

    boolean isCacheEnabled() {
        return cacheEnabled;
    }

    /** Drops the cached overrides; the next read loads the table. */
    void evict() {
        cacheVersion.incrementAndGet();
        snapshot = null;
    }

    /**
     * Evicts now, so the rest of the current transaction reads its own write, and again when the transaction ends,
     * so nothing cached while the write was still uncommitted (by this or by a concurrent reader) survives it.
     */
    private void evictNowAndAfterCompletion() {
        evict();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    evict();
                }
            });
        }
    }

    private String effective(SettingKey key) {
        String stored = storedOverrides().get(key);
        return stored != null ? stored : defaults.get(key);
    }

    private Map<SettingKey, String> storedOverrides() {
        if (!cacheEnabled) {
            return loadStoredOverrides();
        }
        long now = clock.millis();
        Snapshot current = snapshot;
        if (current != null && current.version() == cacheVersion.get()) {
            long age = now - current.loadedAtMillis();
            if (age >= 0 && age < CACHE_TTL.toMillis()) {
                return current.stored();
            }
        }
        // The version is read before the table: an eviction that happens while loading makes this snapshot stale.
        long version = cacheVersion.get();
        Map<SettingKey, String> stored = loadStoredOverrides();
        snapshot = new Snapshot(stored, now, version);
        return stored;
    }

    /** Valid stored overrides in canonical form. Rows of unknown keys and rows that fail validation are ignored. */
    private Map<SettingKey, String> loadStoredOverrides() {
        Map<SettingKey, String> stored = new EnumMap<>(SettingKey.class);
        for (StoredSetting row : repository.findAllStored()) {
            SettingKey key = SettingKey.fromKey(row.settingKey()).orElse(null);
            if (key == null) {
                continue;
            }
            String value = canonicalOrNull(key, row.value());
            if (value != null) {
                stored.put(key, value);
            }
        }
        return Collections.unmodifiableMap(stored);
    }

    /** The stored text in canonical form, or null (reported once per key) when it does not satisfy the key. */
    private String canonicalOrNull(SettingKey key, String storedValue) {
        try {
            return key.normalize(storedValue);
        } catch (BadRequestException invalid) {
            if (reportedInvalidRows.add(key)) {
                log.warn("Stored value of system setting {} is not valid ({}); using its default",
                        key.key(), reasonOf(invalid));
            }
            return null;
        }
    }

    // ==================== HELPERS ====================

    /**
     * The first application property of the key that holds a valid value, in canonical form; else the default in
     * code. A blank property is skipped silently; an invalid one is skipped with a WARN.
     */
    private String resolveDefault(SettingKey key) {
        for (String property : key.properties()) {
            String raw = environment.getProperty(property);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                return key.normalize(raw);
            } catch (BadRequestException invalid) {
                log.warn("Ignoring application property {} for system setting {} ({})",
                        property, key.key(), reasonOf(invalid));
            }
        }
        return key.codeDefault();
    }

    private SystemSettingResponse toResponse(SettingKey key, StoredSetting row) {
        String defaultValue = defaults.get(key);
        boolean overridden = row != null;
        String value = defaultValue;
        if (overridden) {
            String stored = canonicalOrNull(key, row.value());
            if (stored != null) {
                value = stored;
            }
        }
        return new SystemSettingResponse(
                key.key(),
                value,
                defaultValue,
                overridden,
                key.type(),
                key.description(),
                key.min(),
                key.max(),
                key.maxLength(),
                overridden ? row.updatedAt() : null,
                overridden ? row.updatedBySystemId() : null);
    }

    private static SettingKey requireKey(String keyName) {
        return SettingKey.fromKey(keyName).orElseThrow(() -> new BadRequestException("Unknown setting key"));
    }

    private static void requireType(SettingKey key, SettingType... allowed) {
        Objects.requireNonNull(key, "key");
        for (SettingType type : allowed) {
            if (key.type() == type) {
                return;
            }
        }
        throw new IllegalArgumentException("System setting " + key.key() + " is of type " + key.type());
    }

    /** Same rule as the booking rules: the configured clinic zone, else the JVM zone (which is set to it at startup). */
    private ZoneId clinicZone() {
        try {
            return ZoneId.of(environment.getProperty(TIME_ZONE_PROPERTY, "Africa/Harare"));
        } catch (RuntimeException invalidZone) {
            return ZoneId.systemDefault();
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** "value: must be between 1 and 168" becomes "must be between 1 and 168": the rule that failed, never the value. */
    private static String reasonOf(BadRequestException invalid) {
        String message = invalid.getMessage() == null ? "" : invalid.getMessage();
        return message.startsWith("value: ") ? message.substring("value: ".length()) : message;
    }
}
