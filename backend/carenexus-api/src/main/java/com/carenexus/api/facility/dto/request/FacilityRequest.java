package com.carenexus.api.facility.dto.request;

import com.carenexus.api.common.dto.ValidationPatterns;
import com.carenexus.api.facility.model.Facility;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/admin/facilities} and {@code PUT /api/admin/facilities/{id}}.
 *
 * The compact constructor (which Jackson calls) normalises before Bean Validation runs: every string is trimmed,
 * and an optional string that is empty or only whitespace becomes {@code null}. A blank name stays blank so it
 * fails {@code @NotBlank}. Case is never changed. Unknown fields (facilityId, isActive, createdAt, updatedAt) are
 * ignored: the active flag changes only through the activate / deactivate endpoints.
 *
 * PUT is a full replacement, so an optional field that is missing, null or blank clears the stored value.
 */
@Schema(name = "FacilityRequest", description = "Details of a facility; PUT replaces all five fields")
public record FacilityRequest(
        @Schema(description = "Display name; trimmed, not unique", example = "Avondale Family Clinic",
                requiredMode = Schema.RequiredMode.REQUIRED, maxLength = Facility.NAME_MAX)
        @NotBlank(message = "must not be blank")
        @Size(max = Facility.NAME_MAX, message = "must be at most 200 characters")
        String name,

        @Schema(description = "Free-text kind of facility, for example Hospital, Clinic, Diagnostic Centre",
                example = "Clinic", nullable = true, maxLength = Facility.TYPE_MAX)
        @Size(max = Facility.TYPE_MAX, message = "must be at most 80 characters")
        String type,

        @Schema(description = "Street or postal address on one line", example = "12 King George Road, Avondale, Harare",
                nullable = true, maxLength = Facility.ADDRESS_MAX)
        @Size(max = Facility.ADDRESS_MAX, message = "must be at most 300 characters")
        String address,

        @Schema(description = "Contact phone: optional leading +, 7 to 15 digits; spaces, dots, dashes and brackets "
                + "allowed between them", example = "+263 24 270 0000", nullable = true, maxLength = Facility.PHONE_MAX)
        @Size(max = Facility.PHONE_MAX, message = "must be at most 20 characters")
        @Pattern(regexp = ValidationPatterns.PHONE, message = "must be a valid phone number")
        String phone,

        @Schema(description = "Contact email, case kept as typed", example = "frontdesk@avondaleclinic.example",
                nullable = true, maxLength = Facility.EMAIL_MAX)
        @Size(max = Facility.EMAIL_MAX, message = "must be at most 150 characters")
        @Email(regexp = FacilityRequest.EMAIL_SHAPE, message = "must be a valid email address")
        String email
) {

    /** Something, an @, something, a dot, something; no whitespace. Checked in addition to {@code @Email}. */
    public static final String EMAIL_SHAPE = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$";

    public FacilityRequest {
        name = name == null ? null : name.trim();
        type = optional(type);
        address = optional(address);
        phone = optional(phone);
        email = optional(email);
    }

    private static String optional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
