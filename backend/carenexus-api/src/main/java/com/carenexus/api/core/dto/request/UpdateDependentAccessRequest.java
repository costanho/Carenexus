package com.carenexus.api.core.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import lombok.*;

import java.util.Locale;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Change of a caregiver grant; every field is optional and absent or null means unchanged")
public class UpdateDependentAccessRequest {

    // Used by PATCH /api/caregiver/dependents/{accessId} (caregiver: may only narrow access) and
    // PATCH /api/patients/me/caregivers/{accessId} and PATCH /api/patients/me/caregiver-access/{accessId}
    // (patient/admin: may grant or narrow). Setting canMessageCareTeam / canJoinCalls to true approves them only
    // when the caller is the patient; admins never approve, caregivers can never turn them on.
    // The resulting grant is normalised on save: FULL_ACCESS stores every data flag true, VIEW_ONLY stores the
    // three write flags false (flags sent against that level are ignored).

    /** Blank means unchanged. */
    @JsonProperty("accessLevel")
    @Pattern(regexp = CreateDependentRequest.ACCESS_LEVEL_PATTERN, message = CreateDependentRequest.ACCESS_LEVEL_MESSAGE)
    @Schema(description = "FULL_ACCESS, VIEW_ONLY, EDIT_ONLY or CUSTOM; blank or absent = unchanged")
    private String accessLevel;

    /**
     * "YYYY-MM-DD" (end of that day), an ISO date-time, or null to leave unchanged.
     * An empty string removes the expiry (patient/admin only; for a caregiver that would widen access).
     */
    @JsonProperty("expiresAt")
    @Schema(description = "YYYY-MM-DD (end of that day) or an ISO date-time; \"\" removes the expiry (patient only)")
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

    /** Requested permission to join video calls (communication permissions need the patient's approval). */
    @JsonProperty("canJoinCalls")
    private Boolean canJoinCalls;

    @JsonProperty("canViewConsultations")
    private Boolean canViewConsultations;

    @JsonProperty("canScheduleAppointments")
    private Boolean canScheduleAppointments;

    @JsonProperty("canManageCarePlan")
    private Boolean canManageCarePlan;

    /** View the patient's invoices, payments and insurance claims. */
    @JsonProperty("canViewBilling")
    private Boolean canViewBilling;

    /** Pay invoices and manage payment methods and claims for the patient. */
    @JsonProperty("canManageBilling")
    private Boolean canManageBilling;

    /** A blank level means "unchanged" (stored as null); otherwise trimmed and upper-cased. */
    public void setAccessLevel(String accessLevel) {
        String value = CreateDependentRequest.blankToNull(accessLevel);
        this.accessLevel = value == null ? null : value.toUpperCase(Locale.ROOT);
    }
}
