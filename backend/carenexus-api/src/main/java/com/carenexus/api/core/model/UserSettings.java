package com.carenexus.api.core.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "user_settings", uniqueConstraints = {
    @UniqueConstraint(columnNames = "system_id")
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

    @Column(name = "system_id", nullable = false, unique = true, length = 20)
    private String systemId;

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

    /** UI key critical_alerts: critical results / emergencies */
    @Column(name = "critical_alerts", nullable = false)
    @Builder.Default
    private Boolean criticalAlerts = true;

    /** UI key medication_due: daily due-medication reminders */
    @Column(name = "medication_reminders", nullable = false)
    @Builder.Default
    private Boolean medicationReminders = true;

    /** UI key vitals_alert: readings outside range */
    @Column(name = "vitals_alerts", nullable = false)
    @Builder.Default
    private Boolean vitalsAlerts = true;

    /** UI key appt_reminder_1h (appointment_reminders = 24h reminder) */
    @Column(name = "appointment_reminder_1h", nullable = false)
    @Builder.Default
    private Boolean appointmentReminder1h = true;

    /** UI key appt_changes: cancelled / rescheduled */
    @Column(name = "appointment_change_alerts", nullable = false)
    @Builder.Default
    private Boolean appointmentChangeAlerts = true;

    /** UI key video_call_ready */
    @Column(name = "video_call_alerts", nullable = false)
    @Builder.Default
    private Boolean videoCallAlerts = true;

    /** UI key new_message */
    @Column(name = "message_notifications", nullable = false)
    @Builder.Default
    private Boolean messageNotifications = true;

    /** UI key care_plan_update */
    @Column(name = "care_plan_update_alerts", nullable = false)
    @Builder.Default
    private Boolean carePlanUpdateAlerts = false;

    /** UI key referral */
    @Column(name = "referral_alerts", nullable = false)
    @Builder.Default
    private Boolean referralAlerts = true;

    /** UI key login_alert: new device sign-in */
    @Column(name = "login_alerts", nullable = false)
    @Builder.Default
    private Boolean loginAlerts = true;

    /** UI key access_change: proxy permission changes */
    @Column(name = "access_change_alerts", nullable = false)
    @Builder.Default
    private Boolean accessChangeAlerts = true;

    /** Invoice, payment and insurance claim notifications (notification category BILLING). */
    @Column(name = "billing_notifications", nullable = false)
    @Builder.Default
    private Boolean billingNotifications = true;

    /**
     * Quiet hours: non-critical email, SMS and push messages are held until the window ends. In-app notifications
     * are not affected. The window is wall-clock time in {@link #timezone} (clinic zone when unset or invalid) and
     * may cross midnight (start 22:00, end 07:00).
     */
    @Column(name = "quiet_hours_enabled", nullable = false)
    @Builder.Default
    private Boolean quietHoursEnabled = false;

    @Column(name = "quiet_hours_start")
    private LocalTime quietHoursStart;

    @Column(name = "quiet_hours_end")
    private LocalTime quietHoursEnd;

    // Legacy columns: weekly_summary, profile_visibility, data_sharing_enabled, two_factor_enabled, language and
    // theme are no longer exposed or changed by the settings API (nothing reads them). They stay mapped so the
    // stored values are kept as they are.

    @Column(name = "weekly_summary", nullable = false)
    @Builder.Default
    private Boolean weeklySummary = false;

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

    /**
     * IANA zone id chosen by the user (for example Africa/Nairobi); null means the clinic zone
     * ({@code carenexus.scheduling.time-zone}). Read by the notification fan-out (quiet hours) and reminder text.
     */
    @Column(name = "timezone", length = 50)
    private String timezone;

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
