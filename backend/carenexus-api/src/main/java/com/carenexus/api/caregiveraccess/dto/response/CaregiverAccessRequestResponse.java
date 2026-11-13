package com.carenexus.api.caregiveraccess.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One access request, in the caregiver view ({@code /api/caregiver/access-requests}) or the patient view
 * ({@code /api/patients/me/caregiver-access/requests}). Level and flags are what the caregiver REQUESTED; the
 * permissions actually granted are on the grant ({@code accessId}). Until a request is APPROVED the caregiver view
 * never contains the patient's system id or name. Date-times are clinic wall-clock local date-times.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "A caregiver's access request")
public class CaregiverAccessRequestResponse {

    @Schema(description = "Request id", example = "17")
    private Integer requestId;

    @Schema(description = "PENDING, APPROVED, DECLINED, CANCELLED or EXPIRED", example = "PENDING")
    private String status;

    @Schema(description = "The requesting caregiver", example = "CGV-00003")
    private String caregiverSystemId;

    @Schema(description = "Patient view only (\"First Last\"); null in the caregiver view", nullable = true)
    private String caregiverName;

    @Schema(description = "Patient view only; null in the caregiver view", nullable = true)
    private String caregiverEmail;

    @Schema(description = "The email the caregiver typed, trimmed and lower-cased")
    private String patientEmail;

    @Schema(description = "Patient view: the caller. Caregiver view: null unless APPROVED", nullable = true)
    private String patientSystemId;

    @Schema(description = "Caregiver view: \"First Last\" once APPROVED, else null. Patient view: null", nullable = true)
    private String patientName;

    @Schema(description = "As stated by the caregiver; becomes the grant's relationship on approval")
    private String relationship;

    @Schema(description = "REQUESTED level: FULL_ACCESS, VIEW_ONLY, EDIT_ONLY or CUSTOM")
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
    private Boolean canMessageCareTeam;
    private Boolean canJoinCalls;

    @Schema(description = "The caregiver's note to the patient", nullable = true)
    private String message;

    @Schema(description = "The patient's reason; set only when DECLINED with a reason", nullable = true)
    private String declineReason;

    @Schema(description = "The grant created or reused by the approval; set only when APPROVED", nullable = true)
    private Integer accessId;

    @Schema(description = "When the request was made (clinic local date-time)")
    private LocalDateTime createdAt;

    @Schema(description = "createdAt + 30 days: from then on a still pending request may be marked EXPIRED")
    private LocalDateTime requestExpiresAt;

    @Schema(description = "When it was approved, declined, cancelled or expired; null while PENDING", nullable = true)
    private LocalDateTime respondedAt;
}
