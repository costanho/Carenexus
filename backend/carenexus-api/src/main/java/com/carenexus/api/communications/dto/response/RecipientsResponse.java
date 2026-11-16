package com.carenexus.api.communications.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.util.List;

/** GET /api/communications/recipients. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecipientsResponse {

    private String patientSystemId;

    private String patientName;

    @Schema(description = "The caller excluded; the patient first, then related doctors, then caregivers with an "
            + "approved messaging grant. Hard cap: the first 200 entries in that order. Administrators are never listed.")
    private List<RecipientResponse> recipients;
}
