package com.carenexus.api.caregiveraccess.dto.request;

import com.carenexus.api.auth.dto.request.RegisterRequest;
import com.carenexus.api.core.dto.request.CreateDependentRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

/**
 * POST /api/caregiver/access-requests: the caregiver (from the JWT) asks the patient with this email for access.
 *
 * Text is normalised by the setters before validation: the email is trimmed and lower-cased, relationship and
 * message are trimmed, accessLevel is trimmed and upper-cased; a blank value counts as absent. Absent flags are
 * false. The flags are a REQUEST: the patient decides the final permissions when approving.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "A caregiver's request for access to an existing patient")
public class CreateAccessRequestRequest {

    @NotBlank(message = "Patient email is required")
    @Pattern(regexp = RegisterRequest.EMAIL_REGEX, message = "Please enter a valid email address")
    @Schema(description = "The patient's account email (at most 150 characters)", example = "thomas.dube@example.com",
            maxLength = 150, requiredMode = Schema.RequiredMode.REQUIRED)
    private String patientEmail;

    @NotBlank(message = "Relationship is required")
    @Size(max = 80, message = "Relationship must be at most 80 characters")
    @Schema(description = "How the caregiver is related to the patient; becomes the grant's relationship",
            example = "daughter", maxLength = 80, requiredMode = Schema.RequiredMode.REQUIRED)
    private String relationship;

    @Pattern(regexp = CreateDependentRequest.ACCESS_LEVEL_PATTERN, message = CreateDependentRequest.ACCESS_LEVEL_MESSAGE)
    @Schema(description = "Requested level: FULL_ACCESS, VIEW_ONLY, EDIT_ONLY or CUSTOM; default VIEW_ONLY")
    private String accessLevel;

    private Boolean canViewRecords;
    private Boolean canViewAppointments;
    private Boolean canViewMedications;
    private Boolean canViewLabResults;
    private Boolean canViewImaging;
    private Boolean canViewConsultations;
    private Boolean canViewBilling;
    private Boolean canScheduleAppointments;
    private Boolean canManageCarePlan;
    private Boolean canManageBilling;

    @Schema(description = "Request to message the care team; takes effect only if the patient switches it on when approving")
    private Boolean canMessageCareTeam;

    @Schema(description = "Request to join video calls; takes effect only if the patient switches it on when approving")
    private Boolean canJoinCalls;

    @Size(max = 500, message = "Message must be at most 500 characters")
    @Schema(description = "Optional note to the patient", maxLength = 500)
    private String message;

    // ==================== NORMALISING SETTERS (used by Jackson) ====================

    public void setPatientEmail(String patientEmail) {
        String value = RequestTexts.clean(patientEmail);
        this.patientEmail = value == null ? null : value.toLowerCase(java.util.Locale.ROOT);
    }

    public void setRelationship(String relationship) {
        this.relationship = RequestTexts.clean(relationship);
    }

    public void setAccessLevel(String accessLevel) {
        this.accessLevel = RequestTexts.cleanUpper(accessLevel);
    }

    public void setMessage(String message) {
        this.message = RequestTexts.clean(message);
    }
}
