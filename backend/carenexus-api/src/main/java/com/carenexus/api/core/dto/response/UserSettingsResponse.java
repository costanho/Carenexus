package com.carenexus.api.core.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSettingsResponse {

    @JsonProperty("settingId")
    private Integer settingId;

    @JsonProperty("userId")
    private Integer userId;

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

    @JsonProperty("createdAt")
    private LocalDateTime createdAt;

    @JsonProperty("updatedAt")
    private LocalDateTime updatedAt;
}
