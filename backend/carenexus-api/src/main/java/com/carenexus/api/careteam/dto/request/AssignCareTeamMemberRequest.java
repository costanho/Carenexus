package com.carenexus.api.careteam.dto.request;

import com.carenexus.api.common.dto.ValidationPatterns;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Body of {@code POST /api/patients/{patientSystemId}/care-team}: exactly one of {@code providerSystemId} (a
 * CareNexus doctor) and {@code providerName} (an external provider). String values are trimmed as they are read;
 * a whitespace-only name or organisation counts as absent. Cross-field rules are checked by the service.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Assign a member to a patient's care team")
public class AssignCareTeamMemberRequest {

    @JsonProperty("providerSystemId")
    @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = "must be a valid system id")
    @Schema(description = "A doctor's system id (active, approved); exclusive with the external provider fields",
            example = "DOC-00002", nullable = true)
    private String providerSystemId;

    @JsonProperty("providerName")
    @Size(max = 150, message = "must be at most 150 characters")
    @Schema(description = "External provider's name, 1 to 150 characters", example = "Greenwood Pharmacy", nullable = true)
    private String providerName;

    @JsonProperty("providerPhone")
    @Pattern(regexp = CareTeamRequestRules.PHONE_PATTERN, message = "must be a valid phone number")
    @Schema(description = "External provider's phone, at most 20 characters", example = "+263 242 700 123", nullable = true)
    private String providerPhone;

    @JsonProperty("providerOrganisation")
    @Size(max = 200, message = "must be at most 200 characters")
    @Schema(description = "External provider's organisation, at most 200 characters", example = "Greenwood Health Group",
            nullable = true)
    private String providerOrganisation;

    @JsonProperty("role")
    @NotBlank(message = CareTeamRequestRules.ROLE_REQUIRED)
    @Pattern(regexp = CareTeamRequestRules.ROLE_PATTERN, message = CareTeamRequestRules.ROLE_INVALID)
    @Schema(description = "DOCTOR, NURSE, LAB, PHARMACY, SPECIALIST or THERAPIST (case-insensitive); an account "
            + "provider only DOCTOR or SPECIALIST", example = "SPECIALIST")
    private String role;

    @JsonProperty("isPrimary")
    @Schema(description = "Make this member the primary (clears the previous primary); default false", nullable = true)
    private Boolean isPrimary;

    @JsonProperty("facilityId")
    @Positive(message = "must be a positive number")
    @Schema(description = "An active facility", example = "2", nullable = true)
    private Integer facilityId;

    @JsonProperty("providerSystemId")
    public void setProviderSystemId(String providerSystemId) {
        this.providerSystemId = CareTeamRequestRules.trim(providerSystemId);
    }

    @JsonProperty("providerName")
    public void setProviderName(String providerName) {
        this.providerName = CareTeamRequestRules.trimToNull(providerName);
    }

    @JsonProperty("providerPhone")
    public void setProviderPhone(String providerPhone) {
        this.providerPhone = CareTeamRequestRules.trim(providerPhone);
    }

    @JsonProperty("providerOrganisation")
    public void setProviderOrganisation(String providerOrganisation) {
        this.providerOrganisation = CareTeamRequestRules.trimToNull(providerOrganisation);
    }

    @JsonProperty("role")
    public void setRole(String role) {
        this.role = role;
    }

    @JsonProperty("isPrimary")
    public void setIsPrimary(Boolean isPrimary) {
        this.isPrimary = isPrimary;
    }

    @JsonProperty("facilityId")
    public void setFacilityId(Integer facilityId) {
        this.facilityId = facilityId;
    }
}
