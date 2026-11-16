package com.carenexus.api.auth.provider;

/**
 * Seam for a bot-detection / CAPTCHA provider in front of the public endpoints that create work for the system
 * (registration, password recovery). The client obtains a token from the provider's widget and sends it with the
 * request; the server verifies it here before doing anything else.
 *
 * Selected by {@code carenexus.auth.human-verification}. {@code none} (the default, {@link NoopHumanVerification})
 * verifies nothing; rate limits still apply. A real provider is added as another implementation chosen in
 * {@code auth.config.HumanVerificationConfig}.
 */
public interface HumanVerification {

    /** Public registration ({@code POST /api/auth/register}). */
    String ACTION_REGISTER = "REGISTER";

    /** Password recovery request ({@code POST /api/auth/password/forgot}). */
    String ACTION_PASSWORD_FORGOT = "PASSWORD_FORGOT";

    /** Error code of a refused verification: {@code BadRequestException(CODE_FAILED, ...)} (HTTP 400). */
    String CODE_FAILED = "HUMAN_VERIFICATION_FAILED";

    /**
     * Returns normally when the caller may continue. An implementation that refuses throws
     * {@code BadRequestException(CODE_FAILED, "<sentence safe to show>")}.
     *
     * @param action {@link #ACTION_REGISTER} or {@link #ACTION_PASSWORD_FORGOT}
     * @param token  the token the client sent (may be null or blank); it is a credential, never log it
     * @param ip     client address from {@code request.getRemoteAddr()} (may be null)
     */
    void verify(String action, String token, String ip);
}
