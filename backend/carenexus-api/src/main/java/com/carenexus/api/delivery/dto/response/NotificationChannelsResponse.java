package com.carenexus.api.delivery.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** The external delivery channels of this installation. The in-app channel is always on and not listed. */
@Schema(description = "External delivery channels of this installation (the in-app channel is always on)")
public record NotificationChannelsResponse(
        @Schema(description = "Email channel") ChannelStatus email,
        @Schema(description = "SMS channel") ChannelStatus sms,
        @Schema(description = "Push channel") ChannelStatus push) {
}
