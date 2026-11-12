package com.carenexus.api.core.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "A consultation. Every field is present; absent values are null. Date-times are clinic "
        + "wall-clock time without offset.")
public class ConsultationResponse {

    @JsonProperty("consultationId")
    private Integer consultationId;

    @JsonProperty("appointmentId")
    private Integer appointmentId;

    /** Type of the linked appointment: IN_PERSON | VIDEO | PHONE (null when unknown). */
    @JsonProperty("appointmentType")
    @Schema(description = "Type of the linked appointment: IN_PERSON, VIDEO, PHONE")
    private String appointmentType;

    /** Current status of the linked appointment: SCHEDULED | RESCHEDULED | COMPLETED | CANCELLED | NO_SHOW. */
    @JsonProperty("appointmentStatus")
    @Schema(description = "Current status of the linked appointment: SCHEDULED, RESCHEDULED, COMPLETED, CANCELLED, NO_SHOW")
    private String appointmentStatus;

    @JsonProperty("patientSystemId")
    private String patientSystemId;

    /** Always null (kept for compatibility); clients resolve names themselves. */
    @JsonProperty("patientName")
    @Schema(description = "Always null; kept for compatibility")
    private String patientName;

    @JsonProperty("doctorSystemId")
    private String doctorSystemId;

    @JsonProperty("chiefComplaint")
    private String chiefComplaint;

    @JsonProperty("historyOfPresentIllness")
    private String historyOfPresentIllness;

    @JsonProperty("physicalExamination")
    private String physicalExamination;

    @JsonProperty("clinicalNotes")
    private String clinicalNotes;

    @JsonProperty("diagnosis")
    private String diagnosis;

    @JsonProperty("treatmentPlan")
    private String treatmentPlan;

    @JsonProperty("followUpInstructions")
    private String followUpInstructions;

    @JsonProperty("followUpRequired")
    private Boolean followUpRequired;

    @JsonProperty("followUpDate")
    private LocalDate followUpDate;

    @JsonProperty("durationMinutes")
    private Integer durationMinutes;

    @JsonProperty("status")
    @Schema(description = "DRAFT, IN_PROGRESS, COMPLETED or CANCELLED")
    private String status;

    @JsonProperty("startedAt")
    @Schema(description = "Set once, when the consultation first becomes IN_PROGRESS")
    private LocalDateTime startedAt;

    @JsonProperty("completedAt")
    @Schema(description = "Set once, on the transition to COMPLETED")
    private LocalDateTime completedAt;

    @JsonProperty("createdAt")
    private LocalDateTime createdAt;

    /**
     * Id of the non-deleted CONSULT medical record linked to this consultation (lowest id if several); null when
     * there is none. Opening the record is authorised separately by the medical-records endpoints.
     */
    @JsonProperty("medicalRecordId")
    @Schema(description = "Id of the non-deleted CONSULT medical record of this consultation (lowest id if several); "
            + "null when there is none")
    private Integer medicalRecordId;
}
