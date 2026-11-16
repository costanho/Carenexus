package com.carenexus.api.core.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "An account profile; patient fields are null for other roles")
public class UserProfileResponse {

    @JsonProperty("userId")
    @Schema(description = "Numeric account id (legacy; display systemId)")
    private Integer userId;

    @JsonProperty("systemId")
    @Schema(description = "The account's system id", example = "PAT-00001")
    private String systemId;

    @JsonProperty("firstName")
    private String firstName;

    @JsonProperty("lastName")
    private String lastName;

    @JsonProperty("phone")
    @Schema(description = "Account phone; for a CAREGIVER without one, the phone of the caregiver record")
    private String phone;

    @JsonProperty("email")
    private String email;

    @JsonProperty("role")
    private String role;

    @JsonProperty("isActive")
    private Boolean isActive;

    @JsonProperty("patientSystemId")
    private String patientSystemId;

    @JsonProperty("dateOfBirth")
    private LocalDate dateOfBirth;

    @JsonProperty("gender")
    private String gender;

    @JsonProperty("bloodType")
    private String bloodType;

    @JsonProperty("allergies")
    @Schema(description = "PATIENT only: a string holding a JSON array of strings; null when never recorded or "
            + "hidden from the caller")
    private String allergies;

    @JsonProperty("chronicConditions")
    private String chronicConditions;

    @JsonProperty("healthStatus")
    @Schema(description = "PATIENT only: STABLE, MONITOR or CRITICAL; null only when hidden from the caller")
    private String healthStatus;

    @JsonProperty("emergencyContacts")
    @Schema(description = "PATIENT only (empty when none, null for other roles); primary first, then oldest first")
    private List<EmergencyContactResponse> emergencyContacts;

    @JsonProperty("relationship")
    @Schema(description = "CAREGIVER accounts only: the caregiver's own description of the relationship "
            + "(read-only); null when not recorded and for every other role")
    private String relationship;

    @JsonProperty("createdAt")
    private LocalDateTime createdAt;

    @JsonProperty("updatedAt")
    private LocalDateTime updatedAt;
}
