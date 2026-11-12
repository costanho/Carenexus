package com.carenexus.api.auth.dto.request;

import com.carenexus.api.auth.dto.validation.StrongPassword;
import com.carenexus.api.common.dto.ValidationPatterns;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Body of {@code POST /api/auth/register}. Text fields are trimmed when they are set and a blank optional value
 * counts as absent; the password is used exactly as sent.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Details of the account to create")
public class RegisterRequest {

    /** Same rule the service applies: one @, a dot in the domain, no spaces, at most 150 characters. */
    public static final String EMAIL_REGEX = "^(?=.{1,150}$)[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$";

    @NotBlank(message = "First name is required")
    @Size(max = 100, message = "First name must be at most 100 characters")
    @Schema(example = "Tendai", maxLength = 100)
    private String firstName;

    @NotBlank(message = "Last name is required")
    @Size(max = 100, message = "Last name must be at most 100 characters")
    @Schema(example = "Moyo", maxLength = 100)
    private String lastName;

    @NotBlank(message = "Email is required")
    @Pattern(regexp = EMAIL_REGEX, message = "Please enter a valid email address")
    @Schema(description = "Login email; stored lower-case, unique", example = "tendai.moyo@example.com", maxLength = 150)
    private String email;

    @StrongPassword
    @Schema(description = "At least 8 characters with one letter and one digit; at most 72 bytes",
            example = "Str0ng-Passw0rd", requiredMode = Schema.RequiredMode.REQUIRED)
    private String password;

    @Size(max = 20, message = "Phone number must be at most 20 characters")
    @Pattern(regexp = ValidationPatterns.PHONE, message = "Please enter a valid phone number")
    @Schema(description = "Optional; unique among users when present", example = "+263 77 123 4567", nullable = true)
    private String phone;

    @NotBlank(message = "Role is required")
    @Size(max = 20, message = "must be at most 20 characters")
    @Schema(description = "Any letter case. PROXY is stored as CAREGIVER; ADMIN is refused; a DOCTOR starts pending "
            + "verification", allowableValues = {"PATIENT", "DOCTOR", "CAREGIVER", "PROXY"}, example = "PATIENT")
    private String role;

    @Size(max = 20, message = "must be at most 20 characters")
    @Schema(description = "Only with role PATIENT: an active caregiver account that receives a view-only grant "
            + "authorised by the new patient", example = "CGV-00003", nullable = true)
    private String caregiverSystemId;

    @Size(max = 80, message = "Relationship must be at most 80 characters")
    @Schema(description = "Used only with caregiverSystemId; defaults to \"caregiver\"", example = "daughter", nullable = true)
    private String relationship;

    @Size(max = 50, message = "Licence number must be at most 50 characters")
    @Schema(description = "Stored only for role DOCTOR; unique among doctors whatever the letter case",
            example = "MDPCZ-12345", nullable = true)
    private String licenseNo;

    @Size(max = 150, message = "Specialization must be at most 150 characters")
    @Schema(description = "Stored only for role DOCTOR", example = "Cardiology", nullable = true)
    private String specialization;

    @Size(max = 150, message = "Licensing body must be at most 150 characters")
    @Schema(description = "Regulator that issued the licence; stored only for role DOCTOR",
            example = "Medical and Dental Practitioners Council of Zimbabwe", nullable = true)
    private String licensingBody;

    @Pattern(regexp = "^[A-Za-z]{2}$", message = "Licence country must be a two-letter country code")
    @Schema(description = "ISO 3166-1 alpha-2 country of the licence, stored upper-case; stored only for role DOCTOR",
            example = "ZW", nullable = true)
    private String licenseCountry;

    @Size(max = 20, message = "must be at most 20 characters")
    @Schema(description = "Version of the terms of service the user accepted; absent means not accepted yet",
            example = "1", nullable = true)
    private String acceptedTermsVersion;

    @Size(max = 20, message = "must be at most 20 characters")
    @Schema(description = "Version of the privacy notice the user accepted; absent means not accepted yet",
            example = "1", nullable = true)
    private String acceptedPrivacyVersion;

    @Size(max = 4096, message = "must be at most 4096 characters")
    @Schema(description = "Token of the human-verification widget, when one is configured", nullable = true)
    private String humanVerificationToken;

    public void setFirstName(String firstName) {
        this.firstName = RequestText.clean(firstName);
    }

    public void setLastName(String lastName) {
        this.lastName = RequestText.clean(lastName);
    }

    public void setEmail(String email) {
        this.email = RequestText.cleanLower(email);
    }

    /** Not trimmed: a password is used exactly as sent. */
    public void setPassword(String password) {
        this.password = password;
    }

    public void setPhone(String phone) {
        this.phone = RequestText.clean(phone);
    }

    public void setRole(String role) {
        this.role = RequestText.clean(role);
    }

    public void setCaregiverSystemId(String caregiverSystemId) {
        this.caregiverSystemId = RequestText.clean(caregiverSystemId);
    }

    public void setRelationship(String relationship) {
        this.relationship = RequestText.clean(relationship);
    }

    public void setLicenseNo(String licenseNo) {
        this.licenseNo = RequestText.clean(licenseNo);
    }

    public void setSpecialization(String specialization) {
        this.specialization = RequestText.clean(specialization);
    }

    public void setLicensingBody(String licensingBody) {
        this.licensingBody = RequestText.clean(licensingBody);
    }

    public void setLicenseCountry(String licenseCountry) {
        this.licenseCountry = RequestText.cleanUpper(licenseCountry);
    }

    public void setAcceptedTermsVersion(String acceptedTermsVersion) {
        this.acceptedTermsVersion = RequestText.clean(acceptedTermsVersion);
    }

    public void setAcceptedPrivacyVersion(String acceptedPrivacyVersion) {
        this.acceptedPrivacyVersion = RequestText.clean(acceptedPrivacyVersion);
    }

    public void setHumanVerificationToken(String humanVerificationToken) {
        this.humanVerificationToken = RequestText.clean(humanVerificationToken);
    }
}
