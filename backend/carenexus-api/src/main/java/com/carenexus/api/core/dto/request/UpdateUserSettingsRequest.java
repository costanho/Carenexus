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
