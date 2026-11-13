package com.carenexus.api.account.mfa.service;

import com.carenexus.api.common.exception.ApiException;
import com.carenexus.api.common.exception.BadRequestException;
import org.springframework.http.HttpStatus;

/**
 * Error codes, fixed message texts (api/be-account-mfa.md, M1 to M11) and the two exception types of the MFA module.
 * Every text is one sentence under 120 characters and safe to show.
 */
public final class MfaErrors {

    public static final String CODE_INVALID = "MFA_CODE_INVALID";
    public static final String CHALLENGE_INVALID = "MFA_CHALLENGE_INVALID";
    /** Same value as ErrorResponse.MFA_REQUIRED. */
    public static final String REQUIRED = "MFA_REQUIRED";

    public static final String M1_TOTP_INVALID =
            "That code is incorrect or already used. Enter the current code from your authenticator app.";
    public static final String M2_RECOVERY_INVALID = "That recovery code is incorrect or already used.";
    public static final String M3_CHALLENGE_EXPIRED = "This sign-in step has expired. Please sign in again.";
    public static final String M4_CHALLENGE_LOCKED = "Too many incorrect codes. Please sign in again.";
    public static final String M5_ALREADY_ENABLED = "Two-factor authentication is already turned on.";
    public static final String M6_NOT_ENABLED = "Two-factor authentication is not turned on.";
    public static final String M7_SETUP_NOT_STARTED = "Start two-factor setup before entering a code.";
    public static final String M8_REQUIRED_FOR_ROLE =
            "Two-factor authentication is required for your role and cannot be turned off.";
    public static final String M9_WRONG_PASSWORD = "Current password is incorrect.";
    public static final String M10_CODE_OR_RECOVERY = "Enter a 6-digit code or a recovery code, not both.";
    public static final String M11_USER_NOT_FOUND = "User not found";

    private MfaErrors() {
    }

    /**
     * 400 MFA_CODE_INVALID: a TOTP or recovery code is wrong or was already used. Thrown after the failed attempt was
     * recorded, so the services list it in {@code noRollbackFor}.
     */
    public static class CodeInvalidException extends BadRequestException {
        public CodeInvalidException(String message) {
            super(CODE_INVALID, message);
        }
    }

    /**
     * 401 MFA_CHALLENGE_INVALID: the challenge token is unknown, expired, used, of the wrong type, locked, or the
     * account is no longer eligible. Thrown as an ApiException (not a Spring AuthenticationException) so the code and
     * message reach the client.
     */
    public static class ChallengeInvalidException extends ApiException {
        public ChallengeInvalidException(String message) {
            super(HttpStatus.UNAUTHORIZED, CHALLENGE_INVALID, message);
        }
    }
}
