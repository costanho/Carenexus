package com.carenexus.api.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.util.Map;

/**
 * Answer of register, login, refresh and of the endpoints that complete a login challenge. Members that do not
 * apply are serialised as JSON null:
 * <ul>
 *   <li>registration: {@code message} and {@code user};</li>
 *   <li>a session: {@code tokens}, {@code user} and {@code flags};</li>
 *   <li>a challenge: {@code challenge} only (no session exists yet).</li>
 * </ul>
 * Clients tell a challenge from a session by {@code challenge != null}.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Result of register, login and refresh. Members that do not apply are null.")
public class AuthResponse {

    @Schema(description = "Set by registration only", nullable = true,
            example = "Account created successfully. Please log in.")
    private String message;

    @Schema(description = "The session's tokens; null for registration and for a challenge", nullable = true)
    private AuthTokens tokens;

    @Schema(description = "The account; null for a challenge", nullable = true)
    private AuthUser user;

    @Schema(description = "Session flags (booleans such as mustChangePassword, emailVerified, legalActionRequired, "
            + "mfaEnabled). Always an object when a session is issued, possibly empty; null otherwise. Clients "
            + "ignore keys they do not know.", nullable = true,
            example = "{\"mustChangePassword\": false, \"emailVerified\": true}")
    private Map<String, Object> flags;

    @Schema(description = "Present when the sign-in must be completed with another step; then no session exists yet",
            nullable = true)
    private Challenge challenge;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(name = "AuthTokens", description = "Tokens of one session")
    public static class AuthTokens {
        @Schema(description = "JWT sent as \"Authorization: Bearer <token>\"")
        private String accessToken;

        @Schema(description = "Opaque single-use token for POST /api/auth/refresh; every refresh returns a new one")
        private String refreshToken;

        @Schema(description = "Lifetime of the access token in seconds", example = "900")
        private Long expiresIn;

        @Schema(description = "UUID of the session: created at sign-in and unchanged by every refresh",
                example = "7c1f0c5e-3f0a-4b7e-9a51-2d0d6f6f1c3a")
        private String sessionId;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(name = "AuthUser", description = "The signed-in or newly created account")
    public static class AuthUser {
        @Schema(description = "Integer primary key (legacy field)", example = "17")
        private Integer user_id;

        @Schema(description = "PAT-, DOC-, CGV- or ADM- id", example = "PAT-00012")
        private String system_id;

        @Schema(example = "Linda Chikafu")
        private String name;

        @Schema(example = "linda@example.com", nullable = true)
        private String email;

        @Schema(description = "CAREGIVER is reported as proxy", allowableValues = {"patient", "doctor", "proxy", "admin"},
                example = "patient")
        private String role;
    }

    /** A step the client must complete before a session is issued (see {@code LoginGate}). */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(name = "AuthChallenge", description = "A step that must be completed before a session is issued")
    public static class Challenge {
        @Schema(description = "MFA: send a one-time or recovery code. MFA_SETUP: enrol first.",
                allowableValues = {"MFA", "MFA_SETUP"}, example = "MFA")
        private String type;

        @Schema(description = "Opaque single-purpose token to send to the endpoint that completes the challenge")
        private String token;

        @Schema(description = "Lifetime of the challenge token in seconds", example = "300")
        private Long expiresInSeconds;

        /** The token is a credential: keep it out of logs. */
        @Override
        public String toString() {
            return "Challenge[type=" + type + "]";
        }
    }
}
