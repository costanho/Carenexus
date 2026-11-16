package com.carenexus.api.core.dto.response;

import lombok.*;

import java.time.Instant;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppointmentResponse {
    private Integer appointmentId;
    private String patientSystemId;
    private String patientName;
    private String doctorSystemId;
    private Integer facilityId;
    private LocalDateTime scheduledAt;
    private Integer durationMinutes;
    private String type;
    private String status;
    private String reasonForVisit;
    /** Legacy field kept so clients do not break. Always null: calls are joined via {@link #callJoinRoute}. */
    private String videoConsultationLink;
    private Boolean reminderSent;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** Null unless type VIDEO with a call session; one of the call_sessions statuses. */
    private String callStatus;
    /** "/dashboard/call/{appointmentId}" when callStatus != null, else null. Never a provider URL. */
    private String callJoinRoute;
    /** Null when NEEDS_TIME_CONFIRMATION or no session. */
    private Instant joinWindowOpensAt;
    private Instant joinWindowClosesAt;
}
