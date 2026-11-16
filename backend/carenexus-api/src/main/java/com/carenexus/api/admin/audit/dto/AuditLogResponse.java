package com.carenexus.api.admin.audit.dto;

import com.carenexus.api.common.model.AuditLog;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * One audit row as returned to administrators. Same field names as the serialized {@link AuditLog} entity of the
 * baseline, plus {@code actorName}. Timestamps are clinic wall-clock times without an offset.
 */
@Schema(description = "Audit log entry")
public record AuditLogResponse(
        Long auditId,
        @Schema(description = "The actor; null when nobody was signed in (for example a failed login)", nullable = true)
        String systemId,
        @Schema(description = "First and last name of the actor today; null when the actor is unknown", nullable = true)
        String actorName,
        @Schema(description = "Actor's role at the time", nullable = true)
        String actorRole,
        @Schema(description = "Upper snake case, for example READ, USER_DEACTIVATE, DOCTOR_VERIFY (open list)")
        String action,
        @Schema(description = "Upper snake case, for example APPOINTMENT, ACCOUNT, AUDIT_LOG (open list)", nullable = true)
        String resourceType,
        @Schema(nullable = true)
        Integer resourceId,
        @Schema(description = "Patient whose data was touched", nullable = true)
        String patientSystemId,
        @Schema(description = "Account an account or security event is about", nullable = true)
        String targetSystemId,
        @Schema(nullable = true)
        String httpMethod,
        @Schema(description = "Request path without the query string", nullable = true)
        String requestPath,
        @Schema(allowableValues = {"SUCCESS", "FAILURE"})
        String status,
        @Schema(nullable = true)
        String ipAddress,
        @Schema(nullable = true)
        String userAgent,
        @Schema(description = "Reason of a failure or denial", nullable = true)
        String errorMessage,
        LocalDateTime timestamp) {

    public static AuditLogResponse of(AuditLog row, String actorName) {
        return new AuditLogResponse(row.getAuditId(), row.getSystemId(), actorName, row.getActorRole(), row.getAction(),
                row.getResourceType(), row.getResourceId(), row.getPatientSystemId(), row.getTargetSystemId(),
                row.getHttpMethod(), row.getRequestPath(), row.getStatus(), row.getIpAddress(), row.getUserAgent(),
                row.getErrorMessage(), row.getTimestamp());
    }
}
