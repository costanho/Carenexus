package com.carenexus.api.admin.user.dto.response;

import com.carenexus.api.admin.clinician.dto.response.AdminDoctorResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * Result of an account action: every field of {@link AdminUserDetailResponse} (the account after the action) plus
 * {@code message}, one sentence safe to show as a toast.
 */
@Schema(description = "The account after the action, plus a message describing what happened")
public record AdminUserActionResponse(
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
        @Schema(nullable = true)
        LocalDateTime emailVerifiedAt,
        @Schema(nullable = true)
        LocalDateTime passwordChangedAt,
        boolean hasPassword,
        @Schema(nullable = true)
        String closureReason,
        @Schema(nullable = true)
        String deactivationReason,
        long activeSessions,
        long futureActiveAppointments,
        @Schema(nullable = true)
        AdminDoctorResponse doctor,
        @Schema(description = "What happened, safe to show as a toast")
        String message) {

    public static AdminUserActionResponse of(AdminUserDetailResponse d, String message) {
        return new AdminUserActionResponse(d.userId(), d.systemId(), d.firstName(), d.lastName(), d.email(), d.phone(),
                d.role(), d.isActive(), d.createdAt(), d.updatedAt(), d.emailVerified(), d.mustChangePassword(),
                d.closedAt(), d.anonymised(), d.verificationStatus(), d.emailVerifiedAt(), d.passwordChangedAt(),
                d.hasPassword(), d.closureReason(), d.deactivationReason(), d.activeSessions(),
                d.futureActiveAppointments(), d.doctor(), message);
    }
}
