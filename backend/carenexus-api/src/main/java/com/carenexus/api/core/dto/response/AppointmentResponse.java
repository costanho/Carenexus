package com.carenexus.api.core.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * One appointment as returned by every appointment endpoint. Null fields are serialised as null (never omitted).
 * Built by AppointmentService.toResponses (batch lookup of names, facility, call info and contact phone).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "An appointment")
public class AppointmentResponse {
    @Schema(example = "412")
    private Integer appointmentId;

    @Schema(example = "PAT-00001")
    private String patientSystemId;

    @Schema(description = "\"First Last\" of the patient; null only if the account is missing", example = "Thomas Davis",
            nullable = true)
    private String patientName;

    @Schema(example = "DOC-00002")
    private String doctorSystemId;

    @Schema(description = "Doctor display name with title; null only if the account is missing", example = "Dr. James Wilson",
            nullable = true)
    private String doctorName;

    @Schema(description = "Facility of the visit", example = "1", nullable = true)
    private Integer facilityId;

    @Schema(description = "Facility name; null when facilityId is null", example = "CareNexus Central Clinic", nullable = true)
    private String facilityName;

    @Schema(description = "Facility address; null when facilityId is null or the facility has no address", nullable = true)
    private String facilityAddress;

    @Schema(description = "Start, clinic wall-clock time without offset", example = "2026-11-20T10:30:00")
    private LocalDateTime scheduledAt;

    @Schema(example = "30")
    private Integer durationMinutes;

    @Schema(description = "IN_PERSON, VIDEO or PHONE", example = "IN_PERSON")
    private String type;

    @Schema(description = "SCHEDULED, RESCHEDULED, COMPLETED, CANCELLED or NO_SHOW", example = "SCHEDULED")
    private String status;

    @Schema(nullable = true)
    private String reasonForVisit;

    /** Legacy field kept so clients do not break. Always null: calls are joined via {@link #callJoinRoute}. */
    @Schema(description = "Legacy, always null", nullable = true, deprecated = true)
    private String videoConsultationLink;

    @Schema(description = "PHONE only: number the doctor calls. Visible to the appointment's doctor, the patient, "
            + "a caregiver who may view appointments and admins; null for everyone else", nullable = true,
            example = "+263771234567")
    private String contactPhone;

    @Schema(description = "An automatic reminder went out for the current start time")
    private Boolean reminderSent;

    @Schema(nullable = true)
    private String notes;

    @Schema(description = "Server local date-time", example = "2026-11-10T09:15:27.105331")
    private LocalDateTime createdAt;

    @Schema(description = "Server local date-time", example = "2026-11-10T09:15:27.105331")
    private LocalDateTime updatedAt;

    /** Null unless type VIDEO with a call session; one of the call_sessions statuses. */
    @Schema(description = "VIDEO only: PREPARING, READY, FAILED, LIVE, ENDED, CANCELLED or NEEDS_TIME_CONFIRMATION",
            nullable = true)
    private String callStatus;

    /** "/dashboard/call/{appointmentId}" when callStatus != null, else null. Never a provider URL. */
    @Schema(description = "/dashboard/call/{appointmentId} when callStatus is set", nullable = true)
    private String callJoinRoute;

    /** Null when NEEDS_TIME_CONFIRMATION or no session. */
    @Schema(description = "ISO instant (UTC)", nullable = true)
    private Instant joinWindowOpensAt;

    @Schema(description = "ISO instant (UTC)", nullable = true)
    private Instant joinWindowClosesAt;
}
