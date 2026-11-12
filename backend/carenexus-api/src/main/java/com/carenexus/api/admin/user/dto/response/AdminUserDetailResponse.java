package com.carenexus.api.admin.user.dto.response;

import com.carenexus.api.admin.clinician.dto.response.AdminDoctorResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * One account with its status details: every field of {@link AdminUserResponse} plus the fields below.
 */
@Schema(description = "Account with its status details (every field of AdminUserResponse plus the status details)")
public record AdminUserDetailResponse(
        Integer userId,
        String systemId,
        String firstName,
        String lastName,
        @Schema(nullable = true)
        String email,
        @Schema(nullable = true)
        String phone,
        @Schema(allowableValues = {"PATIENT", "DOCTOR", "CAREGIVER", "ADMIN"})
        String role,
        Boolean isActive,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        boolean emailVerified,
        boolean mustChangePassword,
        @Schema(nullable = true)
        LocalDateTime closedAt,
        boolean anonymised,
        @Schema(allowableValues = {"PENDING", "APPROVED", "REJECTED", "SUSPENDED"}, nullable = true)
        String verificationStatus,
        @Schema(description = "When the email was confirmed", nullable = true)
        LocalDateTime emailVerifiedAt,
        @Schema(description = "Last password change or reset; null = never since registration", nullable = true)
        LocalDateTime passwordChangedAt,
        @Schema(description = "False for a managed dependent that cannot sign in yet")
        boolean hasPassword,
        @Schema(description = "Reason recorded with the closure", nullable = true)
        String closureReason,
        @Schema(description = "Reason given by the administrator at the last deactivation", nullable = true)
        String deactivationReason,
        @Schema(description = "Sessions that can still be refreshed")
        long activeSessions,
        @Schema(description = "SCHEDULED or RESCHEDULED appointments after clinic now (as doctor for DOCTOR, as patient "
                + "for PATIENT, 0 otherwise)")
        long futureActiveAppointments,
        @Schema(description = "Clinician record for role DOCTOR", nullable = true)
        AdminDoctorResponse doctor) {

    /** The list-row part of this account. */
    public AdminUserResponse summary() {
        return new AdminUserResponse(userId, systemId, firstName, lastName, email, phone, role, isActive, createdAt,
                updatedAt, emailVerified, mustChangePassword, closedAt, anonymised, verificationStatus);
    }
}
