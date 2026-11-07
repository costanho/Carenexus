package com.carenexus.api.communications.dto.response;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecipientsResponse {

    private String patientSystemId;

    private String patientName;

    private List<RecipientResponse> recipients;
}
