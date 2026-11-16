package com.carenexus.api.billing.claim.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** A document attached to an insurance claim that still exists. */
@Schema(description = "Attached document")
public record ClaimDocument(
        @Schema(description = "Pass to GET /api/documents/{id}/download (which applies its own authorization)",
                example = "12")
        Integer documentId,
        @Schema(description = "The document's title", example = "Insurance card")
        String title) {
}
