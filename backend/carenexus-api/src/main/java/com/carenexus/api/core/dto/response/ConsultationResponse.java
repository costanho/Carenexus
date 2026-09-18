package com.carenexus.api.core.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConsultationResponse {

    @JsonProperty("consultationId")
    private Integer consultationId;

    @JsonProperty("appointmentId")
    private Integer appointmentId;

    @JsonProperty("patientId")
    private Integer patientId;

    @JsonProperty("doctorId")
    private Integer doctorId;

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
    private String status;

    @JsonProperty("startedAt")
    private LocalDateTime startedAt;

    @JsonProperty("completedAt")
    private LocalDateTime completedAt;

    @JsonProperty("createdAt")
    private LocalDateTime createdAt;
}
