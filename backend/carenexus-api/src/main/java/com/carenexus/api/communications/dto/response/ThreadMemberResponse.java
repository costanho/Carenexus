package com.carenexus.api.communications.dto.response;

import lombok.*;


@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ThreadMemberResponse {

    private String systemId;

    private String name;

    private String role;

    private String label;
}
