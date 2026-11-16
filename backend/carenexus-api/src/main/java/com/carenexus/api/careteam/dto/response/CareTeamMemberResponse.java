package com.carenexus.api.careteam.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One member of a patient's care team: an assigned row ({@code source = ASSIGNED}) or a doctor related to the patient
 * only through appointments or consultations ({@code source = DERIVED}). The only response object of the care-team
 * endpoints. Every returned row is active.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "A member of a patient's care team")
public class CareTeamMemberResponse {

    public static final String SOURCE_ASSIGNED = "ASSIGNED";
    public static final String SOURCE_DERIVED = "DERIVED";

    @JsonProperty("careTeamId")
    @Schema(description = "Row id; null for DERIVED rows", example = "57", nullable = true)
    private Integer careTeamId;

    @JsonProperty("patientSystemId")
    @Schema(description = "The patient", example = "PAT-00001")
    private String patientSystemId;

    @JsonProperty("patientName")
    @Schema(description = "Patient's first and last name", example = "Thomas Dube")
    private String patientName;

    @JsonProperty("source")
    @Schema(description = "ASSIGNED (a stored care_team row) or DERIVED (related through appointments or consultations; read-only)",
            allowableValues = {SOURCE_ASSIGNED, SOURCE_DERIVED})
    private String source;

    @JsonProperty("providerSystemId")
    @Schema(description = "The doctor's system id; null for an external provider", example = "DOC-00001", nullable = true)
    private String providerSystemId;

    @JsonProperty("providerName")
    @Schema(description = "Account provider: first and last name without title; external provider: the stored name",
            example = "Amara Okafor")
    private String providerName;

    @JsonProperty("providerPhone")
    @Schema(description = "External providers only; always null for an account provider", nullable = true)
    private String providerPhone;

    @JsonProperty("providerOrganisation")
    @Schema(description = "External providers only; always null for an account provider", nullable = true)
    private String providerOrganisation;

    @JsonProperty("role")
    @Schema(description = "Care-team role; always DOCTOR for DERIVED rows",
            allowableValues = {"DOCTOR", "NURSE", "LAB", "PHARMACY", "SPECIALIST", "THERAPIST"})
    private String role;

    @JsonProperty("isPrimary")
    @Schema(description = "Primary member of the care team; always false for DERIVED rows. Legacy data can make two "
            + "members read as primary.")
    private Boolean isPrimary;

    @JsonProperty("specialization")
    @Schema(description = "Doctor's specialization; null when empty and for external providers", example = "Cardiology",
            nullable = true)
    private String specialization;

    @JsonProperty("facilityId")
    @Schema(description = "Facility linked to the assignment; null for DERIVED rows", example = "2", nullable = true)
    private Integer facilityId;

    @JsonProperty("facilityName")
    @Schema(description = "Name of the facility; null when there is none", nullable = true)
    private String facilityName;

    @JsonProperty("assignedAt")
    @Schema(description = "Clinic wall-clock date-time of the assignment, no offset; null for DERIVED rows",
            example = "2026-11-10T09:15:30", type = "string", nullable = true)
    private LocalDateTime assignedAt;
}
