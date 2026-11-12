package com.carenexus.api.account.mfa.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Body of {@code POST /api/users/me/mfa/enable} and {@code POST /api/users/me/mfa/recovery-codes/regenerate}. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "MfaCodeRequest", description = "A code from the authenticator app")
public class MfaCodeRequest {

    @NotNull(message = MfaFormats.TOTP_CODE_MESSAGE)
    @Pattern(regexp = MfaFormats.TOTP_CODE, message = MfaFormats.TOTP_CODE_MESSAGE)
    @Schema(description = "Current 6-digit code from the authenticator app, as a string", example = "492039",
            pattern = MfaFormats.TOTP_CODE)
    private String code;
}
