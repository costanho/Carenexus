package com.carenexus.api.core.dto.request;

import com.carenexus.api.auth.dto.request.RegisterRequest;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Locale;

/**
 * POST /api/caregiver/dependents/{accessId}/invite: the email address the managed dependent will sign in with.
 * Trimmed and lower-cased; must be unused or already this patient's email. No single-String constructor on purpose:
 * Jackson would then accept a bare JSON string as the body instead of answering 'Malformed request body'.
 */
@Getter
@NoArgsConstructor
@Schema(description = "Invitation of a managed dependent to sign in")
public class InviteDependentRequest {

    @JsonProperty("email")
    @NotBlank(message = "Email is required")
    @Pattern(regexp = RegisterRequest.EMAIL_REGEX, message = "Please enter a valid email address")
    @Schema(description = "Email address the patient will sign in with", example = "rudo.moyo@example.com")
    private String email;

    public void setEmail(String email) {
        this.email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
