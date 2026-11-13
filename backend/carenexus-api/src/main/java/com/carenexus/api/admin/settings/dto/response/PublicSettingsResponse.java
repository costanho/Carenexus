package com.carenexus.api.admin.settings.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Clinic identity, support contact and clinic hours shown to every signed-in user (Help &amp; Support).
 * Every field is always present; the support contacts are JSON null when none is configured.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "PublicSettingsResponse", description = "Clinic name, support contact and clinic hours")
public record PublicSettingsResponse(
        @Schema(description = "Name of the clinic; never blank", example = "CareNexus")
        String clinicName,

        @Schema(description = "Support email address; null when none is configured",
                example = "support@clinic.example", nullable = true)
        String supportEmail,

        @Schema(description = "Support phone number; null when none is configured",
                example = "+263 24 270 0000", nullable = true)
        String supportPhone,

        @Schema(description = "IANA time zone of the clinic; all clinic hours and appointment times are wall-clock "
                + "times in this zone", example = "Africa/Harare")
        String clinicTimeZone,

        @Schema(description = "Hour of day the clinic opens (0 to 23), clinic wall-clock", example = "8")
        int openHour,

        @Schema(description = "Hour of day the clinic closes (1 to 24, 24 = midnight), clinic wall-clock", example = "18")
        int closeHour,

        @Schema(description = "False when the clinic is closed on Saturday and Sunday", example = "true")
        boolean allowWeekends) {
}
