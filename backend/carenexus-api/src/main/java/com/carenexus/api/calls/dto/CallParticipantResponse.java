package com.carenexus.api.calls.dto;

import lombok.*;

import java.time.Instant;

/** A call participant as shown to clients. No system ids, provider identities or room names. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CallParticipantResponse {
    private Long participantId;
    private String role;
    private String label;
    private boolean actingForPatient;
    private String status;
    private Instant requestedAt;
    private Instant admittedAt;
    private boolean connected;
    private boolean removalPending;
    private boolean me;
}
