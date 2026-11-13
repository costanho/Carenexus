package com.carenexus.api.facility.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * One facility, as every facility endpoint returns it (signed-in users and administrators see the same shape).
 * Every field is always present; nullable ones are sent as JSON null. Also stored and read back as JSON for
 * idempotent replays of {@code POST /api/admin/facilities}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "FacilityResponse", description = "A clinic location")
public record FacilityResponse(
        @Schema(description = "Row id; the same value as facilityId in appointments and care-team entries",
                example = "4")
        Integer facilityId,

        @Schema(description = "Display name", example = "Avondale Family Clinic")
        String name,

        @Schema(description = "Free-text kind of facility", example = "Clinic", nullable = true)
        String type,

        @Schema(description = "Street or postal address on one line", example = "12 King George Road, Avondale, Harare",
                nullable = true)
        String address,

        @Schema(description = "Contact phone. Rows from the development seed may not match the phone pattern",
                example = "+263 24 270 0000", nullable = true)
        String phone,

        @Schema(description = "Contact email", example = "frontdesk@avondaleclinic.example", nullable = true)
        String email,

        @Schema(description = "True when the facility is offered for new bookings and listed by GET /api/facilities",
                example = "true")
        @JsonProperty("isActive")
        Boolean isActive,

        @Schema(description = "When the facility was created (clinic wall-clock, no offset)",
                example = "2026-11-10T14:05:09.482913")
        LocalDateTime createdAt,

        @Schema(description = "When the facility was last changed (clinic wall-clock, no offset); equals createdAt "
                + "until the first change", example = "2026-11-10T14:05:09.482913")
        LocalDateTime updatedAt
) {
}
