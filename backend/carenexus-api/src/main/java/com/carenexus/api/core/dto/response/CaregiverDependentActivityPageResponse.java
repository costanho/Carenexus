package com.carenexus.api.core.dto.response;

import com.carenexus.api.common.dto.PageResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.LocalDate;

/**
 * Paged form of GET /api/caregiver/dependents/activity (any of from, to, page, size given): appointments and
 * consultations of the caller's dependents in the window, each its own page envelope sharing one page and size.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Appointments and consultations of the caregiver's dependents in a date window, paged")
public class CaregiverDependentActivityPageResponse {

    @JsonProperty("from")
    @Schema(description = "Window start that was applied (clinic date, inclusive)", example = "2026-08-12")
    private LocalDate from;

    @JsonProperty("to")
    @Schema(description = "Window end that was applied (clinic date, inclusive); null = open", nullable = true)
    private LocalDate to;

    @JsonProperty("appointments")
    @Schema(description = "Appointments filtered on scheduledAt, newest first")
    private PageResponse<AppointmentResponse> appointments;

    @JsonProperty("consultations")
    @Schema(description = "Finished consultations filtered on createdAt, newest first")
    private PageResponse<ConsultationResponse> consultations;
}
