package com.carenexus.api.auth.provider;

/**
 * {@code carenexus.auth.human-verification=none}: no human verification. Every request passes and the token, when
 * one is sent, is ignored. Abuse of the public endpoints is then limited by the rate limits only.
 */
public class NoopHumanVerification implements HumanVerification {

    public static final String PROVIDER = "none";

    @Override
    public void verify(String action, String token, String ip) {
        // Nothing to verify.
    }
}
