package com.carenexus.api.account.password.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Locale;

/**
 * Body of {@code POST /api/auth/password/forgot}. The email is trimmed and lower-cased when it is set (a blank value
 * counts as missing), so validation sees the value the service looks up. Unknown properties are ignored.
 */
@Getter
@NoArgsConstructor
@Schema(description = "Request for a password reset link")
public class ForgotPasswordRequest {

    /** One @, a dot in the domain, no spaces. The length is checked separately so each rule reports once. */
    public static final String EMAIL_REGEX = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$";

    @NotBlank(message = "Email is required")
    @Size(max = 150, message = "Email must be at most 150 characters")
    @Pattern(regexp = EMAIL_REGEX, message = "Please enter a valid email address")
    @Schema(description = "Email address of the account; compared case-insensitively",
            example = "amara.okafor@example.com", maxLength = 150)
    private String email;

    @Size(max = 4096, message = "Verification token is too long")
    @Schema(description = "Token from the human-verification widget, when one is enabled; ignored otherwise",
            nullable = true, maxLength = 4096)
    private String humanVerificationToken;

    public ForgotPasswordRequest(String email, String humanVerificationToken) {
        setEmail(email);
        setHumanVerificationToken(humanVerificationToken);
    }

    public void setEmail(String email) {
        if (email == null || email.isBlank()) {
            this.email = null;
            return;
        }
        this.email = email.trim().toLowerCase(Locale.ROOT);
    }

    /** Not trimmed: the token is a credential of the verification provider and is passed on as sent. */
    public void setHumanVerificationToken(String humanVerificationToken) {
        this.humanVerificationToken = humanVerificationToken;
    }
}
