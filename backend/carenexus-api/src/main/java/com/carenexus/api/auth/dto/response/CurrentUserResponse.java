package com.carenexus.api.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

/**
 * Answer of {@code GET /api/users/me}: the five user fields at the top level (as before) plus the session flags.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "The signed-in user and the current session flags")
public class CurrentUserResponse {

    @Schema(description = "Integer primary key (legacy field)", example = "17")
    private Integer user_id;

    @Schema(description = "PAT-, DOC-, CGV- or ADM- id", example = "PAT-00012")
    private String system_id;

    @Schema(example = "Linda Chikafu")
    private String name;

    @Schema(description = "Null only for a managed dependent, which cannot sign in", example = "linda@example.com",
            nullable = true)
    private String email;

    @Schema(description = "CAREGIVER is reported as proxy", allowableValues = {"patient", "doctor", "proxy", "admin"},
            example = "patient")
    private String role;

    @Schema(description = "Session flags, same content as at sign-in; an empty object when none apply",
            example = "{\"mustChangePassword\": false, \"emailVerified\": true}")
    private Map<String, Object> flags;
}
