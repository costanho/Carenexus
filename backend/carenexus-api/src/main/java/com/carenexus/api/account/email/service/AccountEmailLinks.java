package com.carenexus.api.account.email.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds the absolute web links put into account emails, from {@code carenexus.web.base-url}. Tokens are URL-safe
 * base64, so they need no encoding.
 */
@Component
public class AccountEmailLinks {

    /** The web sign-in route (frontend/src/app/auth/login/web/login-web.tsx); there is no bare /auth/login page. */
    public static final String WEB_LOGIN_PATH = "/auth/login/web/login-web";

    static final String VERIFY_PATH = "/auth/verify-email?token=";
    static final String CONFIRM_CHANGE_PATH = "/auth/confirm-email-change?token=";

    private final String baseUrl;

    public AccountEmailLinks(@Value("${carenexus.web.base-url:http://localhost:8081}") String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        this.baseUrl = value.isEmpty() ? "http://localhost:8081" : value;
    }

    public String verifyEmail(String rawToken) {
        return baseUrl + VERIFY_PATH + rawToken;
    }

    public String confirmEmailChange(String rawToken) {
        return baseUrl + CONFIRM_CHANGE_PATH + rawToken;
    }
}
