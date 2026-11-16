package com.carenexus.api.account.datarights.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * One account request as its owner sees it (exports, closures, deletion requests). Every field is always present;
 * nullable fields are sent as JSON null.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "A data export, closure record or deletion request of the caller's own account")
public record AccountRequestResponse(
        @Schema(description = "Row id; the {id} of the download and cancel endpoints")
        Integer requestId,
        @Schema(description = "EXPORT, CLOSURE or DELETION")
        String type,
        @Schema(description = "REQUESTED, PROCESSING, READY, COMPLETED, REJECTED or CANCELLED")
        String status,
        @Schema(description = "The user's own reason (closure, deletion); null for EXPORT", nullable = true)
        String reason,
        @Schema(description = "The administrator's reason when a deletion request was rejected", nullable = true)
        String decisionReason,
        @Schema(description = "When the request was made (UTC instant)")
        Instant requestedAt,
        @Schema(description = "EXPORT: when the file became ready; CLOSURE: equals requestedAt; DELETION: when it was "
                + "approved, rejected or cancelled (null while REQUESTED)", nullable = true)
        Instant processedAt,
        @Schema(description = "EXPORT only: end of the download window (requestedAt + 7 days)", nullable = true)
        Instant expiresAt,
        @Schema(description = "True only for a READY export whose download window is still open, decided by the server")
        boolean downloadable,
        @Schema(description = "EXPORT only: download file name, carenexus-data-export-<systemId>-<yyyyMMdd>.json",
                nullable = true)
        String fileName) {
}
