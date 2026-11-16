package com.carenexus.api.communications.dto.request;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateThreadRequest {

    private String patientSystemId;

    private String type;

    private String subject;

    private List<String> memberSystemIds;

    private String clientThreadId;
}
