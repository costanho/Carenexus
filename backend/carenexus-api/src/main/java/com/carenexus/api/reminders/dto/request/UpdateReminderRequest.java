package com.carenexus.api.reminders.dto.request;

import com.carenexus.api.common.dto.ValidationPatterns;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Body of {@code PATCH /api/reminders/{id}}: a partial update. Every field is optional (absent or null = unchanged),
 * at least one must be given. Pause is {@code {"isActive": false}}, resume {@code {"isActive": true}}. Automatic
 * reminders accept only {@code isActive}. Strings are trimmed while the body is read.
 */
@Schema(description = "Changes to a reminder; absent or null fields stay unchanged")
public record UpdateReminderRequest(

        @Schema(description = "MEDICATION, APPOINTMENT, LAB_FOLLOWUP, CARE_PLAN, CHECK_IN or VITAL_SIGN (manual only)",
                example = "MEDICATION", nullable = true)
        String type,

        @Schema(description = "New title (manual only)", maxLength = 80, nullable = true)
        @Pattern(regexp = ReminderInput.NOT_BLANK, message = "must not be blank")
        @Size(max = 80, message = "must be at most 80 characters")
        String title,

        @Schema(description = "New text (manual only)", maxLength = 400, nullable = true)
        @Pattern(regexp = ReminderInput.NOT_BLANK, message = "must not be blank")
        @Size(max = 400, message = "must be at most 400 characters")
        String message,

        @Schema(description = "New time of day, HH:mm clinic time (manual only)", example = "07:00", nullable = true)
        @Pattern(regexp = ValidationPatterns.TIME_HH_MM, message = "must be a time in HH:mm format")
        String reminderTime,

        @Schema(description = "ONCE, DAILY, WEEKLY or MONTHLY (manual only); ONCE clears the end date",
                example = "DAILY", nullable = true)
        String frequency,

        @Schema(description = "New first day (yyyy-MM-dd; manual only)", type = "string", format = "date",
                nullable = true)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate startDate,

        @Schema(description = "New last day, inclusive (yyyy-MM-dd; manual only); not before the resulting startDate",
                type = "string", format = "date", nullable = true)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate endDate,

        @Schema(description = "true removes the end date (manual only); cannot be combined with endDate",
                nullable = true)
        Boolean clearEndDate,

        @Schema(description = "false pauses, true resumes; the current value is a no-op", nullable = true)
        @JsonProperty("isActive")
        Boolean isActive
) {

    public UpdateReminderRequest {
        type = ReminderInput.trim(type);
        title = ReminderInput.trim(title);
        message = ReminderInput.trim(message);
        reminderTime = ReminderInput.trim(reminderTime);
        frequency = ReminderInput.trim(frequency);
    }

    /** True when no field was given (all absent or null). */
    @JsonIgnore
    public boolean isEmpty() {
        return type == null && title == null && message == null && reminderTime == null && frequency == null
                && startDate == null && endDate == null && clearEndDate == null && isActive == null;
    }

    /** True when a field other than {@code isActive} was given (refused for automatic reminders). */
    public boolean touchesContent() {
        return type != null || title != null || message != null || reminderTime != null || frequency != null
                || startDate != null || endDate != null || clearEndDate != null;
    }
}
