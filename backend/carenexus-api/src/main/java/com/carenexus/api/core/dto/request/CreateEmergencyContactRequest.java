package com.carenexus.api.core.dto.request;

import com.carenexus.api.common.dto.ValidationPatterns;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

/**
 * Body of POST /api/profile/emergency-contacts. Strings are trimmed when the body is read (before validation);
 * a blank relationship or email is stored as null.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "A new emergency contact of a patient")
public class CreateEmergencyContactRequest {

    /** Email address of a contact, or "" (no email). */
    public static final String EMAIL_PATTERN = "^$|^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$";

    /** Phone pattern that leaves an empty value to {@code @NotBlank} (one message per problem). */
    public static final String PHONE_OR_EMPTY_PATTERN = "^$|" + ValidationPatterns.PHONE;

    @JsonProperty("patientSystemId")
    @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = "Invalid patient id")
    @Schema(description = "The patient the contact belongs to. PATIENT callers may omit it (defaults to themselves); "
            + "required for CAREGIVER and ADMIN callers", example = "PAT-00001")
    private String patientSystemId;

    @JsonProperty("name")
    @NotBlank(message = "Contact name is required")
    @Size(max = 150, message = "Contact name must be at most 150 characters")
    @Schema(description = "1 to 150 characters", example = "Sarah Dube", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @JsonProperty("relationship")
    @Size(max = 80, message = "Relationship must be at most 80 characters")
    @Schema(description = "Free text, at most 80 characters", example = "Daughter")
    private String relationship;

    @JsonProperty("phone")
    @NotBlank(message = "Phone number is required")
    @Size(max = 20, message = "Phone number must be at most 20 characters")
    @Pattern(regexp = PHONE_OR_EMPTY_PATTERN, message = "Enter a valid phone number")
    @Schema(description = "Optional leading +, 7 to 15 digits; at most 20 characters", example = "+263773456789",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String phone;

    @JsonProperty("email")
    @Size(max = 150, message = "Email must be at most 150 characters")
    @Pattern(regexp = EMAIL_PATTERN, message = "Enter a valid email address")
    @Schema(description = "At most 150 characters", example = "sarah.dube@example.com")
    private String email;

    @JsonProperty("isPrimary")
    @Schema(description = "Default false; true makes this the patient's only primary contact")
    private Boolean isPrimary;

    @JsonProperty("isCaregiver")
    @Schema(description = "Informational flag: this person also cares for the patient. Grants nothing. Default false")
    private Boolean isCaregiver;

    // ==================== TRIMMING SETTERS (used by Jackson) ====================

    public void setPatientSystemId(String patientSystemId) {
        String trimmed = trim(patientSystemId);
        this.patientSystemId = trimmed == null || trimmed.isEmpty() ? null : trimmed;
    }

    public void setName(String name) {
        this.name = trim(name);
    }

    public void setRelationship(String relationship) {
        this.relationship = trim(relationship);
    }

    public void setPhone(String phone) {
        this.phone = trim(phone);
    }

    public void setEmail(String email) {
        this.email = trim(email);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
