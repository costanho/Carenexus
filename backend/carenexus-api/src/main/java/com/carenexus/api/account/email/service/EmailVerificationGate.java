package com.carenexus.api.account.email.service;

import com.carenexus.api.account.email.config.AccountEmailProperties;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.dto.ErrorResponse;
import com.carenexus.api.common.exception.ApiException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.spi.LoginContext;
import com.carenexus.api.common.spi.LoginGate;
import com.carenexus.api.common.spi.LoginStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Sign-in refusal for an account whose email is not confirmed, when {@code carenexus.auth.require-verified-email} is
 * true: 403 with code EMAIL_NOT_VERIFIED, no tokens and no challenge. Runs after the password was verified, before
 * any gate that issues a challenge (order {@value #ORDER}). Sends no mail; the client offers the resend endpoint.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmailVerificationGate implements LoginGate {

    public static final int ORDER = 10;
    public static final String MESSAGE = "Please verify your email address before signing in.";

    private final AccountEmailProperties properties;
    private final AuditService auditService;

    @Override
    public LoginStep afterPasswordVerified(User user, LoginContext ctx) {
        if (properties.isRequireVerifiedEmail() && user != null && user.getEmailVerifiedAt() == null) {
            try {
                auditService.logFailedLogin(user.getEmail(), ctx == null ? null : ctx.ip(),
                        ctx == null ? null : ctx.userAgent(), "Email not verified");
            } catch (RuntimeException e) {
                log.warn("Could not write failed-login audit entry: {}", e.getClass().getSimpleName());
            }
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorResponse.EMAIL_NOT_VERIFIED, MESSAGE);
        }
        return LoginStep.proceed();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
