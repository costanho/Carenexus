package com.carenexus.api.account.legal.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** What the signed-in account accepted and which current documents it still has to accept. */
@Schema(description = "Legal acceptance state of the caller's account")
public record LegalStatusResponse(
        @Schema(description = "True when pending is not empty; same value as flags.legalActionRequired")
        boolean actionRequired,
        @Schema(description = "The caller's acceptances, newest first, at most 50")
        List<LegalAcceptanceResponse> accepted,
        @Schema(description = "Current documents the caller has not accepted, TERMS before PRIVACY")
        List<LegalDocumentSummary> pending) {
}
