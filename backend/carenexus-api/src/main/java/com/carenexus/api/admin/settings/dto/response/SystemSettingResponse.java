package com.carenexus.api.admin.settings.dto.response;

import com.carenexus.api.admin.settings.SettingType;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One system setting as the administrator sees it: its effective value, the default it falls back to and the
 * metadata a typed editor needs. Every field is always present; nullable ones are sent as JSON null.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "SystemSettingResponse", description = "A system setting with its effective value, default and limits")
public record SystemSettingResponse(
        @Schema(description = "Dotted name of the setting; use it in the URL of PUT and DELETE",
                example = "billing.tax-rate")
        String key,

        @Schema(description = "Effective value in canonical text form: the stored override when overridden, "
                + "otherwise the default. Empty only for an unset support contact.", example = "0.16")
        String value,

        @Schema(description = "Value in force when no override is stored (application property, else the built-in "
                + "default). What DELETE restores.", example = "0")
        String defaultValue,

        @Schema(description = "True when an administrator has stored an override", example = "true")
        boolean overridden,

        @Schema(description = "Value type; decides how the text is validated", example = "DECIMAL")
        SettingType type,

        @Schema(description = "What the setting controls, one sentence",
                example = "Tax rate applied to new invoices, as a fraction (0.16 = 16%).")
        String description,

        @Schema(description = "Inclusive lower bound for INT and DECIMAL settings; null for the other types",
                example = "0", nullable = true)
        BigDecimal min,

        @Schema(description = "Inclusive upper bound for INT and DECIMAL settings; null for the other types",
                example = "1", nullable = true)
        BigDecimal max,

        @Schema(description = "Maximum length for STRING, EMAIL, PHONE and CURRENCY settings; null for INT and DECIMAL",
                example = "120", nullable = true)
        Integer maxLength,

        @Schema(description = "When the override was last written, clinic wall-clock time without offset; null "
                + "when not overridden", example = "2026-11-10T14:06:41", nullable = true)
        LocalDateTime updatedAt,

        @Schema(description = "System id of the administrator who last wrote the override; null when not overridden",
                example = "ADM-00001", nullable = true)
        String updatedBySystemId) {
}
