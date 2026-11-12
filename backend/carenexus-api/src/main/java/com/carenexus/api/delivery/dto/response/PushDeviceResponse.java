package com.carenexus.api.delivery.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** A registered push device of the caller. The token is not echoed. */
@Schema(description = "A registered push device of the caller")
public record PushDeviceResponse(
        @Schema(description = "Row id of the device", example = "12")
        Integer deviceId,

        @Schema(description = "Device platform", allowableValues = {"IOS", "ANDROID", "WEB"}, example = "ANDROID")
        String platform,

        @Schema(description = "When the token was last registered or refreshed (ISO-8601 instant, UTC)",
                example = "2026-11-10T09:00:00Z")
        Instant lastSeenAt) {
}
