package com.carenexus.api.reminders.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A reminder addressed to the patient it is about, as every reminder endpoint returns it. Times are clinic
 * wall-clock; names are not included.
 */
@Schema(description = "A reminder addressed to a patient")
public record ReminderResponse(

        @Schema(description = "Row id", example = "12")
        Integer reminderId,

        @Schema(description = "The patient the reminder is for and who receives it", example = "PAT-00001")
        String patientSystemId,

        @Schema(description = "MEDICATION, APPOINTMENT, LAB_FOLLOWUP, CARE_PLAN, CHECK_IN or VITAL_SIGN",
                example = "VITAL_SIGN")
        String type,

        @Schema(description = "Notification title (1 to 80 characters)", example = "Morning blood pressure")
        String title,

        @Schema(description = "Notification text (1 to 400 characters)")
        String message,

        @Schema(description = "Time of day, HH:mm, 24-hour clinic time", example = "07:30")
        String reminderTime,

        @Schema(description = "ONCE, DAILY, WEEKLY or MONTHLY", example = "DAILY")
        String frequency,

        @Schema(description = "First day it can fire (yyyy-MM-dd, clinic calendar)", example = "2026-11-11",
                type = "string", format = "date")
        LocalDate startDate,

        @Schema(description = "Last day it can fire, inclusive; null = no end; always null for ONCE", type = "string",
                format = "date", nullable = true)
        LocalDate endDate,

        @Schema(description = "Stored flag; false = paused or ended")
        @JsonProperty("isActive")
        Boolean isActive,

        @Schema(description = "Derived at read time: ACTIVE, PAUSED (a later occurrence exists) or ENDED",
                example = "ACTIVE")
        String status,

        @Schema(description = "Next time it fires, clinic wall-clock; non-null exactly when isActive",
                example = "2026-11-11T07:30:00", type = "string", format = "date-time", nullable = true)
        LocalDateTime nextDueAt,

        @Schema(description = "When the dispatcher last processed it (even when the notification was suppressed by a "
                + "preference); null = never", type = "string", format = "date-time", nullable = true)
        LocalDateTime lastSentAt,

        @Schema(description = "MANUAL, CONSULTATION or CARE_PLAN", example = "MANUAL")
        String sourceType,

        @Schema(description = "Consultation id (CONSULTATION) or care plan id (CARE_PLAN); null for MANUAL",
                nullable = true)
        Integer sourceId,

        @Schema(description = "Display text for the source: Manual, Follow-up or Care plan", example = "Manual")
        String sourceLabel,

        @Schema(description = "Who created a manual reminder; null for automatic reminders", example = "DOC-00002",
                nullable = true)
        String createdBySystemId,

        @Schema(description = "True exactly when the caller may delete it right now")
        Boolean canDelete,

        @Schema(description = "Clinic wall-clock creation time", example = "2026-11-10T16:42:10", type = "string",
                format = "date-time")
        LocalDateTime createdAt
) {
}
