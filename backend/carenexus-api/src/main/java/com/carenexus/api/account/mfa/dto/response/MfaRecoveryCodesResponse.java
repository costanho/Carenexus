package com.carenexus.api.account.mfa.dto.response;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Ten new one-time recovery codes. The server keeps only their hashes, so they are shown once. */
@Schema(name = "MfaRecoveryCodesResponse", description = "Ten one-time recovery codes, shown once")
public record MfaRecoveryCodesResponse(
        @ArraySchema(minItems = 10, maxItems = 10,
                schema = @Schema(description = "Recovery code XXXXX-XXXXX", example = "K7QMP-2XWRT",
                        pattern = "^[A-HJKMNP-Z2-9]{5}-[A-HJKMNP-Z2-9]{5}$"))
        List<String> recoveryCodes) {

    /** The codes are credentials: keep them out of logs. */
    @Override
    public String toString() {
        return "MfaRecoveryCodesResponse";
    }
}
