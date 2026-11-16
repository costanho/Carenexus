package com.carenexus.api.core.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Counts of a doctor's consultations per status (same JSON keys as the earlier untyped map).
 */
@Schema(description = "Counts of the consultations a doctor authored, per status")
public record ConsultationStatsResponse(
        @Schema(description = "All consultations authored by the doctor", example = "14")
        long totalConsultations,
        @Schema(example = "1") long draftCount,
        @Schema(example = "1") long inProgressCount,
        @Schema(example = "10") long completedCount,
        @Schema(example = "2") long cancelledCount,
        @Schema(description = "completed * 100 / (completed + cancelled), not rounded; 0.0 when nothing is completed",
                example = "83.33333333333333")
        double completionRate) {

    public static ConsultationStatsResponse of(long draft, long inProgress, long completed, long cancelled) {
        double rate = completed == 0 ? 0.0 : (completed * 100.0) / (completed + cancelled);
        return new ConsultationStatsResponse(draft + inProgress + completed + cancelled, draft, inProgress, completed,
                cancelled, rate);
    }
}
