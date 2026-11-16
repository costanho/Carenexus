package com.carenexus.api.ai.alert.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Badge count of the alert inbox (api/be-ai-core.md 4.2).
 *
 * @param unacknowledgedCount number of unacknowledged alerts visible to the caller
 * @param disclaimer          the fixed automated-screening notice
 */
@Schema(description = "Number of unacknowledged alerts the caller can see")
public record UnacknowledgedAlertCountResponse(
        @Schema(description = "Unacknowledged alerts visible to the caller", example = "3")
        long unacknowledgedCount,
        @Schema(description = "Automated-screening notice", example = AiAlertResponse.DISCLAIMER)
        String disclaimer) {

    public static UnacknowledgedAlertCountResponse of(long count) {
        return new UnacknowledgedAlertCountResponse(count, AiAlertResponse.DISCLAIMER);
    }
}
