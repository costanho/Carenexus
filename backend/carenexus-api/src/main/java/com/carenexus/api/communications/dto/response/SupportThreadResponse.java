package com.carenexus.api.communications.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.Instant;

/** A support conversation as an administrator sees it in the support inbox. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(name = "SupportThread", description = "A support conversation in the administrator inbox")
public class SupportThreadResponse {

    @Schema(description = "Open it with the conversation endpoints (GET /api/communications/threads/{threadId}, ...)")
    private Integer threadId;

    @Schema(nullable = true)
    private String subject;

    @Schema(description = "The user who opened the conversation", example = "CGV-00001")
    private String openerSystemId;

    @Schema(description = "\"First Last\" of the opener (the system id when the name is empty)")
    private String openerName;

    @Schema(description = "PATIENT, DOCTOR or CAREGIVER")
    private String openerRole;

    @Schema(description = "True while not closed")
    private Boolean open;

    @Schema(nullable = true)
    private Instant closedAt;

    @Schema(description = "Newest message of the conversation", nullable = true)
    private LastMessageResponse lastMessage;

    @Schema(nullable = true)
    private Instant lastMessageAt;

    private Instant createdAt;

    @Schema(description = "A message not sent by the calling administrator is newer than that administrator's read "
            + "position (0 when the administrator never opened the conversation)")
    private Boolean unread;
}
