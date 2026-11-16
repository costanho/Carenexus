package com.carenexus.api.core.dto.request;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RescheduleAppointmentRequest {
    private LocalDateTime scheduledAt;    // Required: new local date/time, e.g. 2026-11-20T10:30:00
    private Integer durationMinutes;      // Optional, keeps the current duration when omitted
}
