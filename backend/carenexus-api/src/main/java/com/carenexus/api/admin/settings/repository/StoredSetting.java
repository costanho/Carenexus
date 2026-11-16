package com.carenexus.api.admin.settings.repository;

import java.time.LocalDateTime;

/**
 * One row of {@code system_settings} as plain values. Reads use this projection instead of the entity so they
 * always reflect the table, also when the row was changed earlier in the same transaction by a single-statement
 * write.
 *
 * @param settingKey        dotted name of the setting
 * @param value             stored text
 * @param updatedBySystemId administrator who last wrote the override (may be null)
 * @param updatedAt         clinic wall-clock time of the last write
 */
public record StoredSetting(String settingKey, String value, String updatedBySystemId, LocalDateTime updatedAt) {
}
