package com.carenexus.api.account.email.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Email verification and email change settings ({@code carenexus.auth.*}). Defaults live here; application.yml only
 * overrides. The bean is mutable so tests can switch the login gate on and off (and must restore it).
 *
 * <pre>
 * carenexus.auth.require-verified-email       = true   sign-in refused (403 EMAIL_NOT_VERIFIED) until the email is confirmed
 * carenexus.auth.email-verification-ttl-hours = 24     lifetime of a verification link
 * carenexus.auth.email-change-ttl-hours       = 24     lifetime of an email change confirmation link
 * </pre>
 */
@Getter
@Setter
@ConfigurationProperties("carenexus.auth")
public class AccountEmailProperties {

    private boolean requireVerifiedEmail = true;

    private int emailVerificationTtlHours = 24;

    private int emailChangeTtlHours = 24;

    /** Verification link lifetime, at least one hour. */
    public int safeEmailVerificationTtlHours() {
        return Math.max(1, emailVerificationTtlHours);
    }

    /** Email change link lifetime, at least one hour. */
    public int safeEmailChangeTtlHours() {
        return Math.max(1, emailChangeTtlHours);
    }
}
