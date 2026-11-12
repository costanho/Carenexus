package com.carenexus.api.careteam.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Body of {@code PATCH /api/patients/{patientSystemId}/care-team/{careTeamId}}: at least one field non-null.
 * A patient may send only {@code isPrimary}.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Change a care team member's role and/or primary flag")
public class UpdateCareTeamMemberRequest {

    @JsonProperty("role")
    @Pattern(regexp = CareTeamRequestRules.ROLE_PATTERN, message = CareTeamRequestRules.ROLE_INVALID)
    @Schema(description = "New role (case-insensitive); null = unchanged. An account provider only DOCTOR or SPECIALIST",
            example = "SPECIALIST", nullable = true)
    private String role;

    @JsonProperty("isPrimary")
    @Schema(description = "true makes this member the primary (clears the previous one), false clears it; null = unchanged",
            nullable = true)
    private Boolean isPrimary;
}
