package com.carenexus.api.account.password.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Answer of the password endpoints: one fixed sentence the client may show. Never carries a token or user data.
 */
@Schema(name = "PasswordMessageResponse", description = "Fixed confirmation text")
public record PasswordMessageResponse(
        @Schema(description = "Fixed text, safe to show",
                example = "If an account exists for that email, a password reset link has been sent.")
        String message) {
}
