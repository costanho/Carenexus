package com.carenexus.api.caregiveraccess.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 202 body of POST /api/caregiver/access-requests: always the same constant text, so the answer never reveals
 * whether a patient account with the email exists.
 */
@Schema(description = "Neutral confirmation of an access request")
public record AccessRequestAcceptedResponse(
        @Schema(description = "Always the same text", example = AccessRequestAcceptedResponse.TEXT)
        String message) {

    public static final String TEXT =
            "If a patient account with this email exists, they will be asked to approve your request.";

    public static AccessRequestAcceptedResponse accepted() {
        return new AccessRequestAcceptedResponse(TEXT);
    }
}
