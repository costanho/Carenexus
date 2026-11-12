package com.carenexus.api.core.dto.request;

import com.carenexus.api.common.dto.ValidationPatterns;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

/**
 * Body of PUT /api/profile/emergency-contacts/{contactId}. Partial update: omitted or null fields stay unchanged.
 * Strings are trimmed when the body is read (before validation). name and phone, when sent, must be valid (a
 * contact keeps a name and a phone); relationship and email are cleared by {@code ""}. patientSystemId is not part
 * of this body (ignored when sent): a contact never moves to another patient.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Partial update of an emergency contact: only the fields sent are changed")
public class UpdateEmergencyContactRequest {

    @JsonProperty("name")
    @Size(min = 1, message = "Contact name cannot be blank")
    @Size(max = 150, message = "Contact name must be at most 150 characters")
    @Schema(description = "1 to 150 characters", example = "Sarah Dube")
    private String name;

    @JsonProperty("relationship")
    @Size(max = 80, message = "Relationship must be at most 80 characters")
    @Schema(description = "At most 80 characters; \"\" clears", example = "Daughter")
    private String relationship;

    @JsonProperty("phone")
    @Size(max = 20, message = "Phone number must be at most 20 characters")
    @Pattern(regexp = ValidationPatterns.PHONE, message = "Enter a valid phone number")
    @Schema(description = "Optional leading +, 7 to 15 digits; at most 20 characters", example = "+263 77 345 6789")
    private String phone;

    @JsonProperty("email")
    @Size(max = 150, message = "Email must be at most 150 characters")
    @Pattern(regexp = CreateEmergencyContactRequest.EMAIL_PATTERN, message = "Enter a valid email address")
    @Schema(description = "At most 150 characters; \"\" clears", example = "sarah.dube@example.com")
    private String email;

    @JsonProperty("isPrimary")
    @Schema(description = "true: this becomes the primary contact (the previous one is demoted); "
            + "false: this contact is no longer primary")
    private Boolean isPrimary;

    @JsonProperty("isCaregiver")
    @Schema(description = "Informational flag: this person also cares for the patient. Grants nothing")
    private Boolean isCaregiver;

    // ==================== TRIMMING SETTERS (used by Jackson) ====================

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
