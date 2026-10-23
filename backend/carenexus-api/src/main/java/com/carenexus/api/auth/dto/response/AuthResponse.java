package com.carenexus.api.auth.dto.response;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthResponse {
    private String message;
    private AuthTokens tokens;
    private AuthUser user;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AuthTokens {
        private String accessToken;
        private String refreshToken;
        private Long expiresIn;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AuthUser {
        private Integer user_id;
        private String system_id;
        private String name;
        private String email;
        private String role;
    }
}
