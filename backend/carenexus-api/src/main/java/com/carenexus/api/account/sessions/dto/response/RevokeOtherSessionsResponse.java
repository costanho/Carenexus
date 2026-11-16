package com.carenexus.api.account.sessions.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** Result of {@code POST /api/users/me/sessions/revoke-others}. */
@Schema(description = "Result of ending every other session")
public record RevokeOtherSessionsResponse(
        @Schema(description = "Number of sessions ended by this call (0 or more); the kept session is never counted",
                example = "2", requiredMode = Schema.RequiredMode.REQUIRED)
        int revokedCount) {
}
