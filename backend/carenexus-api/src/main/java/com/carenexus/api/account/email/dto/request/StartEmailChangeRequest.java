package com.carenexus.api.account.email.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Body of {@code POST /api/users/me/email-change}. {@code newEmail} is trimmed and lower-cased when set;
 * {@code currentPassword} is used exactly as sent.
 */
@Getter
@NoArgsConstructor
@Schema(description = "Start a change of the sign-in email")
public class StartEmailChangeRequest {

    @NotBlank
    @Size(max = 128)
    @Schema(description = "The caller's current password", example = "Correct-Horse-9", maxLength = 128,
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String currentPassword;

    @NotBlank
    @Size(max = 150)
    @Pattern(regexp = EmailRules.EMAIL_REGEX, message = EmailRules.EMAIL_MESSAGE)
    @Schema(description = "The new sign-in email; must differ from the current one and not belong to another account",
            example = "amina.new@example.com", maxLength = 150, requiredMode = Schema.RequiredMode.REQUIRED)
    private String newEmail;

    public StartEmailChangeRequest(String currentPassword, String newEmail) {
        this.currentPassword = currentPassword;
        setNewEmail(newEmail);
    }

    /** Not trimmed: a password is used exactly as sent. */
    public void setCurrentPassword(String currentPassword) {
        this.currentPassword = currentPassword;
    }

    public void setNewEmail(String newEmail) {
        this.newEmail = EmailRules.normalize(newEmail);
    }
}
