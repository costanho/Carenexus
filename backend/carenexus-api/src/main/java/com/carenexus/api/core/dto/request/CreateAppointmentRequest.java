package com.carenexus.api.core.dto.request;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateAppointmentRequest {
    private Integer patientId;           // Required
    private Integer doctorId;             // Required
    private Integer facilityId;           // Optional
    private LocalDateTime scheduledAt;    // Required: ISO 8601 format
    private Integer durationMinutes;      // Optional, default 30
    private String type;                  // VIDEO, IN_PERSON, PHONE (default: IN_PERSON)
    private String reasonForVisit;        // Optional
    private String videoConsultationLink; // Optional, required if type=VIDEO
    private String notes;                 // Optional
}
