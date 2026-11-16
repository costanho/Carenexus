package com.carenexus.api.admin.settings.repository;

import com.carenexus.api.admin.settings.model.SystemSetting;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Stored setting overrides. The table holds at most one row per {@code SettingKey}, so the list is naturally small
 * (hard cap: the number of keys).
 *
 * Reads return the {@link StoredSetting} projection, never a managed entity. Both writes are single statements, so
 * two administrators saving the same key at the same moment cannot collide on the primary key.
 */
@Transactional(readOnly = true)
public interface SystemSettingRepository extends Repository<SystemSetting, String> {

    /** Every stored override, ordered by key. */
    @Query("SELECT new com.carenexus.api.admin.settings.repository.StoredSetting("
            + "s.settingKey, s.value, s.updatedBySystemId, s.updatedAt) FROM SystemSetting s ORDER BY s.settingKey")
    List<StoredSetting> findAllStored();

    /** Creates the override or replaces its value, author and time. Returns the number of rows written (1). */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO system_settings (setting_key, value, updated_by_system_id, updated_at) "
            + "VALUES (:key, :value, :updatedBy, :updatedAt) "
            + "ON CONFLICT (setting_key) DO UPDATE SET value = EXCLUDED.value, "
            + "updated_by_system_id = EXCLUDED.updated_by_system_id, updated_at = EXCLUDED.updated_at",
            nativeQuery = true)
    int upsert(@Param("key") String key, @Param("value") String value,
               @Param("updatedBy") String updatedBySystemId, @Param("updatedAt") LocalDateTime updatedAt);

    /** Removes the override; returns 1 when a row existed, 0 otherwise. */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM SystemSetting s WHERE s.settingKey = :key")
    int deleteByKey(@Param("key") String key);
}
