package com.carenexus.api.core.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.util.List;

/**
 * POST /api/caregiver/dependents: a caregiver creates a dependent patient account and their own access grant
 * in one transaction. The caregiver comes from the JWT, never from this body.
 *
 * dateOfBirth is "YYYY-MM-DD"; expiresAt is "YYYY-MM-DD" (end of that day) or an ISO date-time, or null for no expiry.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateDependentRequest {

    // Account
    @JsonProperty("firstName")
    private String firstName;

    @JsonProperty("lastName")
    private String lastName;

    @JsonProperty("email")
    private String email;

    @JsonProperty("phone")
    private String phone;

    @JsonProperty("password")
    private String password;

    // Health profile
    @JsonProperty("dateOfBirth")
    private String dateOfBirth;

    @JsonProperty("gender")
    private String gender;

    @JsonProperty("bloodType")
    private String bloodType;

    @JsonProperty("healthStatus")
    private String healthStatus;

    @JsonProperty("allergies")
    private List<String> allergies;

    @JsonProperty("chronicConditions")
    private List<String> chronicConditions;

    // Access grant
    @JsonProperty("relationship")
    private String relationship;

    @JsonProperty("accessLevel")
    private String accessLevel;

    @JsonProperty("expiresAt")
    private String expiresAt;

    @JsonProperty("canViewRecords")
    private Boolean canViewRecords;

    @JsonProperty("canViewAppointments")
    private Boolean canViewAppointments;

    @JsonProperty("canViewMedications")
    private Boolean canViewMedications;

    @JsonProperty("canViewLabResults")
    private Boolean canViewLabResults;

    @JsonProperty("canViewImaging")
    private Boolean canViewImaging;

    @JsonProperty("canMessageCareTeam")
    private Boolean canMessageCareTeam;

    @JsonProperty("canViewConsultations")
    private Boolean canViewConsultations;

    @JsonProperty("canScheduleAppointments")
    private Boolean canScheduleAppointments;

    @JsonProperty("canManageCarePlan")
    private Boolean canManageCarePlan;
}
