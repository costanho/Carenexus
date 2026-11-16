package com.carenexus.api.referrals.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A referral as the caller sees it. Every field is always present; a nullable field is null, never omitted.
 * {@code allowedActions} and {@code outcomeNoteRequired} are computed for the caller.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "A referral of a patient to a specialist")
public class ReferralResponse {

    @Schema(description = "Referral id", example = "42")
    private Integer referralId;

    @Schema(description = "Patient system id", example = "PAT-00021")
    private String patientSystemId;

    @Schema(description = "Patient name, \"First Last\"", example = "Thomas Dube")
    private String patientName;

    @Schema(description = "Doctor who created the referral", example = "DOC-00002")
    private String referringDoctorSystemId;

    @Schema(description = "\"Dr. First Last\" of the referring doctor", example = "Dr. James Wilson")
    private String referringDoctorName;

    @Schema(description = "PLATFORM when a platform specialist is named, EXTERNAL for a specialist outside the "
            + "platform, UNASSIGNED otherwise", allowableValues = {"PLATFORM", "EXTERNAL", "UNASSIGNED"})
    private String specialistType;

    @Schema(description = "Platform specialist; null unless specialistType is PLATFORM", example = "DOC-00005",
            nullable = true)
    private String specialistSystemId;

    @Schema(description = "\"Dr. First Last\" of the platform specialist", nullable = true)
    private String specialistName;

    @Schema(description = "Specialization of the platform specialist", example = "Cardiology", nullable = true)
    private String specialistSpecialization;

    @Schema(description = "Name of the external specialist; null unless specialistType is EXTERNAL", nullable = true)
    private String externalSpecialistName;

    @Schema(description = "Hospital or practice of the external specialist", nullable = true)
    private String externalSpecialistOrganisation;

    @Schema(description = "Phone or email of the external specialist", nullable = true)
    private String externalSpecialistContact;

    @Schema(description = "Reason for referral")
    private String reason;

    @Schema(description = "Clinical summary written by the referring doctor", nullable = true)
    private String clinicalSummary;

    @Schema(description = "Priority", allowableValues = {"ROUTINE", "URGENT", "EMERGENCY"})
    private String priority;

    @Schema(description = "Workflow status", allowableValues = {"PENDING", "ACCEPTED", "DECLINED", "COMPLETED", "CANCELLED"})
    private String status;

    @Schema(description = "Consultation the referral was issued from", nullable = true)
    private Integer consultationId;

    @Schema(description = "Clinic calendar day the referral was issued", example = "2026-11-10")
    private LocalDate referralDate;

    @Schema(description = "Reason given when the referral was declined", nullable = true)
    private String declineReason;

    @Schema(description = "Note of the latest accept or complete that carried one", nullable = true)
    private String outcomeNote;

    @Schema(description = "Who recorded the latest decision on the specialist's behalf (referring doctor or "
            + "administrator); null when the named platform specialist acted", nullable = true)
    private String outcomeRecordedBySystemId;

    @Schema(description = "Display name of outcomeRecordedBySystemId", nullable = true)
    private String outcomeRecordedByName;

    @Schema(description = "When the referral was accepted or declined (clinic wall clock)", nullable = true)
    private LocalDateTime respondedAt;

    @Schema(description = "When the referral was completed (clinic wall clock)", nullable = true)
    private LocalDateTime completedAt;

    @Schema(description = "When the referral was created (clinic wall clock)")
    private LocalDateTime createdAt;

    @Schema(description = "Last change (clinic wall clock)")
    private LocalDateTime updatedAt;

    @Schema(description = "What the caller may do now, in this order: ACCEPT, DECLINE, COMPLETE, CANCEL, "
            + "ASSIGN_SPECIALIST")
    private List<String> allowedActions;

    @Schema(description = "True when the caller would act on the specialist's behalf: a note is then required on "
            + "accept and complete")
    private boolean outcomeNoteRequired;
}
