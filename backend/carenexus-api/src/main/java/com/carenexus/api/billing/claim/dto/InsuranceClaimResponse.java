package com.carenexus.api.billing.claim.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * An insurance claim as every claim endpoint returns it. Money values have scale 2 and are in the invoice's
 * currency; date-times are clinic wall-clock ({@code carenexus.scheduling.time-zone}) without an offset. Every field
 * is always present; a nullable field without a value is {@code null}.
 */
@Schema(description = "Insurance claim")
public record InsuranceClaimResponse(
        @Schema(description = "Primary key", example = "17")
        Integer claimId,
        @Schema(description = "CLM-<year>-<6 or more digits>", example = "CLM-2026-000017")
        String claimNo,
        @Schema(description = "The invoice the claim is for", example = "42")
        Integer invoiceId,
        @Schema(description = "INV-<year>-<6 digits> of that invoice; null only if the invoice row is gone",
                nullable = true, example = "INV-2026-000042")
        String invoiceNo,
        @Schema(description = "The invoice's patient", example = "PAT-00001")
        String patientSystemId,
        @Schema(description = "\"First Last\" of the patient; null if the account is gone", nullable = true)
        String patientName,
        @Schema(description = "Insurer name", example = "Jubilee Health")
        String insuranceProvider,
        @Schema(description = "Policy number, in full", example = "JH-88213-K")
        String policyNumber,
        @Schema(description = "Member number", nullable = true, example = "0045712")
        String memberNumber,
        @Schema(description = "Amount claimed", example = "2320.00")
        BigDecimal claimAmount,
        @Schema(description = "Cumulative amount approved so far; null until a decision approves something",
                nullable = true, example = "1500.00")
        BigDecimal approvedAmount,
        @Schema(description = "The invoice's currency (ISO-4217)", example = "KES")
        String currency,
        @Schema(description = "What can still be settled on the invoice now: total - amountPaid while the invoice is "
                + "SENT, PARTIALLY_PAID or OVERDUE, 0.00 otherwise", example = "2320.00")
        BigDecimal invoiceOutstanding,
        @Schema(description = "Claim status", allowableValues = {"SUBMITTED", "PENDING", "APPROVED", "REJECTED",
                "PARTIAL", "APPEALED"})
        String status,
        @Schema(description = "Reason of a REJECTED decision, or the reviewer's note on a PARTIAL decision",
                nullable = true)
        String rejectionReason,
        @Schema(description = "Reason of the latest appeal", nullable = true)
        String appealReason,
        @Schema(description = "Ids of the attached documents in the order submitted; [] when none")
        List<Integer> documentIds,
        @Schema(description = "Attached documents that still exist, in the order of documentIds; [] when none")
        List<ClaimDocument> documents,
        @Schema(description = "The latest INSURANCE payment created by an approval of this claim", nullable = true)
        Integer paymentId,
        @Schema(description = "Reference returned by the claims provider at submission (MANUAL-<claimNo> for the "
                + "manual provider); informational", nullable = true, example = "MANUAL-CLM-2026-000017")
        String providerReference,
        @Schema(description = "Who submitted: the patient, a caregiver or an administrator", example = "PAT-00001")
        String submittedBySystemId,
        @Schema(description = "The administrator who made the latest adjudication step", nullable = true)
        String reviewedBySystemId,
        @Schema(description = "Clinic wall-clock time of the submission", example = "2026-11-10T09:20:11")
        LocalDateTime submittedAt,
        @Schema(description = "When the latest decision (APPROVED, PARTIAL, REJECTED) was made; null while open",
                nullable = true)
        LocalDateTime resolvedAt,
        @Schema(description = "Last change", nullable = true)
        LocalDateTime updatedAt) {
}
