package com.carenexus.api.communications.dto.response;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ThreadMessagesResponse {

    private Integer threadId;

    private List<MessageResponse> messages;

    private Boolean hasMore;

    private Integer lastReadMessageId;
}
