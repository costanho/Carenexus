package com.carenexus.api.referrals.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Optional body of {@code PATCH /api/referrals/{id}/accept} and {@code /complete}. The note is required when the
 * caller records the outcome on the specialist's behalf ({@code outcomeNoteRequired} in the response).
 */
@Schema(description = "Outcome note of an accept or complete")
public record ReferralOutcomeRequest(

        @Schema(description = "Outcome note; required when recorded on the specialist's behalf", maxLength = 1000)
        @Size(max = 1000, message = "must be at most 1000 characters")
        String note) {

    public ReferralOutcomeRequest {
        note = TextInput.trimToNull(note);
    }
}
