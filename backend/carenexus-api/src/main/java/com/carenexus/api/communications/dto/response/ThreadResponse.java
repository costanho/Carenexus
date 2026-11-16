package com.carenexus.api.communications.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.Instant;
import java.util.List;

/** A conversation as the caller sees it. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ThreadResponse {

    private Integer threadId;

    @Schema(description = "PRIVATE, CARE_TEAM or SUPPORT", example = "CARE_TEAM")
    private String type;

    @Schema(description = "At most 200 characters; null when none was given")
    private String subject;

    @Schema(description = "Patient the conversation is about; null for SUPPORT conversations", nullable = true)
    private String patientSystemId;

    @Schema(description = "\"First Last\" of the patient; null for SUPPORT conversations", nullable = true)
    private String patientName;

    @Schema(description = "Active members only")
    private List<ThreadMemberResponse> members;

    @Schema(description = "Newest message visible to the caller", nullable = true)
    private LastMessageResponse lastMessage;

    @Schema(description = "Visible messages after the caller's read position that the caller did not send")
    private Long unreadCount;

    @Schema(description = "An unread urgent message exists; always false for SUPPORT conversations")
    private Boolean hasUrgentUnread;

    @Schema(description = "The caller's own archive flag")
    private Boolean archived;

    private Instant lastMessageAt;

    private Instant createdAt;

    @Schema(description = "CARE_TEAM conversation and the caller is the patient or a doctor; false for PRIVATE and SUPPORT")
    private Boolean canManageMembers;

    @Schema(description = "True only for a SUPPORT conversation an administrator closed (no more messages can be sent)")
    private Boolean closed;

    @Schema(description = "When the SUPPORT conversation was closed; null while open", nullable = true)
    private Instant closedAt;
}
