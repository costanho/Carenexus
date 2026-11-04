package com.carenexus.api.calls.dto;

import lombok.*;

import java.time.LocalDateTime;

/** POST /confirm-time: the clinic wall-clock time the doctor saw (must equal the stored scheduledAt). */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConfirmTimeRequest {
    private LocalDateTime scheduledAt;
}
