package com.carenexus.api.account.password.dto.request;

import com.carenexus.api.auth.dto.validation.StrongPassword;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Body of {@code POST /api/auth/password/reset}. The token is trimmed (a blank value counts as missing); the new
 * password is used exactly as sent.
 */
@Getter
@NoArgsConstructor
@Schema(description = "Token from an emailed link and the new password")
public class ResetPasswordRequest {

    @NotBlank(message = "Reset token is required")
    @Size(max = 128, message = "Reset token is invalid")
    @Schema(description = "The token query parameter of the emailed link, unchanged",
            example = "pX3vYt0Jr8mQe1uB7n-Zk4sLw2Hc9aDf6GiRoT5yUxE", maxLength = 128)
    private String token;

    @StrongPassword
    @Schema(description = "At least 8 characters with a letter and a digit, at most 72 bytes in UTF-8",
            example = "Harvest2026moon", format = "password")
    private String newPassword;

    public ResetPasswordRequest(String token, String newPassword) {
        setToken(token);
        setNewPassword(newPassword);
    }

    public void setToken(String token) {
        this.token = token == null || token.isBlank() ? null : token.trim();
    }

    /** Not trimmed: a password is used exactly as sent. */
    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }

    @Override
    public String toString() {
        return "ResetPasswordRequest[credentials hidden]";
    }
}
