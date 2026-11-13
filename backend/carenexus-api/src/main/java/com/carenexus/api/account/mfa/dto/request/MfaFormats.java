package com.carenexus.api.account.mfa.dto.request;

/** Formats and validation texts shared by the MFA request bodies (api/be-account-mfa.md, field formats). */
public final class MfaFormats {

    /** A TOTP code: exactly 6 ASCII digits (leading zeros are significant). */
    public static final String TOTP_CODE = "^\\d{6}$";
    public static final String TOTP_CODE_MESSAGE = "must be 6 digits";

    /** A recovery code as typed: 10 letters or digits with an optional hyphen in the middle. */
    public static final String RECOVERY_CODE = "^[A-Za-z0-9]{5}-?[A-Za-z0-9]{5}$";
    public static final String RECOVERY_CODE_MESSAGE = "must be a recovery code such as ABCDE-FGHJK";

    public static final int CHALLENGE_TOKEN_MAX = 128;
    public static final int PASSWORD_MAX = 72;
    public static final String REQUIRED_MESSAGE = "is required";

    private MfaFormats() {
    }
}
