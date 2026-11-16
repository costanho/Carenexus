package com.carenexus.api.caregiveraccess.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * One thing a caregiver viewed or did with the caller's data (a successful audit_log row). Deliberately minimal: no
 * IP address, path, user agent or resource id.
 */
@Schema(description = "One action of a caregiver on the caller's data")
public record CaregiverActivityEntryResponse(
        @Schema(description = "Audit row id; use it as the list key only", example = "9812")
        Long auditId,
        @Schema(description = "READ, CREATE, UPDATE, DELETE or an account-event action in upper snake case", example = "READ")
        String action,
        @Schema(description = "Audit resource type, for example LAB_RESULT; may be null", nullable = true, example = "LAB_RESULT")
        String resourceType,
        @Schema(description = "When it happened (clinic local date-time)")
        LocalDateTime timestamp) {
}
