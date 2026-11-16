package com.carenexus.api.account.email.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** A fixed confirmation text that is safe to show. */
@Schema(name = "AccountMessageResponse", description = "Fixed confirmation text, safe to show to the user")
public record AccountMessageResponse(
        @Schema(example = "Your email address has been verified.") String message) {
}
