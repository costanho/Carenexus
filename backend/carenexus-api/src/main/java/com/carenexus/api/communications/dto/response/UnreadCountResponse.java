package com.carenexus.api.communications.dto.response;

import lombok.*;


@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UnreadCountResponse {

    private Long unreadCount;

    private Long unreadMessageCount;
}
