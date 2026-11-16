package com.carenexus.api.account.datarights.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * An account request as an administrator sees it: the fields of {@link AccountRequestResponse} plus the account it
 * belongs to (current values of the users row).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "An account request with the account it belongs to")
public record AdminAccountRequestResponse(
        @Schema(description = "Row id") Integer requestId,
        @Schema(description = "EXPORT, CLOSURE or DELETION") String type,
        @Schema(description = "REQUESTED, PROCESSING, READY, COMPLETED, REJECTED or CANCELLED") String status,
        @Schema(description = "The user's own reason", nullable = true) String reason,
        @Schema(description = "The administrator's reason for a rejection", nullable = true) String decisionReason,
        @Schema(description = "When the request was made (UTC instant)") Instant requestedAt,
        @Schema(description = "When it reached its current state (UTC instant)", nullable = true) Instant processedAt,
        @Schema(description = "EXPORT only: end of the download window", nullable = true) Instant expiresAt,
        @Schema(description = "True only for a READY export still in its window (the owner alone can download it)")
        boolean downloadable,
        @Schema(description = "EXPORT only: the download file name", nullable = true) String fileName,
        @Schema(description = "System id of the account, for example PAT-00012") String systemId,
        @Schema(description = "Current first name (Deleted after anonymisation)") String firstName,
        @Schema(description = "Current last name (User after anonymisation)") String lastName,
        @Schema(description = "Current login email; null after anonymisation and for managed dependents",
                nullable = true) String email,
        @Schema(description = "PATIENT, DOCTOR, CAREGIVER or ADMIN") String role,
        @JsonProperty("isActive")
        @Schema(description = "Current users.is_active; false once closed, deactivated or anonymised") boolean isActive,
        @Schema(description = "Who moved the row to its current terminal state (administrator for approve and "
                + "reject, the user for closure and cancel); null for exports and while REQUESTED", nullable = true)
        String processedBySystemId) {
}
