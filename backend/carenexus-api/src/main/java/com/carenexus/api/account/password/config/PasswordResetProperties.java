package com.carenexus.api.account.password.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Settings of the password recovery links.
 * <ul>
 *   <li>{@code carenexus.auth.password-reset-ttl-minutes}: lifetime of every set-password link (forgot password,
 *       administrator reset, invitation); code default 45, allowed 5 to 1440.</li>
 *   <li>{@code carenexus.web.base-url}: public URL of the web app the links point at (no trailing slash needed).</li>
 * </ul>
 * Invalid values fail startup.
 */
@Component
@Getter
public class PasswordResetProperties {

    public static final int DEFAULT_TTL_MINUTES = 45;
    static final int MIN_TTL_MINUTES = 5;
    static final int MAX_TTL_MINUTES = 1440;

    /** Web route that reads the token from the query string (CONTRACTS 12.6). */
    public static final String RESET_ROUTE = "/auth/reset-password?token=";

    private final int ttlMinutes;
    private final String webBaseUrl;

    public PasswordResetProperties(
            @Value("${carenexus.auth.password-reset-ttl-minutes:" + DEFAULT_TTL_MINUTES + "}") int ttlMinutes,
            @Value("${carenexus.web.base-url:http://localhost:8081}") String webBaseUrl) {
        if (ttlMinutes < MIN_TTL_MINUTES || ttlMinutes > MAX_TTL_MINUTES) {
            throw new IllegalStateException("carenexus.auth.password-reset-ttl-minutes must be between "
                    + MIN_TTL_MINUTES + " and " + MAX_TTL_MINUTES + " (was " + ttlMinutes + ")");
        }
        String base = webBaseUrl == null ? "" : webBaseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            throw new IllegalStateException("carenexus.web.base-url must be an absolute http(s) URL");
        }
        this.ttlMinutes = ttlMinutes;
        this.webBaseUrl = base;
    }

    /** The absolute link the user opens to set a new password. */
    public String resetLink(String rawToken) {
        return webBaseUrl + RESET_ROUTE + rawToken;
    }
}
