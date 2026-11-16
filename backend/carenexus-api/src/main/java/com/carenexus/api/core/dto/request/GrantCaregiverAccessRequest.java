package com.carenexus.api.core.dto.request;

import com.carenexus.api.auth.dto.request.RegisterRequest;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.util.Locale;

/**
 * POST /api/patients/me/caregiver-access: the patient (from the JWT) grants an existing caregiver account access.
 *
 * caregiverEmail and relationship are required. accessLevel defaults to VIEW_ONLY; FULL_ACCESS sets every
 * view/manage flag but never the communication flags. expiresAt is "YYYY-MM-DD" (end of that day) or an ISO
 * date-time, or null for no expiry. Flags default to false. canMessageCareTeam / canJoinCalls set true here are
 * approved by the patient in the same request. FULL_ACCESS stores every data flag true and VIEW_ONLY stores the write
 * flags false (normalised on save). Blank accessLevel / expiresAt count as absent.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "The patient grants an existing caregiver account access")
public class GrantCaregiverAccessRequest {

    @JsonProperty("caregiverEmail")
    @NotBlank(message = "Caregiver email is required")
    @Pattern(regexp = RegisterRequest.EMAIL_REGEX, message = "Please enter a valid email address")
    private String caregiverEmail;

    @JsonProperty("relationship")
    @NotBlank(message = "Relationship is required")
    @Size(max = 80, message = "Relationship must be at most 80 characters")
    private String relationship;

    @JsonProperty("accessLevel")
    @Pattern(regexp = CreateDependentRequest.ACCESS_LEVEL_PATTERN, message = CreateDependentRequest.ACCESS_LEVEL_MESSAGE)
    @Schema(description = "FULL_ACCESS, VIEW_ONLY, EDIT_ONLY or CUSTOM; default VIEW_ONLY")
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

    /** View the patient's invoices, payments and insurance claims. */
    @JsonProperty("canViewBilling")
    private Boolean canViewBilling;

    /** Pay invoices and manage payment methods and claims for the patient. */
    @JsonProperty("canManageBilling")
    private Boolean canManageBilling;

    @JsonProperty("canMessageCareTeam")
    private Boolean canMessageCareTeam;

    @JsonProperty("canJoinCalls")
    private Boolean canJoinCalls;

    // ==================== NORMALISING SETTERS (used by Jackson) ====================

    public void setCaregiverEmail(String caregiverEmail) {
        this.caregiverEmail = caregiverEmail == null ? null : caregiverEmail.trim().toLowerCase(Locale.ROOT);
    }

    public void setRelationship(String relationship) {
        this.relationship = CreateDependentRequest.trim(relationship);
    }

    public void setAccessLevel(String accessLevel) {
        String value = CreateDependentRequest.blankToNull(accessLevel);
        this.accessLevel = value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    public void setExpiresAt(String expiresAt) {
        this.expiresAt = CreateDependentRequest.blankToNull(expiresAt);
    }
}
