package com.carenexus.api.core.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * One page of the calling doctor's patients: the standard pagination envelope fields plus {@code summary}.
 */
@Schema(description = "One page of the doctor's patients plus counts per health status")
public record DoctorPatientPageResponse(
        @Schema(description = "Rows of this page (recentAppointments and the healthStatus* detail fields are null)")
        List<DoctorPatientResponse> content,
        @Schema(description = "Zero-based page index", example = "0") int page,
        @Schema(description = "Page size after clamping (1..100)", example = "20") int size,
        @Schema(description = "Rows matching the filters", example = "57") long totalElements,
        @Schema(description = "Total number of pages", example = "3") int totalPages,
        @Schema(description = "Counts over the doctor's whole patient set") DoctorPatientSummaryResponse summary) {
}
