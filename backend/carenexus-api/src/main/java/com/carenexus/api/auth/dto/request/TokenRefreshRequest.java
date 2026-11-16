package com.carenexus.api.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Body of {@code POST /api/auth/refresh} (token required) and {@code POST /api/auth/logout} (token optional).
 * A missing or blank token is not a validation error: refresh answers 401 and logout falls back to the bearer token.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "A refresh token issued by login or by a previous refresh")
public class TokenRefreshRequest {

    @Size(max = 512, message = "must be at most 512 characters")
    @Schema(description = "Opaque refresh token; surrounding whitespace is ignored", maxLength = 512, nullable = true)
    private String refreshToken;
}
