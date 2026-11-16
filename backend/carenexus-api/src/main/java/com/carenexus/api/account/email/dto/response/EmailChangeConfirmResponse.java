package com.carenexus.api.account.email.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** Result of a confirmed email change. Every session of the account was ended; the user signs in again. */
@Schema(description = "The email change was applied")
public record EmailChangeConfirmResponse(
        @Schema(example = "Your email address has been changed. Sign in with your new email address.")
        String message,
        @Schema(description = "The account's new sign-in email (lower case)", example = "amina.new@example.com")
        String email) {
}
