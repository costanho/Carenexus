package com.carenexus.api.account.legal.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** One acceptance of an account with the evidence recorded with it (administrator view). */
@Schema(description = "A legal document version an account accepted, with the recorded evidence")
public record LegalAcceptanceAdminResponse(
        @Schema(description = "Row id", example = "41") Integer acceptanceId,
        @Schema(description = "The account", example = "PAT-00014") String systemId,
        @Schema(description = "TERMS or PRIVACY", example = "TERMS") String docType,
        @Schema(description = "Version that was accepted", example = "1") String version,
        @Schema(description = "When it was accepted (ISO-8601 instant, UTC)", example = "2026-11-10T09:14:22Z")
        Instant acceptedAt,
        @Schema(description = "Client address recorded with the acceptance", example = "196.43.12.7", nullable = true)
        String ipAddress,
        @Schema(description = "User-Agent recorded with the acceptance", nullable = true) String userAgent,
        @Schema(description = "True when the version is still the current version of its type") boolean current) {
}
