package com.carenexus.api.account.sessions.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * One active sign-in session of the caller ({@code GET /api/users/me/sessions}). Nullable members are serialised as
 * JSON null, never omitted.
 */
@Schema(description = "One active sign-in session of the caller")
public record SessionResponse(
        @Schema(description = "Session id (lower-case UUID): stays the same across token refreshes. Use it to end "
                + "the session or to keep it when ending all others", example = "7c1f0c5e-3f0a-4b7e-9a51-2d0d6f6f1c3a",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String sessionId,
        @Schema(description = "Device type sent at sign-in (free text, max 50; conventionally Web, iOS or Android); "
                + "null when the client sent none", example = "Web", nullable = true)
        String deviceType,
        @Schema(description = "Device description sent at sign-in (free text, max 255); null when the client sent "
                + "none. Render as plain text", example = "Chrome on macOS", nullable = true)
        String deviceInfo,
        @Schema(description = "When the session was signed in (ISO-8601 instant, UTC)",
                example = "2026-11-10T06:02:11.418203Z", requiredMode = Schema.RequiredMode.REQUIRED)
        Instant createdAt,
        @Schema(description = "When the session last obtained a token: the sign-in or the latest token refresh "
                + "(ISO-8601 instant, UTC). Lags real activity by up to one access-token lifetime",
                example = "2026-11-10T09:47:55.102334Z", requiredMode = Schema.RequiredMode.REQUIRED)
        Instant lastUsedAt,
        @Schema(description = "True only for the session named by the 'current' query parameter",
                requiredMode = Schema.RequiredMode.REQUIRED)
        boolean current) {
}
