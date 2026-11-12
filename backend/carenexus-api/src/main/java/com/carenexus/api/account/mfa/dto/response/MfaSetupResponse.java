package com.carenexus.api.account.mfa.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** The secret of a pending enrolment, for the QR code and for manual entry. */
@Schema(name = "MfaSetupResponse", description = "Secret to add to an authenticator app")
public record MfaSetupResponse(
        @Schema(description = "Base32 secret (32 characters A-Z, 2-7) for manual entry",
                example = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP", pattern = "^[A-Z2-7]{32}$")
        String secret,

        @Schema(description = "otpauth:// URI to render as a QR code",
                example = "otpauth://totp/CareNexus:tariro.ncube%40carenexus.test?secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"
                        + "&issuer=CareNexus&algorithm=SHA1&digits=6&period=30")
        String otpauthUri) {

    /** The secret is a credential: keep it out of logs. */
    @Override
    public String toString() {
        return "MfaSetupResponse";
    }
}
