package com.carenexus.api.core.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateDependentAccessRequest {

    // Used by PATCH /api/caregiver/dependents/{accessId} (caregiver: may only narrow access) and
    // PATCH /api/patients/me/caregivers/{accessId} (patient/admin: may grant or narrow).

    @JsonProperty("accessLevel")
    private String accessLevel;

    /**
     * "YYYY-MM-DD" (end of that day), an ISO date-time, or null to leave unchanged.
     * An empty string removes the expiry (patient/admin only; for a caregiver that would widen access).
     */
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
