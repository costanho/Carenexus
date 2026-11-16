package com.carenexus.api.account.mfa.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Body of {@code POST /api/users/me/mfa/disable}: the current password plus exactly one of {@code code} and
 * {@code recoveryCode}.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "MfaDisableRequest", description = "Current password plus a 6-digit code or a recovery code (one of them)")
public class MfaDisableRequest {

    @NotBlank(message = MfaFormats.REQUIRED_MESSAGE)
    @Size(max = MfaFormats.PASSWORD_MAX, message = "must be at most 72 characters")
    @Schema(description = "The account's current password", maxLength = MfaFormats.PASSWORD_MAX)
    private String password;

    @Pattern(regexp = MfaFormats.TOTP_CODE, message = MfaFormats.TOTP_CODE_MESSAGE)
    @Schema(description = "Current 6-digit code; send this or recoveryCode", example = "492039", nullable = true)
    private String code;

    @Pattern(regexp = MfaFormats.RECOVERY_CODE, message = MfaFormats.RECOVERY_CODE_MESSAGE)
    @Schema(description = "One unused recovery code; send this or code", example = "K7QMP-2XWRT", nullable = true)
    private String recoveryCode;
}
