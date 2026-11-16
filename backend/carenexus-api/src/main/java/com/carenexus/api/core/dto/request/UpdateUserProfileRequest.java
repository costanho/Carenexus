package com.carenexus.api.core.dto.request;

import com.carenexus.api.common.dto.ValidationPatterns;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalDate;

/**
 * Body of PUT /api/profile and PUT /api/profile/user/{systemId}. Partial update: a field that is omitted or null
 * leaves the stored value unchanged. Strings are trimmed when the body is read (before validation).
 *
 * Blank strings: firstName, lastName and phone cannot be removed, so a blank value counts as "unchanged";
 * gender and bloodType are cleared by {@code ""}; allergies and chronicConditions are cleared by {@code ""} or
 * {@code "[]"}. healthStatus and email are read-only here: a non-blank value is refused with 400 by the service.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Partial profile update: only the fields sent are changed")
public class UpdateUserProfileRequest {

    /** Gender: MALE, FEMALE or OTHER (any case), or "" to clear. */
    public static final String GENDER_PATTERN = "^(?i:MALE|FEMALE|OTHER)?$";

    /** Blood type: A, B, AB or O followed by + or - (any case), or "" to clear. */
    public static final String BLOOD_TYPE_PATTERN = "^(?i:(?:A|B|AB|O)[+-])?$";

    @JsonProperty("firstName")
    @Size(max = 100, message = "First name must be at most 100 characters")
    @Schema(description = "At most 100 characters; blank leaves the name unchanged", example = "Linda")
    private String firstName;

    @JsonProperty("lastName")
    @Size(max = 100, message = "Last name must be at most 100 characters")
    @Schema(description = "At most 100 characters; blank leaves the name unchanged", example = "Moyo")
    private String lastName;

    @JsonProperty("phone")
    @Size(max = 20, message = "Phone number must be at most 20 characters")
    @Pattern(regexp = ValidationPatterns.PHONE, message = "Enter a valid phone number")
    @Schema(description = "Optional leading +, 7 to 15 digits; spaces, dashes, dots and brackets allowed. "
            + "Blank leaves the phone unchanged", example = "+263 77 212 3456")
    private String phone;

    @JsonProperty("email")
    @Schema(description = "Read-only here: any non-blank value is refused with 400 (use the email-change flow)")
    private String email;

    @JsonProperty("dateOfBirth")
    @Past(message = "Date of birth must be in the past")
    @Schema(description = "PATIENT accounts only; yyyy-MM-dd, before today (clinic zone)", example = "1958-03-14")
    private LocalDate dateOfBirth;

    @JsonProperty("gender")
    @Pattern(regexp = GENDER_PATTERN, message = "Gender must be MALE, FEMALE or OTHER")
    @Schema(description = "PATIENT accounts only; MALE, FEMALE or OTHER, \"\" clears", example = "FEMALE")
    private String gender;

    @JsonProperty("bloodType")
    @Pattern(regexp = BLOOD_TYPE_PATTERN, message = "Blood type must be one of A+, A-, B+, B-, AB+, AB-, O+, O-")
    @Schema(description = "PATIENT accounts only; A+, A-, B+, B-, AB+, AB-, O+, O-, \"\" clears", example = "O+")
    private String bloodType;

    @JsonProperty("allergies")
    @Size(max = 6000, message = "Allergies must be at most 6000 characters")
    @Schema(description = "PATIENT accounts only; a string holding a JSON array of strings (at most 50 entries of "
            + "1 to 100 characters); \"\" or \"[]\" clears", example = "[\"Penicillin\",\"Latex\"]")
    private String allergies;

    @JsonProperty("chronicConditions")
    @Size(max = 6000, message = "Chronic conditions must be at most 6000 characters")
    @Schema(description = "PATIENT accounts only; same encoding as allergies", example = "[\"Hypertension\"]")
    private String chronicConditions;

    @JsonProperty("healthStatus")
    @Schema(description = "Read-only here: any non-blank value is refused with 400 (set by the patient's doctor)")
    private String healthStatus;

    // ==================== TRIMMING SETTERS (used by Jackson) ====================

    public void setFirstName(String firstName) {
        this.firstName = blankToNull(firstName);
    }

    public void setLastName(String lastName) {
        this.lastName = blankToNull(lastName);
    }

    public void setPhone(String phone) {
        this.phone = blankToNull(phone);
    }

    public void setEmail(String email) {
        this.email = trim(email);
    }

    public void setGender(String gender) {
        this.gender = trim(gender);
    }

    public void setBloodType(String bloodType) {
        this.bloodType = trim(bloodType);
    }

    public void setAllergies(String allergies) {
        this.allergies = trim(allergies);
    }

    public void setChronicConditions(String chronicConditions) {
        this.chronicConditions = trim(chronicConditions);
    }

    public void setHealthStatus(String healthStatus) {
        this.healthStatus = trim(healthStatus);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String blankToNull(String value) {
        String trimmed = trim(value);
        return trimmed == null || trimmed.isEmpty() ? null : trimmed;
    }
}
