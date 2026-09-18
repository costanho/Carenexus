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
public class ScheduleEntryResponse {

    @JsonProperty("entryId")
    private String entryId;

    @JsonProperty("type")
    private String type;

    @JsonProperty("appointmentId")
    private Integer appointmentId;

    @JsonProperty("consultationId")
    private Integer consultationId;

    @JsonProperty("patientId")
    private Integer patientId;

    @JsonProperty("patientName")
    private String patientName;

    @JsonProperty("doctorId")
    private Integer doctorId;

    @JsonProperty("scheduledAt")
    private LocalDateTime scheduledAt;

    @JsonProperty("appointmentType")
    private String appointmentType;

    @JsonProperty("appointmentStatus")
    private String appointmentStatus;

    @JsonProperty("durationMinutes")
    private Integer durationMinutes;

    @JsonProperty("reasonForVisit")
    private String reasonForVisit;

    @JsonProperty("videoConsultationLink")
    private String videoConsultationLink;

    @JsonProperty("reminderSent")
    private Boolean reminderSent;

    @JsonProperty("notes")
    private String notes;

    @JsonProperty("chiefComplaint")
    private String chiefComplaint;

    @JsonProperty("diagnosis")
    private String diagnosis;

    @JsonProperty("treatmentPlan")
    private String treatmentPlan;

    @JsonProperty("followUpRequired")
    private Boolean followUpRequired;

    @JsonProperty("followUpDate")
    private LocalDate followUpDate;

    @JsonProperty("consultationStatus")
    private String consultationStatus;

    @JsonProperty("startedAt")
    private LocalDateTime startedAt;

    @JsonProperty("completedAt")
    private LocalDateTime completedAt;

    @JsonProperty("createdAt")
    private LocalDateTime createdAt;

    @JsonProperty("updatedAt")
    private LocalDateTime updatedAt;
}
