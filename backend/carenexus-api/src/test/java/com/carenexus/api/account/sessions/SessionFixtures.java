package com.carenexus.api.account.sessions;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.support.AuthApi;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Signs fixture users in through the real login endpoint, so each call creates a real session (refresh token row
 * with a session id and the device fields), exactly as a client does.
 */
public final class SessionFixtures {

    private final AuthApi authApi;

    public SessionFixtures(AuthApi authApi) {
        this.authApi = authApi;
    }

    /** A signed-in session: the tokens of the login response. */
    public record Session(String sessionId, String accessToken, String refreshToken) {
    }

    /** Sign {@code user} in (fixture password) from a device; null device fields are not sent. */
    public Session signIn(User user, String deviceType, String deviceInfo) throws Exception {
        Map<String, Object> body = AuthApi.credentials(user.getEmail(), AuthApi.FIXTURE_PASSWORD);
        if (deviceType != null) {
            body.put("deviceType", deviceType);
        }
        if (deviceInfo != null) {
            body.put("deviceInfo", deviceInfo);
        }
        MvcResult result = authApi.login(body);
        assertEquals(200, result.getResponse().getStatus(), "login of " + user.getEmail() + ": "
                + result.getResponse().getContentAsString());
        return session(authApi.json(result));
    }

    /** Exchange the session's refresh token; the returned session carries the new tokens. */
    public Session refresh(Session session) throws Exception {
        MvcResult result = authApi.refresh(session.refreshToken());
        assertEquals(200, result.getResponse().getStatus(), "refresh: " + result.getResponse().getContentAsString());
        return session(authApi.json(result));
    }

    /** HTTP status of a refresh with the session's refresh token. */
    public int refreshStatus(Session session) throws Exception {
        return authApi.refresh(session.refreshToken()).getResponse().getStatus();
    }

    private static Session session(JsonNode response) {
        JsonNode tokens = response.path("tokens");
        return new Session(tokens.path("sessionId").asText(), tokens.path("accessToken").asText(),
                tokens.path("refreshToken").asText());
    }
}
