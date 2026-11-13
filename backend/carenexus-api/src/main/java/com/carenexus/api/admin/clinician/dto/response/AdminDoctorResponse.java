package com.carenexus.api.admin.clinician.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * A clinician as seen by an administrator: the verification queue row and the doctor block of the user detail.
 * Timestamps are clinic wall-clock times without an offset.
 */
@Schema(description = "Clinician record as seen by an administrator")
public record AdminDoctorResponse(
        @Schema(description = "The doctor's system id", example = "DOC-00007")
        String doctorSystemId,
        Integer userId,
        String firstName,
        String lastName,
        @Schema(description = "Login email; null for an anonymised account", nullable = true)
        String email,
        @Schema(nullable = true)
        String phone,
        @Schema(description = "The doctor confirmed the email address")
        boolean emailVerified,
        @Schema(nullable = true)
        String specialization,
        @Schema(description = "Null = no licence number on file (approval is refused until one is entered)", nullable = true)
        String licenseNo,
        @Schema(nullable = true)
        String licensingBody,
        @Schema(description = "ISO 3166-1 alpha-2, upper-case", example = "ZW", nullable = true)
        String licenseCountry,
        @Schema(allowableValues = {"PENDING", "APPROVED", "REJECTED", "SUSPENDED"})
        String verificationStatus,
        @Schema(description = "Answer of the licence registry seam; information only, never gates approval",
                allowableValues = {"VERIFIED", "NOT_FOUND", "UNVERIFIED_MANUAL_REVIEW"})
        String registryStatus,
        @Schema(description = "Reason of the last rejection (REJECTED) or suspension (SUSPENDED)", nullable = true)
        String rejectionReason,
        @Schema(description = "Administrator who made the last verification decision", nullable = true)
        String verifiedBySystemId,
        @Schema(description = "When that decision was made (clinic wall-clock)", nullable = true)
        LocalDateTime verifiedAt,
        @Schema(description = "doctors.is_active; true only for APPROVED")
        Boolean isActive,
        @Schema(description = "When the clinician registered (clinic wall-clock)")
        LocalDateTime doctorCreatedAt) {
}
