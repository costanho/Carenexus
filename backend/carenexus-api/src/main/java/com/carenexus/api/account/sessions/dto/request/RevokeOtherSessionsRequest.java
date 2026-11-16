package com.carenexus.api.account.sessions.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Body of {@code POST /api/users/me/sessions/revoke-others}.
 *
 * Deliberately without a one-argument constructor: Jackson would use it to accept a bare JSON string as the body.
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "The session to keep while every other session of the caller is ended")
public class RevokeOtherSessionsRequest {

    /**
     * A UUID, optionally surrounded by white space (the value is trimmed). The empty alternative lets a blank value
     * produce only the "is required" error.
     */
    static final String SESSION_ID_PATTERN =
            "^\\s*([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})?\\s*$";

    @Schema(description = "The caller's own active session (tokens.sessionId of the sign-in), a UUID",
            example = "7c1f0c5e-3f0a-4b7e-9a51-2d0d6f6f1c3a", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Session id is required")
    @Pattern(regexp = SESSION_ID_PATTERN, message = "Session id is invalid")
    private String keepSessionId;
}
