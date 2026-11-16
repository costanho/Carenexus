package com.carenexus.api.account.datarights.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Body of {@code POST /api/users/me/deletion-request}. */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "Password confirmation and optional reason for a deletion request")
public class DeletionRequest {

    @NotBlank
    @Size(max = 200, message = "must be at most 200 characters")
    @Schema(description = "The caller's current password", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 200)
    private String password;

    @Size(max = 500, message = "must be at most 500 characters")
    @Schema(description = "Why the user wants the account deleted (optional; trimmed, blank is stored as null)",
            maxLength = 500, nullable = true)
    private String reason;

    @Override
    public String toString() {
        return "DeletionRequest[password=***, reason=" + (reason == null ? "null" : "<" + reason.length() + " chars>") + "]";
    }
}
