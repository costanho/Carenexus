package com.carenexus.api.account.mfa.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** MFA state of one account (the caller's, or the target's for an administrator). Every field is always present. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "MfaStatusResponse", description = "Two-factor authentication state of an account")
public record MfaStatusResponse(
        @Schema(description = "True when MFA is on; a started but unconfirmed setup is false", example = "true")
        boolean enabled,

        @Schema(description = "True when the account's role must use MFA (carenexus.auth.mfa-required-roles); "
                + "then it cannot be turned off", example = "false")
        boolean required,

        @Schema(description = "Unused recovery codes, 0 to 10; 0 when MFA is off", example = "9")
        int recoveryCodesRemaining,

        @Schema(description = "When MFA was turned on (ISO-8601 UTC instant); null when off", nullable = true,
                example = "2026-11-10T09:14:27Z")
        Instant enabledAt) {

    public static MfaStatusResponse off(boolean required) {
        return new MfaStatusResponse(false, required, 0, null);
    }
}
