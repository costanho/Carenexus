package com.carenexus.api.reminders.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Body of {@code POST /api/reminders}: a manual reminder for a patient, who is also the only recipient. Strings are
 * trimmed while the body is read. {@code type} and {@code frequency} values and the date rules are checked by the
 * service after the caller's authorization; {@code isActive}, {@code sourceType}, {@code sourceId} and
 * {@code recipientSystemId} are not accepted (ignored when sent).
 */
@Schema(description = "A new manual reminder for a patient")
public record CreateReminderRequest(

        @Schema(description = "The patient the reminder is for and who receives it", example = "PAT-00001",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "is required")
        @Pattern(regexp = ReminderInput.OPTIONAL_SYSTEM_ID, message = "must be a valid system id")
        String patientSystemId,

        @Schema(description = "MEDICATION, APPOINTMENT, LAB_FOLLOWUP, CARE_PLAN, CHECK_IN or VITAL_SIGN "
                + "(case-insensitive)", example = "VITAL_SIGN", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "is required")
        String type,

        @Schema(description = "Notification title, delivered verbatim", example = "Morning blood pressure",
                maxLength = 80, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "is required")
        @Size(max = 80, message = "must be at most 80 characters")
        String title,

        @Schema(description = "Notification text, delivered verbatim (the due time is appended)",
                example = "Measure your blood pressure before breakfast and write it down.", maxLength = 400,
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "is required")
        @Size(max = 400, message = "must be at most 400 characters")
        String message,

        @Schema(description = "Time of day, HH:mm, 24-hour clinic time", example = "07:30",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "is required")
        @Pattern(regexp = ReminderInput.OPTIONAL_TIME, message = "must be a time in HH:mm format")
        String reminderTime,

        @Schema(description = "ONCE, DAILY, WEEKLY or MONTHLY (case-insensitive)", example = "DAILY",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "is required")
        String frequency,

        @Schema(description = "First day the reminder can fire (yyyy-MM-dd, clinic calendar)", example = "2026-11-11",
                type = "string", format = "date", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "is required")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate startDate,

        @Schema(description = "Last day it can fire, inclusive (yyyy-MM-dd); not before startDate; ignored for ONCE",
                example = "2026-12-31", type = "string", format = "date", nullable = true)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate endDate
) {

    public CreateReminderRequest {
        patientSystemId = ReminderInput.trim(patientSystemId);
        type = ReminderInput.trim(type);
        title = ReminderInput.trim(title);
        message = ReminderInput.trim(message);
        reminderTime = ReminderInput.trim(reminderTime);
        frequency = ReminderInput.trim(frequency);
    }
}
