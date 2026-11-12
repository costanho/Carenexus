package com.carenexus.api.caregiveraccess.dto.request;

import com.carenexus.api.core.dto.request.CreateDependentRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.*;

/**
 * POST /api/patients/me/caregiver-access/requests/{requestId}/approve: the patient decides the final permissions.
 *
 * The body is authoritative: nothing is copied from what the caregiver requested except the relationship. An absent
 * flag is false (not the requested value); flags are normalised to the level. A communication flag sent true is
 * granted and approved by the patient in the same save. expiresAt is "YYYY-MM-DD" (23:59:59 of that clinic day), an
 * ISO local date-time or an ISO date-time with offset; absent, null or blank = no expiry.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "The permissions the patient grants when approving an access request")
public class ApproveAccessRequestRequest {

    @NotBlank(message = "Access level is required")
    @Pattern(regexp = CreateDependentRequest.ACCESS_LEVEL_PATTERN, message = CreateDependentRequest.ACCESS_LEVEL_MESSAGE)
    @Schema(description = "FULL_ACCESS, VIEW_ONLY, EDIT_ONLY or CUSTOM", requiredMode = Schema.RequiredMode.REQUIRED)
    private String accessLevel;

    @Schema(description = "Access end: YYYY-MM-DD (end of that clinic day), ISO local date-time, or ISO date-time with "
            + "offset; must be in the future; absent = no expiry", example = "2027-11-10")
    private String expiresAt;

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

    @Schema(description = "true = messaging the care team is granted and approved now")
    private Boolean canMessageCareTeam;

    @Schema(description = "true = joining video calls is granted and approved now")
    private Boolean canJoinCalls;

    // ==================== NORMALISING SETTERS (used by Jackson) ====================

    public void setAccessLevel(String accessLevel) {
        this.accessLevel = RequestTexts.cleanUpper(accessLevel);
    }

    public void setExpiresAt(String expiresAt) {
        this.expiresAt = RequestTexts.clean(expiresAt);
    }
}
