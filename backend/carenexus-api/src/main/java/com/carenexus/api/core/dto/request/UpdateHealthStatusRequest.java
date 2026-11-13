package com.carenexus.api.core.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Locale;

/**
 * Body of PATCH /api/doctors/patients/{patientSystemId}/health-status. Values are trimmed when the body is read;
 * the status is compared case-insensitively and stored upper-case. A blank note counts as no note.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "New health status of a patient, with an optional note for the care team")
public class UpdateHealthStatusRequest {

    @JsonProperty("healthStatus")
    @NotBlank(message = "Health status is required")
    @Pattern(regexp = "^(STABLE|MONITOR|CRITICAL)$", flags = Pattern.Flag.CASE_INSENSITIVE,
            message = "Health status must be STABLE, MONITOR or CRITICAL")
    @Schema(description = "STABLE, MONITOR or CRITICAL (case-insensitive)", example = "CRITICAL",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String healthStatus;

    @JsonProperty("note")
    @Size(max = 500, message = "Note must be at most 500 characters")
    @Schema(description = "Free text for the care team, at most 500 characters; never copied into a notification",
            example = "Readmitted overnight, daily review")
    private String note;

    public void setHealthStatus(String healthStatus) {
        this.healthStatus = healthStatus == null ? null : healthStatus.trim();
    }

    public void setNote(String note) {
        if (note == null) {
            this.note = null;
            return;
        }
        String trimmed = note.trim();
        this.note = trimmed.isEmpty() ? null : trimmed;
    }

    /** The status upper-cased, or null when absent. */
    public String normalizedHealthStatus() {
        return healthStatus == null ? null : healthStatus.trim().toUpperCase(Locale.ROOT);
    }
}
