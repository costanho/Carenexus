package com.carenexus.api.communications.dto.response;

import lombok.*;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageResponse {

    private Integer messageId;

    private Integer threadId;

    private String clientMessageId;

    private String senderSystemId;

    private String senderName;

    private String senderRole;

    private String senderLabel;

    private String body;

    private Boolean urgent;

    private Instant sentAt;

    private Boolean mine;
}
