package com.carenexus.api.caregiveraccess.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Snapshot of a caregiver grant right after one lifecycle step, stored as JSONB in {@code consent_records.scope}
 * with exactly these keys (the stored document and the API field are the same shape).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "The grant as it was right after a consent-ledger step")
public class ConsentScopeResponse {

    @Schema(description = "Access level after the step")
    private String accessLevel;

    private boolean canViewRecords;
    private boolean canViewAppointments;
    private boolean canViewMedications;
    private boolean canViewLabResults;
    private boolean canViewImaging;
    private boolean canViewConsultations;
    private boolean canViewBilling;
    private boolean canScheduleAppointments;
    private boolean canManageCarePlan;
    private boolean canManageBilling;

    @Schema(description = "Requested: message the care team")
    private boolean canMessageCareTeam;

    @Schema(description = "Requested: join video calls")
    private boolean canJoinCalls;

    @Schema(description = "The patient's approval of messaging was in place after the step (false on REVOKED)")
    private boolean messagingApproved;

    @Schema(description = "The patient's approval of video calls was in place after the step (false on REVOKED)")
    private boolean callsApproved;

    @Schema(description = "The grant's expiry after the step (clinic local date-time); null = no expiry",
            nullable = true, example = "2027-11-10T23:59:59")
    private String expiresAt;

    @Schema(description = "The caregiver attested to being the parent or legal guardian")
    private boolean guardianAttested;
}
