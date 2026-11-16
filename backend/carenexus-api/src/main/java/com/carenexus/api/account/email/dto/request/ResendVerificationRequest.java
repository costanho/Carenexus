package com.carenexus.api.account.email.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;


/** Body of {@code POST /api/auth/email/resend}. The address is trimmed and lower-cased when set. */
@Getter
@NoArgsConstructor
@Schema(description = "Address that should receive a new verification link")
public class ResendVerificationRequest {

    @NotBlank
    @Size(max = 150)
    @Pattern(regexp = EmailRules.EMAIL_REGEX, message = EmailRules.EMAIL_MESSAGE)
    @Schema(description = "Sign-in email of the account; case-insensitive", example = "amina.patient@example.com",
            maxLength = 150, requiredMode = Schema.RequiredMode.REQUIRED)
    private String email;

    public ResendVerificationRequest(String email) {
        setEmail(email);
    }

    public void setEmail(String email) {
        this.email = EmailRules.normalize(email);
    }
}
