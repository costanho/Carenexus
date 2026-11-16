package com.carenexus.api.caregiveraccess.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** One consent-ledger row: a lifecycle step of a caregiver grant about the caller. */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "One step in the patient's consent ledger")
public class ConsentRecordResponse {

    @Schema(description = "Ledger row id", example = "203")
    private Integer consentId;

    @Schema(description = "GRANTED, PERMISSIONS_CHANGED, COMMUNICATION_APPROVED, EXTENDED, NARROWED, REVOKED or EXPIRED")
    private String eventKind;

    @Schema(description = "The grant the step belongs to (revoked grants included)")
    private Integer accessId;

    @Schema(description = "The caller")
    private String patientSystemId;

    @Schema(description = "The caregiver of the grant")
    private String caregiverSystemId;

    @Schema(description = "\"First Last\", resolved now; null when the account no longer exists", nullable = true)
    private String caregiverName;

    @Schema(description = "Who performed the step (patient, caregiver or administrator); null = the system",
            nullable = true)
    private String grantedBySystemId;

    @Schema(description = "The grant right after the step")
    private ConsentScopeResponse scope;

    @Schema(description = "When the step was recorded (clinic local date-time)")
    private LocalDateTime recordedAt;
}
