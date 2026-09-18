package com.carenexus.api.core.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateConsultationRequest {

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
}
