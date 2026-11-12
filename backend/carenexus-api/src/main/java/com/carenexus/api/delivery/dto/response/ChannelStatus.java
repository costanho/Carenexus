package com.carenexus.api.delivery.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** State of one external delivery channel on this installation. */
@Schema(description = "State of one external delivery channel on this installation")
public record ChannelStatus(
        @Schema(description = "True when the provider is not 'disabled' and is configured. False means messages on "
                + "this channel are never sent", example = "true")
        boolean available,

        @Schema(description = "Lower-case provider id: 'local' (messages are recorded on this machine, nothing is "
                + "sent), 'disabled', or the id of a real provider", example = "local")
        String provider) {
}
