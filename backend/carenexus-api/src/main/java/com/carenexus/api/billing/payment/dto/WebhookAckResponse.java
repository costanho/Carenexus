package com.carenexus.api.billing.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Answer to every correctly signed, parseable gateway callback, whatever its outcome. */
@Schema(description = "Webhook acknowledgement")
public record WebhookAckResponse(
        @Schema(description = "Always true", example = "true")
        boolean received) {

    public static final WebhookAckResponse RECEIVED = new WebhookAckResponse(true);
}
