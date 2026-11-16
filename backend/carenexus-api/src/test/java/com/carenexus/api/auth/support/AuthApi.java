package com.carenexus.api.auth.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Calls the public auth endpoints the way a client does (no access token unless one is given), optionally from a
 * chosen client address so that tests of the per-address rate limits use keys of their own.
 */
public class AuthApi {

    /** The password of every fixture user created by BaseIntegrationTest.createAndSaveUser. */
    public static final String FIXTURE_PASSWORD = "password123";

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final String clientIp;

    public AuthApi(MockMvc mockMvc, ObjectMapper objectMapper) {
        this(mockMvc, objectMapper, null);
    }

    private AuthApi(MockMvc mockMvc, ObjectMapper objectMapper, String clientIp) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.clientIp = clientIp;
    }

    /** The same client, calling from {@code ip}. */
    public AuthApi from(String ip) {
        return new AuthApi(mockMvc, objectMapper, ip);
    }

    public MvcResult register(Map<String, ?> body) throws Exception {
        return send(post("/api/auth/register"), body, null);
    }

    public MvcResult login(String email, String password) throws Exception {
        return login(credentials(email, password));
    }

    public MvcResult login(Map<String, ?> body) throws Exception {
        return send(post("/api/auth/login"), body, null);
    }

    public MvcResult refresh(String refreshToken) throws Exception {
        return send(post("/api/auth/refresh"), Map.of("refreshToken", refreshToken), null);
    }

    public MvcResult logoutWithToken(String refreshToken) throws Exception {
        return send(post("/api/auth/logout"), Map.of("refreshToken", refreshToken), null);
    }

    /** Sign out everywhere: no refresh token in the body, identified by the access token. */
    public MvcResult logoutEverywhere(String accessToken) throws Exception {
        return send(post("/api/auth/logout"), Map.of(), accessToken);
    }

    public MvcResult me(String accessToken) throws Exception {
        return send(get("/api/users/me"), null, accessToken);
    }

    public MvcResult send(MockHttpServletRequestBuilder builder, Object body, String accessToken) throws Exception {
        if (accessToken != null) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        if (clientIp != null) {
            builder.with(request -> {
                request.setRemoteAddr(clientIp);
                return request;
            });
        }
        return mockMvc.perform(builder).andReturn();
    }

    public JsonNode json(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? objectMapper.nullNode() : objectMapper.readTree(content);
    }

    public static Map<String, Object> credentials(String email, String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", password);
        return body;
    }

    /** A valid registration body; tests change or remove entries. */
    public static Map<String, Object> registration(String role, String email) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("firstName", "Tendai");
        body.put("lastName", "Moyo");
        body.put("email", email);
        body.put("password", "Str0ng-Passw0rd");
        body.put("role", role);
        return body;
    }
}
