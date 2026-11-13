package com.carenexus.api.account.datarights.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Body of {@code POST /api/users/me/close}. */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "Password confirmation and optional reason for closing the caller's account")
public class CloseAccountRequest {

    @NotBlank
    @Size(max = 200, message = "must be at most 200 characters")
    @Schema(description = "The caller's current password", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 200)
    private String password;

    @Size(max = 500, message = "must be at most 500 characters")
    @Schema(description = "Why the account is closed (optional; trimmed, blank is stored as null)", maxLength = 500,
            nullable = true)
    private String reason;

    @Override
    public String toString() {
        return "CloseAccountRequest[password=***, reason=" + (reason == null ? "null" : "<" + reason.length() + " chars>") + "]";
    }
}
