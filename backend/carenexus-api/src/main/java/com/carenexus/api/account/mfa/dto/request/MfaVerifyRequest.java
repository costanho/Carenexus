package com.carenexus.api.account.mfa.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Body of {@code POST /api/auth/mfa/verify}: the challenge token plus exactly one of {@code code} and {@code recoveryCode}. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "MfaVerifyRequest", description = "Challenge token of the sign-in plus a 6-digit code or a recovery code")
public class MfaVerifyRequest {

    @NotBlank(message = MfaFormats.REQUIRED_MESSAGE)
    @Size(max = MfaFormats.CHALLENGE_TOKEN_MAX, message = "must be at most 128 characters")
    @Schema(description = "challenge.token of the sign-in response (type MFA)", maxLength = MfaFormats.CHALLENGE_TOKEN_MAX)
    private String challengeToken;

    @Pattern(regexp = MfaFormats.TOTP_CODE, message = MfaFormats.TOTP_CODE_MESSAGE)
    @Schema(description = "Current 6-digit code; send this or recoveryCode", example = "492039", nullable = true)
    private String code;

    @Pattern(regexp = MfaFormats.RECOVERY_CODE, message = MfaFormats.RECOVERY_CODE_MESSAGE)
    @Schema(description = "One unused recovery code (consumed on success); send this or code",
            example = "K7QMP-2XWRT", nullable = true)
    private String recoveryCode;

    /** The token is a credential: keep it out of logs. */
    @Override
    public String toString() {
        return "MfaVerifyRequest";
    }
}
