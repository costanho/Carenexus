package com.carenexus.api.core.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

/**
 * POST /api/patients/me/caregiver-access: the patient (from the JWT) grants an existing caregiver account access.
 *
 * caregiverEmail and relationship are required. accessLevel defaults to VIEW_ONLY; FULL_ACCESS sets every
 * view/manage flag but never the communication flags. expiresAt is "YYYY-MM-DD" (end of that day) or an ISO
 * date-time, or null for no expiry. Flags default to false. canMessageCareTeam / canJoinCalls set true here are
 * approved by the patient in the same request.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GrantCaregiverAccessRequest {

    @JsonProperty("caregiverEmail")
    private String caregiverEmail;

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

    @JsonProperty("canViewConsultations")
    private Boolean canViewConsultations;

    @JsonProperty("canScheduleAppointments")
    private Boolean canScheduleAppointments;

    @JsonProperty("canManageCarePlan")
    private Boolean canManageCarePlan;

    @JsonProperty("canMessageCareTeam")
    private Boolean canMessageCareTeam;

    @JsonProperty("canJoinCalls")
    private Boolean canJoinCalls;
}
