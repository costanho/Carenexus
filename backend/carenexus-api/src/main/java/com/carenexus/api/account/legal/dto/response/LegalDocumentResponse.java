package com.carenexus.api.account.legal.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** One legal document version with its text. */
@Schema(description = "A version of the terms of service or the privacy policy with its text")
public record LegalDocumentResponse(
        @Schema(description = "TERMS or PRIVACY", example = "TERMS") String docType,
        @Schema(description = "Version label of the text returned", example = "1") String version,
        @Schema(description = "Display title", example = "Terms of Service") String title,
        @Schema(description = "The text in the restricted Markdown subset (#, ##, ### headings, paragraphs, '- ' "
                + "bullets, **bold**, [text](https://...) links); anything else is to be shown literally")
        String contentMd,
        @Schema(description = "When this version took effect (ISO-8601 instant, UTC)", example = "2026-11-01T00:00:00Z")
        Instant effectiveAt,
        @Schema(description = "True while the text is placeholder copy that still needs legal review")
        boolean pendingLegalReview) {
}
