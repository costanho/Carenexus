package com.carenexus.api.account.mfa.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Body of {@code POST /api/auth/mfa/setup/confirm}. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "MfaSetupConfirmRequest", description = "Challenge token (type MFA_SETUP) and the first code of the new authenticator")
public class MfaSetupConfirmRequest {

    @NotBlank(message = MfaFormats.REQUIRED_MESSAGE)
    @Size(max = MfaFormats.CHALLENGE_TOKEN_MAX, message = "must be at most 128 characters")
    @Schema(description = "challenge.token of the sign-in response (type MFA_SETUP)",
            maxLength = MfaFormats.CHALLENGE_TOKEN_MAX)
    private String challengeToken;

    @NotNull(message = MfaFormats.TOTP_CODE_MESSAGE)
    @Pattern(regexp = MfaFormats.TOTP_CODE, message = MfaFormats.TOTP_CODE_MESSAGE)
    @Schema(description = "Current 6-digit code from the authenticator app", example = "492039")
    private String code;

    /** The token is a credential: keep it out of logs. */
    @Override
    public String toString() {
        return "MfaSetupConfirmRequest";
    }
}
