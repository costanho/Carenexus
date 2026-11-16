package com.carenexus.api.health.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One stored health reading (table {@code health_metrics}, V1 + V47). Readings are never edited; a manually
 * entered reading can be removed by the patient or by the person who recorded it.
 *
 * {@code recordedAt} is clinic wall-clock time. {@code severity} is computed by the server when the reading is
 * stored and kept with the row ({@code isAbnormal == !NORMAL}, enforced by a CHECK). The legacy integer
 * {@code patient_id} column is not mapped.
 */
@Entity
@Table(name = "health_metrics")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HealthMetric {

    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_WEARABLE = "WEARABLE";
    public static final String SOURCE_DEVICE = "DEVICE";
    public static final String SOURCE_INTEGRATION = "INTEGRATION";

    public static final String SEVERITY_NORMAL = "NORMAL";
    public static final String SEVERITY_ABNORMAL = "ABNORMAL";
    public static final String SEVERITY_CRITICAL = "CRITICAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "metric_id")
    private Integer metricId;

    @Column(name = "patient_system_id", nullable = false, length = 20)
    private String patientSystemId;

    @Column(name = "recorded_by_system_id", length = 20)
    private String recordedBySystemId;

    @Column(name = "reading_group_id", length = 36)
    private String readingGroupId;

    @Column(name = "type", nullable = false, length = 30)
    private String type;

    @Column(name = "value", nullable = false, precision = 10, scale = 3)
    private BigDecimal value;

    @Column(name = "unit", nullable = false, length = 30)
    private String unit;

    @Column(name = "source", nullable = false, length = 20)
    @Builder.Default
    private String source = SOURCE_MANUAL;

    @Column(name = "device_id", length = 100)
    private String deviceId;

    @Column(name = "is_abnormal", nullable = false)
    @Builder.Default
    private Boolean isAbnormal = false;

    @Column(name = "severity", nullable = false, length = 10)
    @Builder.Default
    private String severity = SEVERITY_NORMAL;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;
}
