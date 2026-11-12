package com.carenexus.api.calls.dto;

import lombok.*;

import java.time.Instant;

/** POST /token. Sent with Cache-Control: no-store; never logged. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CallTokenResponse {
    private String serverUrl;
    private String token;
    private Instant expiresAt;
    private String identity;

    @Override
    public String toString() {
        return "CallTokenResponse{expiresAt=" + expiresAt + "}";
    }
}
