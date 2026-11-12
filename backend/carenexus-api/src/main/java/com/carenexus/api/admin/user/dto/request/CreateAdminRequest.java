package com.carenexus.api.admin.user.dto.request;

import com.carenexus.api.auth.dto.request.RegisterRequest;
import com.carenexus.api.auth.dto.validation.StrongPassword;
import com.carenexus.api.common.dto.ValidationPatterns;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/admin/users}: another administrator account with a temporary password. Text is trimmed,
 * the email lower-cased, a blank phone counts as none; the password is used exactly as sent. A {@code role} field,
 * if sent, is ignored (unknown fields are ignored): the account is always ADMIN.
 */
@Schema(description = "Administrator account to create; the password is temporary and must be changed at first sign-in")
public record CreateAdminRequest(
        @NotBlank(message = "First name is required")
        @Size(max = 100, message = "First name must be at most 100 characters")
        @Schema(example = "Farai", maxLength = 100)
        String firstName,

        @NotBlank(message = "Last name is required")
        @Size(max = 100, message = "Last name must be at most 100 characters")
        @Schema(example = "Ncube", maxLength = 100)
        String lastName,

        @NotBlank(message = "Email is required")
        @Pattern(regexp = RegisterRequest.EMAIL_REGEX, message = "Please enter a valid email address")
        @Schema(description = "Login email; stored lower-case; must not be in use", example = "farai.ncube@carenexus.test",
                maxLength = 150)
        String email,

        @StrongPassword
        @Schema(description = "Temporary password chosen by the creating administrator: at least 8 characters with one "
                + "letter and one digit, at most 72 bytes. Never returned, logged or stored in the idempotency record",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String password,

        @Size(max = 20, message = "Phone number must be at most 20 characters")
        @Pattern(regexp = ValidationPatterns.PHONE, message = "Please enter a valid phone number")
        @Schema(description = "Optional; must not be in use", nullable = true, example = "+263 77 123 4567")
        String phone) {

    public CreateAdminRequest {
        firstName = AdminText.clean(firstName);
        lastName = AdminText.clean(lastName);
        email = AdminText.cleanLower(email);
        phone = AdminText.clean(phone);
    }

    /** The password is a secret: keep it out of logs and error messages. */
    @Override
    public String toString() {
        return "CreateAdminRequest[firstName=" + firstName + ", lastName=" + lastName + "]";
    }
}
