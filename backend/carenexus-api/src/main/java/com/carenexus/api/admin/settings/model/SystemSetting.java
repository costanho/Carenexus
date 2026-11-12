package com.carenexus.api.admin.settings.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * An administrator's override of one system setting (table {@code system_settings}, V33). A setting without a row
 * uses its default. The primary key is the dotted name of the setting ({@code SettingKey.key()}).
 *
 * The entity maps the table for Hibernate's schema validation and for the repository's queries. Rows are written
 * by {@code SystemSettingRepository.upsert}, removed by {@code deleteByKey} and read as {@code StoredSetting}
 * projections, so no managed instance of this class is ever handed out.
 */
@Entity
@Table(name = "system_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SystemSetting {

    @Id
    @Column(name = "setting_key", nullable = false, length = 80)
    private String settingKey;

    /** Canonical text form of the value (see {@code SettingKey.normalize}). */
    @Column(name = "value", nullable = false, columnDefinition = "TEXT")
    private String value;

    /** Administrator who last wrote the override. */
    @Column(name = "updated_by_system_id", length = 20)
    private String updatedBySystemId;

    /** Clinic wall-clock time of the last write. */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
