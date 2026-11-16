package com.carenexus.api.core.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Notification settings of one account. Category toggles are enforced by the notification publisher, channel
 * flags, quiet hours and the time zone by the notification fan-out.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Notification settings of one account")
public class UserSettingsResponse {

    @JsonProperty("settingId")
    private Integer settingId;

    @JsonProperty("userId")
    @Schema(description = "Legacy field, always null; use systemId")
    private Integer userId;

    @JsonProperty("systemId")
    @Schema(description = "The account the settings belong to", example = "PAT-00001")
    private String systemId;

    @JsonProperty("emailNotifications")
    private Boolean emailNotifications;

    @JsonProperty("smsNotifications")
    private Boolean smsNotifications;

    @JsonProperty("pushNotifications")
    private Boolean pushNotifications;

    @JsonProperty("appointmentReminders")
    private Boolean appointmentReminders;

    @JsonProperty("appointmentReminder1h")
    private Boolean appointmentReminder1h;

    @JsonProperty("appointmentChangeAlerts")
    private Boolean appointmentChangeAlerts;

    @JsonProperty("prescriptionReminders")
    private Boolean prescriptionReminders;

    @JsonProperty("medicationReminders")
    private Boolean medicationReminders;

    @JsonProperty("labResultAlerts")
    private Boolean labResultAlerts;

    @JsonProperty("imagingResultAlerts")
    private Boolean imagingResultAlerts;

    @JsonProperty("consultationNotifications")
    private Boolean consultationNotifications;

    @JsonProperty("vitalsAlerts")
    private Boolean vitalsAlerts;

    @JsonProperty("criticalAlerts")
    @Schema(description = "Below-critical alerts of category CRITICAL; critical-priority alerts are always delivered")
    private Boolean criticalAlerts;

    @JsonProperty("referralAlerts")
    private Boolean referralAlerts;

    @JsonProperty("carePlanUpdateAlerts")
    private Boolean carePlanUpdateAlerts;

    @JsonProperty("accessChangeAlerts")
    private Boolean accessChangeAlerts;

    @JsonProperty("loginAlerts")
    private Boolean loginAlerts;

    @JsonProperty("billingNotifications")
    @Schema(description = "Invoices, payments, refunds, overdue notices and insurance-claim updates")
    private Boolean billingNotifications;

    @JsonProperty("videoCallAlerts")
    private Boolean videoCallAlerts;

    @JsonProperty("messageNotifications")
    private Boolean messageNotifications;

    @JsonProperty("quietHoursEnabled")
    @Schema(description = "Holds non-critical email, SMS and push inside the quiet window")
    private Boolean quietHoursEnabled;

    @JsonProperty("quietHoursStart")
    @Schema(description = "HH:mm wall-clock time in timezone (clinic zone when null); inclusive", example = "22:00")
    private String quietHoursStart;

    @JsonProperty("quietHoursEnd")
    @Schema(description = "HH:mm; exclusive; earlier than the start means the window crosses midnight",
            example = "07:00")
    private String quietHoursEnd;

    @JsonProperty("timezone")
    @Schema(description = "IANA zone id; null means the clinic time zone", example = "Africa/Nairobi")
    private String timezone;

    @JsonProperty("createdAt")
    private LocalDateTime createdAt;

    @JsonProperty("updatedAt")
    private LocalDateTime updatedAt;
}
