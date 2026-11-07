package com.carenexus.api.communications.dto.request;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SendMessageRequest {

    private String clientMessageId;

    private String body;

    private Boolean urgent;
}
