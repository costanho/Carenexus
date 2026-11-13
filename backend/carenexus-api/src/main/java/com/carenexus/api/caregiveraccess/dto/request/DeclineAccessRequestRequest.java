package com.carenexus.api.caregiveraccess.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.*;

/**
 * POST /api/patients/me/caregiver-access/requests/{requestId}/decline. The body is optional; no body or {@code {}}
 * declines without a reason. The reason is trimmed (blank = none) and shown to the caregiver.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "The patient's optional reason for declining an access request")
public class DeclineAccessRequestRequest {

    @Size(max = 500, message = "Reason must be at most 500 characters")
    @Schema(description = "Shown to the caregiver as declineReason", maxLength = 500)
    private String reason;

    public void setReason(String reason) {
        this.reason = RequestTexts.clean(reason);
    }
}
