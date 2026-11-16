package com.carenexus.api.account.datarights.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Body of {@code POST /api/users/me/data-export}. No one-argument constructor on purpose: Jackson would then accept a
 * bare JSON string as the body.
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "Password confirmation for a data export")
public class DataExportRequest {

    @NotBlank
    @Size(max = 200, message = "must be at most 200 characters")
    @Schema(description = "The caller's current password", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 200)
    private String password;

    @Override
    public String toString() {
        return "DataExportRequest[password=***]";
    }
}
