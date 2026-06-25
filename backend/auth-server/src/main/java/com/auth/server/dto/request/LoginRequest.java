package com.auth.server.dto.request;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginRequest {
    private String email;
    private String password;
    private String deviceType;      // "iOS", "Android", "Web"
    private String deviceInfo;      // "iPhone 14", "Samsung S23", etc.
}
