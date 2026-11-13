package com.carenexus.api.admin.user.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * An account as seen by an administrator (list row). Never carries the password hash. Timestamps are clinic
 * wall-clock times without an offset.
 */
@Schema(description = "Account as seen by an administrator")
public record AdminUserResponse(
        Integer userId,
        @Schema(example = "DOC-00007")
        String systemId,
        String firstName,
        String lastName,
        @Schema(description = "Login email; null for a managed dependent without sign-in and for an anonymised account",
                nullable = true)
        String email,
        @Schema(nullable = true)
        String phone,
        @Schema(allowableValues = {"PATIENT", "DOCTOR", "CAREGIVER", "ADMIN"})
        String role,
        @Schema(description = "False = cannot sign in, every request is refused with 401")
        Boolean isActive,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        @Schema(description = "The email address was confirmed")
        boolean emailVerified,
        @Schema(description = "A temporary password is in force")
        boolean mustChangePassword,
        @Schema(description = "Set when the account was closed by its owner or anonymised", nullable = true)
        LocalDateTime closedAt,
        @Schema(description = "Personal data was removed after an approved deletion request; can never be reactivated")
        boolean anonymised,
        @Schema(description = "Clinician verification status for role DOCTOR; null for other roles",
                allowableValues = {"PENDING", "APPROVED", "REJECTED", "SUSPENDED"}, nullable = true)
        String verificationStatus) {
}
