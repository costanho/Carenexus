package com.auth.server.dto.response;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthResponse {
    private String accessToken;     // JWT token (short-lived)
    private String refreshToken;    // Refresh token (long-lived)
    private String tokenType;       // "Bearer"
    private Long expiresIn;         // Seconds until JWT expires
    private Integer userId;
    private String firstName;
    private String lastName;
    private String role;
}
