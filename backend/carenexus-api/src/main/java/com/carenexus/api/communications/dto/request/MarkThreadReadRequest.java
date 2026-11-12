package com.carenexus.api.communications.dto.request;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarkThreadReadRequest {

    private Integer lastReadMessageId;
}
