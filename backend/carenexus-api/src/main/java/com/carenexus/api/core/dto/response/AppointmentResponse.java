package com.carenexus.api.core.dto.response;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppointmentResponse {
    private Integer appointmentId;
    private Integer patientId;
    private Integer doctorId;
    private Integer facilityId;
    private LocalDateTime scheduledAt;
    private Integer durationMinutes;
    private String type;
    private String status;
    private String reasonForVisit;
    private String videoConsultationLink;
    private Boolean reminderSent;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
