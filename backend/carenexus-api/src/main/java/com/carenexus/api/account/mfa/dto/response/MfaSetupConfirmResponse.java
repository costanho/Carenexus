package com.carenexus.api.account.mfa.dto.response;

import com.carenexus.api.auth.dto.response.AuthResponse;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Result of forced enrolment: the recovery codes (shown once) and the new session. */
@Schema(name = "MfaSetupConfirmResponse", description = "Recovery codes and the signed-in session")
public record MfaSetupConfirmResponse(
        @ArraySchema(minItems = 10, maxItems = 10,
                schema = @Schema(description = "Recovery code XXXXX-XXXXX", example = "K7QMP-2XWRT",
                        pattern = "^[A-HJKMNP-Z2-9]{5}-[A-HJKMNP-Z2-9]{5}$"))
        List<String> recoveryCodes,

        @Schema(description = "The session, identical in shape to a sign-in response; flags.mfaEnabled is true")
        AuthResponse session) {

    /** The codes and tokens are credentials: keep them out of logs. */
    @Override
    public String toString() {
        return "MfaSetupConfirmResponse";
    }
}
