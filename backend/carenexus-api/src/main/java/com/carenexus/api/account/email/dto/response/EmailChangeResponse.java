package com.carenexus.api.account.email.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** The caller's pending email change. */
@Schema(description = "A pending change of the sign-in email")
public record EmailChangeResponse(
        @Schema(description = "Requested address, trimmed and lower case", example = "amina.new@example.com")
        String newEmail,
        @Schema(description = "When the request was stored (UTC, whole seconds)", example = "2026-11-10T09:00:00Z")
        Instant requestedAt,
        @Schema(description = "When the confirmation link stops working (UTC, whole seconds)",
                example = "2026-11-11T09:00:00Z")
        Instant expiresAt) {
}
