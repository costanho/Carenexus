package com.carenexus.api.ai.alert.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One automated clinical alert about a patient (table {@code ai_alerts}, V1, re-keyed by V3, extended by V48).
 * <p>
 * Rows are written only by {@code AiAlertService.raise} (native INSERT ... ON CONFLICT DO NOTHING on
 * {@code dedupe_key}) and by the acknowledgement of the inbox service (conditional UPDATE); this entity is used for
 * reads. {@code doctorSystemId} null means the alert is addressed to every doctor related to the patient.
 * The database enforces the alert type, priority and source type sets and that {@code sourceType} and
 * {@code sourceId} are set together.
 */
@Entity
@Table(name = "ai_alerts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "alert_id")
    private Integer alertId;

    @Column(name = "patient_system_id", nullable = false, length = 20)
    private String patientSystemId;

    /** Addressed doctor; null = every doctor related to the patient. */
    @Column(name = "doctor_system_id", length = 20)
    private String doctorSystemId;

    /** {@code disease_detection.detection_id} when the alert came from a screening result. */
    @Column(name = "detection_id")
    private Integer detectionId;

    /** One of {@link AiAlertType}. */
    @Column(name = "alert_type", nullable = false, length = 30)
    private String alertType;

    /** Short heading; null on rows raised before V48 (the default title of the type is shown). */
    @Column(name = "title", length = 160)
    private String title;

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    /** One of {@link AiAlertPriority}. */
    @Column(name = "priority", nullable = false, length = 20)
    private String priority;

    @Column(name = "action_required", nullable = false)
    private Boolean actionRequired;

    @Column(name = "suggested_action", columnDefinition = "TEXT")
    private String suggestedAction;

    /** One of {@link AiAlertSourceType}, or null together with {@link #sourceId}. */
    @Column(name = "source_type", length = 30)
    private String sourceType;

    @Column(name = "source_id")
    private Integer sourceId;

    @Column(name = "is_acknowledged", nullable = false)
    private Boolean isAcknowledged;

    @Column(name = "acknowledged_by_system_id", length = 20)
    private String acknowledgedBySystemId;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    /** Reserved by V48; not written or exposed yet. */
    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "dedupe_key", length = 160, unique = true)
    private String dedupeKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (isAcknowledged == null) {
            isAcknowledged = false;
        }
        if (actionRequired == null) {
            actionRequired = false;
        }
    }
}
