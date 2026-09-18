package com.carenexus.api.core.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_settings", uniqueConstraints = {
    @UniqueConstraint(columnNames = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "setting_id")
    private Integer settingId;

    @Column(name = "user_id", nullable = false, unique = true)
    private Integer userId;

    @Column(name = "email_notifications", nullable = false)
    @Builder.Default
    private Boolean emailNotifications = true;

    @Column(name = "sms_notifications", nullable = false)
    @Builder.Default
    private Boolean smsNotifications = true;

    @Column(name = "push_notifications", nullable = false)
    @Builder.Default
    private Boolean pushNotifications = true;

    @Column(name = "appointment_reminders", nullable = false)
    @Builder.Default
    private Boolean appointmentReminders = true;

    @Column(name = "prescription_reminders", nullable = false)
    @Builder.Default
    private Boolean prescriptionReminders = true;

    @Column(name = "lab_result_alerts", nullable = false)
    @Builder.Default
    private Boolean labResultAlerts = true;

    @Column(name = "imaging_result_alerts", nullable = false)
    @Builder.Default
    private Boolean imagingResultAlerts = true;

    @Column(name = "consultation_notifications", nullable = false)
    @Builder.Default
    private Boolean consultationNotifications = true;

    @Column(name = "profile_visibility", nullable = false, length = 20)
    @Builder.Default
    private String profileVisibility = "PRIVATE";

    @Column(name = "data_sharing_enabled", nullable = false)
    @Builder.Default
    private Boolean dataSharingEnabled = false;

    @Column(name = "two_factor_enabled", nullable = false)
    @Builder.Default
    private Boolean twoFactorEnabled = false;

    @Column(name = "language", nullable = false, length = 10)
    @Builder.Default
    private String language = "en";

    @Column(name = "timezone", length = 50)
    @Builder.Default
    private String timezone = "UTC";

    @Column(name = "theme", nullable = false, length = 20)
    @Builder.Default
    private String theme = "LIGHT";

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
