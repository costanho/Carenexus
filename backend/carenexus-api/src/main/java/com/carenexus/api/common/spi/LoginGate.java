package com.carenexus.api.common.spi;

import com.carenexus.api.auth.model.User;
import org.springframework.core.Ordered;

/**
 * Extension point of the login flow. AuthService runs every {@code LoginGate} bean, in {@link Ordered} order,
 * after the password was verified and before any token is issued.
 *
 * A gate either lets the login continue ({@link LoginStep#proceed()}), answers with a challenge the client must
 * complete first ({@link LoginStep#challenge}; the first challenge wins and no tokens are returned), or refuses the
 * login by throwing {@code ApiException} (for example 403 EMAIL_NOT_VERIFIED, ACCOUNT_PENDING_VERIFICATION).
 * The endpoint that verifies a challenge finishes the login with {@code AuthService.completeLogin}.
 */
public interface LoginGate extends Ordered {

    /** After the password was verified and before tokens are issued. Throw ApiException to refuse. */
    LoginStep afterPasswordVerified(User user, LoginContext ctx);
}
