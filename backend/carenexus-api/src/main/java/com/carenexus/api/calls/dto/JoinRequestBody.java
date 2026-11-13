package com.carenexus.api.calls.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

/** POST /join-request (body optional). actingForPatient is for caregivers only (ignored for patients). */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JoinRequestBody {

    @Schema(description = "Caregivers only: join on the patient's behalf (default false). Ignored for patients.")
    private Boolean actingForPatient;
}
