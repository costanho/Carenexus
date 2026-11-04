package com.carenexus.api.communications.dto.response;

import lombok.*;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LastMessageResponse {

    private Integer messageId;

    private String senderSystemId;

    private String senderLabel;

    private String preview;

    private Boolean urgent;

    private Instant sentAt;
}
