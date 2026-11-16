package com.carenexus.api.core.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "One row of a doctor's schedule: an APPOINTMENT row (one per appointment) or, in the legacy "
        + "arrays only, a CONSULTATION row placed after the appointment it documents")
public class ScheduleEntryResponse {

    @JsonProperty("entryId")
    private String entryId;

    @JsonProperty("type")
    private String type;

    @JsonProperty("appointmentId")
    private Integer appointmentId;

    @JsonProperty("consultationId")
    private Integer consultationId;

    @JsonProperty("patientSystemId")
    private String patientSystemId;

    @JsonProperty("patientName")
    private String patientName;

    @JsonProperty("doctorSystemId")
    private String doctorSystemId;

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

    /** Legacy field kept so clients do not break. Always null: calls are joined via callJoinRoute. */
    @JsonProperty("videoConsultationLink")
    private String videoConsultationLink;

    @Schema(description = "APPOINTMENT rows: the first automatic reminder went out for the current start time")
    @JsonProperty("reminderSent")
    private Boolean reminderSent;

    @Schema(description = "APPOINTMENT rows: clinic wall-clock time of the last manual reminder the doctor sent; "
            + "null when never", example = "2026-11-19T16:05:41", nullable = true)
    @JsonProperty("reminderSentAt")
    private LocalDateTime reminderSentAt;

    @JsonProperty("notes")
    private String notes;

    @Schema(description = "APPOINTMENT rows: name of the appointment's facility; null without a facility",
            example = "CareNexus Central Clinic", nullable = true)
    @JsonProperty("facilityName")
    private String facilityName;

    @Schema(description = "APPOINTMENT rows: the number to call for a PHONE appointment; null for other types",
            example = "+263771234567", nullable = true)
    @JsonProperty("contactPhone")
    private String contactPhone;

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

    @Schema(description = "APPOINTMENT rows: status of the linked non-cancelled consultation (DRAFT, IN_PROGRESS, "
            + "COMPLETED), null when there is none. CONSULTATION rows: the consultation's status", nullable = true)
    @JsonProperty("consultationStatus")
    private String consultationStatus;

    @Schema(description = "APPOINTMENT rows: still SCHEDULED or RESCHEDULED although it ended more than "
            + "appointments.needs-outcome-after-minutes ago. Always false on CONSULTATION rows")
    @JsonProperty("needsOutcome")
    @Builder.Default
    private boolean needsOutcome = false;

    @JsonProperty("startedAt")
    private LocalDateTime startedAt;

    @JsonProperty("completedAt")
    private LocalDateTime completedAt;

    @JsonProperty("createdAt")
    private LocalDateTime createdAt;

    @JsonProperty("updatedAt")
    private LocalDateTime updatedAt;

    @JsonProperty("callStatus")
    private String callStatus;

    @JsonProperty("callJoinRoute")
    private String callJoinRoute;

    @JsonProperty("joinWindowOpensAt")
    private Instant joinWindowOpensAt;

    @JsonProperty("joinWindowClosesAt")
    private Instant joinWindowClosesAt;
}
