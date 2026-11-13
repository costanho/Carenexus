package com.carenexus.api.core.dto.request;

import com.carenexus.api.common.dto.ValidationPatterns;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

/**
 * Body of PATCH /api/profile/settings (and its aliases). Partial update: omitted or null fields stay unchanged;
 * {@code {}} changes nothing. Strings are trimmed when the body is read.
 *
 * Removed fields (profileVisibility, dataSharingEnabled, language, theme, weeklySummary, twoFactorEnabled) and any
 * other unknown field are ignored, whatever their value.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Partial update of the caller's notification settings: only the fields sent are changed")
public class UpdateUserSettingsRequest {

    // ---- delivery channels (enforced by the notification fan-out) ----

    @JsonProperty("emailNotifications")
    @Schema(description = "Channel: false sends no email for any notification")
    private Boolean emailNotifications;

    @JsonProperty("smsNotifications")
    @Schema(description = "Channel: false sends no SMS for any notification")
    private Boolean smsNotifications;

    @JsonProperty("pushNotifications")
    @Schema(description = "Channel: false sends no push message for any notification")
    private Boolean pushNotifications;

    // ---- notification categories (enforced by the notification publisher) ----

    @JsonProperty("appointmentReminders")
    @Schema(description = "Category APPOINTMENT_REMINDER (first appointment reminder)")
    private Boolean appointmentReminders;

    @JsonProperty("prescriptionReminders")
    @Schema(description = "Category PRESCRIPTION")
    private Boolean prescriptionReminders;

    @JsonProperty("labResultAlerts")
    @Schema(description = "Category LAB_RESULT")
    private Boolean labResultAlerts;

    @JsonProperty("imagingResultAlerts")
    @Schema(description = "Category IMAGING_RESULT")
    private Boolean imagingResultAlerts;

    @JsonProperty("consultationNotifications")
    @Schema(description = "Category CONSULTATION")
    private Boolean consultationNotifications;

    @JsonProperty("criticalAlerts")
    @Schema(description = "Category CRITICAL below critical priority; critical-priority alerts are always delivered")
    private Boolean criticalAlerts;

    @JsonProperty("medicationReminders")
    @Schema(description = "Category MEDICATION_REMINDER")
    private Boolean medicationReminders;

    @JsonProperty("vitalsAlerts")
    @Schema(description = "Category VITALS")
    private Boolean vitalsAlerts;

    @JsonProperty("appointmentReminder1h")
    @Schema(description = "Category APPOINTMENT_REMINDER_1H (second appointment reminder)")
    private Boolean appointmentReminder1h;

    @JsonProperty("appointmentChangeAlerts")
    @Schema(description = "Category APPOINTMENT_CHANGE (booked, rescheduled, cancelled)")
    private Boolean appointmentChangeAlerts;

    @JsonProperty("videoCallAlerts")
    @Schema(description = "Category VIDEO_CALL")
    private Boolean videoCallAlerts;

    @JsonProperty("messageNotifications")
    @Schema(description = "Category MESSAGE")
    private Boolean messageNotifications;

    @JsonProperty("carePlanUpdateAlerts")
    @Schema(description = "Category CARE_PLAN")
    private Boolean carePlanUpdateAlerts;

    @JsonProperty("referralAlerts")
    @Schema(description = "Category REFERRAL")
    private Boolean referralAlerts;

    @JsonProperty("loginAlerts")
    @Schema(description = "Category LOGIN_ALERT")
    private Boolean loginAlerts;

    @JsonProperty("accessChangeAlerts")
    @Schema(description = "Category ACCESS_CHANGE")
    private Boolean accessChangeAlerts;

    @JsonProperty("billingNotifications")
    @Schema(description = "Category BILLING: invoices, payments, refunds, overdue notices and insurance-claim updates")
    private Boolean billingNotifications;

    // ---- quiet hours and time zone (enforced by the notification fan-out) ----

    @JsonProperty("quietHoursEnabled")
    @Schema(description = "Holds non-critical email, SMS and push inside the window. Turning it on needs a start "
            + "and an end time (stored or sent together) that differ")
    private Boolean quietHoursEnabled;

    @JsonProperty("quietHoursStart")
    @Pattern(regexp = ValidationPatterns.TIME_HH_MM, message = "Use 24-hour time as HH:mm")
    @Schema(description = "HH:mm wall-clock time in the user's time zone; start is inclusive", example = "22:00")
    private String quietHoursStart;

    @JsonProperty("quietHoursEnd")
    @Pattern(regexp = ValidationPatterns.TIME_HH_MM, message = "Use 24-hour time as HH:mm")
    @Schema(description = "HH:mm; end is exclusive; earlier than the start means the window crosses midnight",
            example = "07:00")
    private String quietHoursEnd;

    @JsonProperty("timezone")
    @Size(max = 50, message = "Time zone must be at most 50 characters")
    @Schema(description = "IANA zone id (for example Africa/Nairobi or UTC); \"\" means the clinic time zone",
            example = "Africa/Nairobi")
    private String timezone;

    // ==================== TRIMMING SETTERS (used by Jackson) ====================

    public void setQuietHoursStart(String quietHoursStart) {
        this.quietHoursStart = trim(quietHoursStart);
    }

    public void setQuietHoursEnd(String quietHoursEnd) {
        this.quietHoursEnd = trim(quietHoursEnd);
    }

    public void setTimezone(String timezone) {
        this.timezone = trim(timezone);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
