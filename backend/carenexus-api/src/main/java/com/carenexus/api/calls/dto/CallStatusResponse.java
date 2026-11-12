package com.carenexus.api.calls.dto;

import lombok.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/** GET /api/appointments/{id}/call and every call POST except /token. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CallStatusResponse {
    private Integer appointmentId;
    private String callStatus;
    private Instant scheduledStartUtc;
    private Instant joinWindowOpensAt;
    private Instant joinWindowClosesAt;
    private Instant serverTime;
    private boolean joinWindowOpen;
    /** The appointment's stored clinic wall-clock time (sent back as the confirm-time body). */
    private LocalDateTime scheduledAtLocal;
    private String patientName;
    private String doctorName;
    private String myRole;
    private CallParticipantResponse myParticipant;
    private List<CallParticipantResponse> participants;
    private boolean canStart;
    private boolean canEnd;
    private boolean canRequestJoin;
    private boolean canConfirmTime;
    private boolean removalPending;
}
