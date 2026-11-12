package com.carenexus.api.account.legal.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** A legal document version without its text. */
@Schema(description = "A version of the terms of service or the privacy policy, without its text")
public record LegalDocumentSummary(
        @Schema(description = "TERMS or PRIVACY", example = "TERMS") String docType,
        @Schema(description = "Version label to send back when accepting", example = "1") String version,
        @Schema(description = "Display title", example = "Terms of Service") String title,
        @Schema(description = "When this version took effect (ISO-8601 instant, UTC)", example = "2026-11-01T00:00:00Z")
        Instant effectiveAt,
        @Schema(description = "True while the text is placeholder copy that still needs legal review")
        boolean pendingLegalReview) {
}
