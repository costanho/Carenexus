package com.carenexus.api.careplans.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A care plan with all its goals and interventions. Every care-plan endpoint returns this shape. Names are not
 * included; clients resolve the system ids.
 */
@Schema(description = "A care plan with its goals and interventions")
public record CarePlanResponse(

        @Schema(description = "Plan id", example = "17")
        Integer planId,

        @Schema(description = "The patient", example = "PAT-00001")
        String patientSystemId,

        @Schema(description = "The authoring doctor, the only doctor who may change the plan", example = "DOC-00002")
        String doctorSystemId,

        @Schema(description = "Consultation the plan was written from, or null", example = "42", nullable = true)
        Integer consultationId,

        @Schema(description = "Title", example = "Blood pressure management")
        String title,

        @Schema(description = "Description or null", nullable = true)
        String description,

        @Schema(description = "ACTIVE, ON_HOLD, COMPLETED or DISCONTINUED; the last two are terminal (read-only)",
                example = "ACTIVE")
        String status,

        @Schema(description = "First day of the plan (yyyy-MM-dd)", example = "2026-11-10", type = "string",
                format = "date")
        LocalDate startDate,

        @Schema(description = "Planned end (yyyy-MM-dd) or null", example = "2027-05-10", type = "string",
                format = "date", nullable = true)
        LocalDate targetDate,

        @Schema(description = "NEXT review date (yyyy-MM-dd); null = no review scheduled. Kept as stored after the "
                + "plan becomes terminal.", example = "2027-02-10", type = "string", format = "date", nullable = true)
        LocalDate reviewDate,

        @Schema(description = "Clinic wall-clock time of the last review; null = never reviewed", type = "string",
                format = "date-time", nullable = true)
        LocalDateTime lastReviewedAt,

        @Schema(description = "Note of the last review, or null", nullable = true)
        String lastReviewNote,

        @Schema(description = "Goals ordered by position")
        List<CarePlanGoalResponse> goals,

        @Schema(description = "Interventions ordered by position")
        List<CarePlanInterventionResponse> interventions,

        @Schema(description = "Clinic wall-clock creation time", example = "2026-11-10T09:15:27.105331",
                type = "string", format = "date-time")
        LocalDateTime createdAt,

        @Schema(description = "Clinic wall-clock time of the last change of the plan itself (edit, status, review); "
                + "goal progress does not change it", example = "2026-11-10T09:15:27.105331", type = "string",
                format = "date-time")
        LocalDateTime updatedAt
) {
}
