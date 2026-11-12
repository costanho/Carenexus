package com.carenexus.api.account.legal.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** One acceptance of the signed-in account. */
@Schema(description = "A legal document version the caller accepted")
public record LegalAcceptanceResponse(
        @Schema(description = "Row id", example = "41") Integer acceptanceId,
        @Schema(description = "TERMS or PRIVACY", example = "TERMS") String docType,
        @Schema(description = "Version that was accepted", example = "1") String version,
        @Schema(description = "When it was accepted (ISO-8601 instant, UTC)", example = "2026-11-10T09:14:22Z")
        Instant acceptedAt,
        @Schema(description = "True when the version is still the current version of its type") boolean current) {
}
