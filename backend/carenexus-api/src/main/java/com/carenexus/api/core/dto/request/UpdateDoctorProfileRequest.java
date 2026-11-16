package com.carenexus.api.core.dto.request;

import com.carenexus.api.common.dto.ValidationPatterns;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

/**
 * Partial update of a doctor profile (PUT /api/doctors/profile and PUT /api/doctors/profile/{doctorSystemId}).
 * Every field is optional: an omitted or null field is left unchanged.
 *
 * String values are trimmed when the body is read, so the length limits apply to the trimmed text. A blank first
 * name, last name, phone, specialization, email or licence number counts as "not sent" (names, phone and
 * specialization cannot be removed); a blank bio clears the bio.
 *
 * The sign-in email is changed only through the email-change flow: any non-blank email is refused by the service.
 * The licence number may only be changed by an administrator, and activation is never changed here.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Doctor profile changes; send only the fields that change")
public class UpdateDoctorProfileRequest {

    @JsonProperty("firstName")
    @Size(max = 100, message = "First name must be at most 100 characters")
    @Schema(description = "At most 100 characters; blank leaves it unchanged")
    private String firstName;

    @JsonProperty("lastName")
    @Size(max = 100, message = "Last name must be at most 100 characters")
    @Schema(description = "At most 100 characters; blank leaves it unchanged")
    private String lastName;

    @JsonProperty("email")
    @Schema(description = "Not accepted: a non-blank value answers 400. Use the email-change flow", deprecated = true)
    private String email;

    @JsonProperty("phone")
    @Size(max = 20, message = "Phone number must be at most 20 characters")
    @Pattern(regexp = ValidationPatterns.PHONE, message = "Enter a valid phone number")
    @Schema(description = "Optional leading +, 7 to 15 digits; must not belong to another account", example = "+263 77 100 0222")
    private String phone;

    @JsonProperty("specialization")
    @Size(max = 150, message = "Specialization must be at most 150 characters")
    private String specialization;

    @JsonProperty("licenseNo")
    @Size(max = 50, message = "License number must be at most 50 characters")
    @Schema(description = "Administrators only; for the doctor it must equal the stored value when sent")
    private String licenseNo;

    @JsonProperty("bio")
    @Size(max = 2000, message = "Bio must be at most 2000 characters")
    @Schema(description = "At most 2000 characters; blank clears it")
    private String bio;

    @JsonProperty("isActive")
    @Schema(description = "Accepted and ignored: activation is an administrator decision", deprecated = true)
    private Boolean isActive;

    public void setFirstName(String firstName) {
        this.firstName = trimToNull(firstName);
    }

    public void setLastName(String lastName) {
        this.lastName = trimToNull(lastName);
    }

    public void setEmail(String email) {
        this.email = trimToNull(email);
    }

    public void setPhone(String phone) {
        this.phone = trimToNull(phone);
    }

    public void setSpecialization(String specialization) {
        this.specialization = trimToNull(specialization);
    }

    public void setLicenseNo(String licenseNo) {
        this.licenseNo = trimToNull(licenseNo);
    }

    /** A blank bio is kept as "" so the service can tell "clear it" from "not sent" (null). */
    public void setBio(String bio) {
        this.bio = bio == null ? null : bio.trim();
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
