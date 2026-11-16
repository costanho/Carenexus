package com.carenexus.api.communications.dto.response;

import lombok.*;

import java.time.Instant;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ThreadResponse {

    private Integer threadId;

    private String type;

    private String subject;

    private String patientSystemId;

    private String patientName;

    private List<ThreadMemberResponse> members;

    private LastMessageResponse lastMessage;

    private Long unreadCount;

    private Boolean hasUrgentUnread;

    private Boolean archived;

    private Instant lastMessageAt;

    private Instant createdAt;

    private Boolean canManageMembers;
}
