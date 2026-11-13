package com.carenexus.api.admin.settings.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Body of {@code PUT /api/admin/settings/{key}}. The value is always a JSON string, whatever the type of the
 * setting; the service then validates it against the type and limits of the key.
 *
 * Deliberately without a one-argument constructor: Jackson would use it to accept a bare JSON string as the body.
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "New value of one system setting")
public class UpdateSystemSettingRequest {

    /** Upper bound of the raw text, before the per-key rules apply. */
    public static final int MAX_VALUE_LENGTH = 500;

    @Schema(description = "The new value as text, for example \"48\", \"0.16\" or \"KES\". Never blank: "
            + "use DELETE to restore the default.", example = "48", requiredMode = Schema.RequiredMode.REQUIRED,
            maxLength = MAX_VALUE_LENGTH)
    @NotBlank(message = "must not be blank")
    @Size(max = MAX_VALUE_LENGTH, message = "must be at most 500 characters")
    private String value;
}
