package com.carenexus.api.common.spi;

/**
 * Outcome of a {@link LoginGate}: continue, or stop and ask the client to complete a challenge.
 * Build it with {@link #proceed()} or {@link #challenge}; read it with {@link #canProceed()}.
 *
 * (The first component is named {@code canProceed}, not {@code proceed}: a record cannot have both a component
 * accessor and a static factory called {@code proceed()}.)
 *
 * @param canProceed       true when the login may continue to the next gate / token issue
 * @param challengeType    {@link #MFA} or {@link #MFA_SETUP} when {@code canProceed} is false, else null
 * @param challengeToken   opaque single-purpose token the client sends back to the challenge endpoint, else null
 * @param expiresInSeconds lifetime of the challenge token in seconds (0 when proceeding)
 */
public record LoginStep(boolean canProceed, String challengeType, String challengeToken, long expiresInSeconds) {

    /** The account has MFA enabled: the client must send a one-time code. */
    public static final String MFA = "MFA";
    /** The account's role requires MFA and it is not enrolled yet: the client must enrol first. */
    public static final String MFA_SETUP = "MFA_SETUP";

    private static final LoginStep PROCEED = new LoginStep(true, null, null, 0);

    public static LoginStep proceed() {
        return PROCEED;
    }

    /**
     * @param type MFA | MFA_SETUP
     */
    public static LoginStep challenge(String type, String token, long expiresInSeconds) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Challenge type is required");
        }
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Challenge token is required");
        }
        return new LoginStep(false, type, token, Math.max(expiresInSeconds, 0));
    }

    /** True when the client must complete {@link #challengeType()} before a session is issued. */
    public boolean isChallenge() {
        return !canProceed;
    }

    /** The token is a credential: keep it out of logs. */
    @Override
    public String toString() {
        return "LoginStep[canProceed=" + canProceed + ", challengeType=" + challengeType + "]";
    }
}
