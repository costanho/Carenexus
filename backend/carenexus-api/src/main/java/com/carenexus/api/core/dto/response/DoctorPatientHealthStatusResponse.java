package com.carenexus.api.core.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * State of a patient's health status after PATCH /api/doctors/patients/{patientSystemId}/health-status.
 */
@Schema(description = "Health status of a patient after the change")
public record DoctorPatientHealthStatusResponse(
        @Schema(example = "PAT-00001") String patientSystemId,
        @Schema(description = "Current status: STABLE, MONITOR or CRITICAL", example = "CRITICAL") String healthStatus,
        @Schema(description = "Status before this call (equal to healthStatus when changed is false)", example = "MONITOR")
        String previousHealthStatus,
        @Schema(description = "The status itself changed in this call") boolean changed,
        @Schema(description = "Stored note (at most 500 characters)", nullable = true) String healthStatusNote,
        @Schema(description = "When the status or note was last set (server local date-time)", nullable = true)
        LocalDateTime healthStatusUpdatedAt,
        @Schema(description = "Doctor or administrator who last set it", nullable = true) String healthStatusUpdatedBySystemId,
        @Schema(description = "\"Dr. First Last\" for a doctor, \"First Last\" for an administrator", nullable = true)
        String healthStatusUpdatedByName) {
}
