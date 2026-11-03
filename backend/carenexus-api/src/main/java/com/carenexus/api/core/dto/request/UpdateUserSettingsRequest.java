package com.carenexus.api.core.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateUserSettingsRequest {

    @JsonProperty("emailNotifications")
    private Boolean emailNotifications;

    @JsonProperty("smsNotifications")
    private Boolean smsNotifications;

    @JsonProperty("pushNotifications")
    private Boolean pushNotifications;

    @JsonProperty("appointmentReminders")
    private Boolean appointmentReminders;

    @JsonProperty("prescriptionReminders")
    private Boolean prescriptionReminders;

    @JsonProperty("labResultAlerts")
    private Boolean labResultAlerts;

    @JsonProperty("imagingResultAlerts")
    private Boolean imagingResultAlerts;

    @JsonProperty("consultationNotifications")
    private Boolean consultationNotifications;

    // Notification preferences used by the proxy notification settings screen (V11 columns)

    @JsonProperty("criticalAlerts")
    private Boolean criticalAlerts;

    @JsonProperty("medicationReminders")
    private Boolean medicationReminders;

    @JsonProperty("vitalsAlerts")
    private Boolean vitalsAlerts;

    @JsonProperty("appointmentReminder1h")
    private Boolean appointmentReminder1h;

    @JsonProperty("appointmentChangeAlerts")
    private Boolean appointmentChangeAlerts;

    @JsonProperty("videoCallAlerts")
    private Boolean videoCallAlerts;

    @JsonProperty("messageNotifications")
    private Boolean messageNotifications;

    @JsonProperty("carePlanUpdateAlerts")
    private Boolean carePlanUpdateAlerts;

    @JsonProperty("referralAlerts")
    private Boolean referralAlerts;

    @JsonProperty("loginAlerts")
    private Boolean loginAlerts;

    @JsonProperty("accessChangeAlerts")
    private Boolean accessChangeAlerts;

    @JsonProperty("weeklySummary")
    private Boolean weeklySummary;

    @JsonProperty("profileVisibility")
    private String profileVisibility;

    @JsonProperty("dataSharingEnabled")
    private Boolean dataSharingEnabled;

    @JsonProperty("twoFactorEnabled")
    private Boolean twoFactorEnabled;

    @JsonProperty("language")
    private String language;

    @JsonProperty("timezone")
    private String timezone;

    @JsonProperty("theme")
    private String theme;
}
