package com.carenexus.api.calls.dto;

import lombok.*;

/** POST /join-request. actingForPatient is for caregivers only (ignored for patients). */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JoinRequestBody {
    private Boolean actingForPatient;
}
