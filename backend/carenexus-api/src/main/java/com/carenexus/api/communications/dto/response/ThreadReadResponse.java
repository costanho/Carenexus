package com.carenexus.api.communications.dto.response;

import lombok.*;


@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ThreadReadResponse {

    private Integer lastReadMessageId;

    private Long unreadCount;
}
