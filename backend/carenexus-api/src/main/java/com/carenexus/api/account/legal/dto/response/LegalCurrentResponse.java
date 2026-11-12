package com.carenexus.api.account.legal.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** The current version of each legal document, keyed by type. */
@Schema(description = "Current version of the terms of service and of the privacy policy")
public record LegalCurrentResponse(
        @Schema(description = "Current Terms of Service") LegalDocumentSummary terms,
        @Schema(description = "Current Privacy Policy") LegalDocumentSummary privacy) {
}
