package com.carenexus.api.account.datarights.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** Answer of {@code POST /api/users/me/close}. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "The account was closed; clinical records are retained")
public record AccountClosureResponse(
        @Schema(description = "Id of the CLOSURE row written for the record") Integer requestId,
        @Schema(description = "users.closed_at (UTC instant)") Instant closedAt,
        @Schema(description = "Always true: clinical records are kept") boolean clinicalRecordsRetained,
        @Schema(description = "Text to show to the user as received") String message) {

    public static final String MESSAGE = "Your account is closed. Your clinical records are retained as required for "
            + "medical record keeping and were not deleted.";
}
