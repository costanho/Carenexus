package com.carenexus.api.account.email.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Body of {@code POST /api/auth/email/verify} and {@code POST /api/auth/email-change/confirm}: the {@code token} query
 * parameter of the emailed link, passed on unchanged. Trimmed when set.
 */
@Getter
@NoArgsConstructor
@Schema(description = "One-time token taken from the emailed link")
public class EmailTokenRequest {

    @NotBlank
    @Size(max = 200)
    @Schema(description = "The token query parameter of the emailed link",
            example = "pV3s0c8mJm3bXq1yT9d2kQ7uE5wZr4nA6hLgC0fYxSI", maxLength = 200,
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String token;

    public EmailTokenRequest(String token) {
        setToken(token);
    }

    public void setToken(String token) {
        this.token = token == null ? null : token.trim();
    }
}
