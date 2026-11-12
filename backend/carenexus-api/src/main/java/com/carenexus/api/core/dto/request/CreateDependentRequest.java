package com.carenexus.api.core.dto.request;

import com.carenexus.api.auth.dto.request.RegisterRequest;
import com.carenexus.api.common.dto.ValidationPatterns;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Locale;

/**
 * POST /api/caregiver/dependents: a caregiver creates a dependent patient account and their own access grant
 * in one transaction. The caregiver comes from the JWT, never from this body.
 *
 * Two kinds of dependent: with sign-in details ({@code email} AND {@code password}) or managed (neither: the account
 * cannot sign in until the caregiver invites the patient). A managed dependent needs {@code guardianAttested = true}.
 * Blank strings in email, password, phone, dateOfBirth, gender, bloodType, accessLevel and expiresAt count as absent.
 *
 * dateOfBirth is "YYYY-MM-DD"; expiresAt is "YYYY-MM-DD" (end of that day) or an ISO date-time, or null for no expiry.
 * {@code healthStatus} is accepted for old clients and ignored: a new patient always starts STABLE.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "A dependent patient account created by a caregiver, with the caregiver's grant")
public class CreateDependentRequest {

    public static final String ACCESS_LEVEL_PATTERN = "^(FULL_ACCESS|VIEW_ONLY|EDIT_ONLY|CUSTOM)$";
    public static final String ACCESS_LEVEL_MESSAGE =
            "Invalid access level. Must be FULL_ACCESS, VIEW_ONLY, EDIT_ONLY or CUSTOM";

    // Account
    @JsonProperty("firstName")
    @NotBlank(message = "First name is required")
    @Size(max = 100, message = "First name must be at most 100 characters")
    private String firstName;

    @JsonProperty("lastName")
    @NotBlank(message = "Last name is required")
    @Size(max = 100, message = "Last name must be at most 100 characters")
    private String lastName;

    @JsonProperty("email")
    @Pattern(regexp = RegisterRequest.EMAIL_REGEX, message = "Please enter a valid email address")
    @Schema(description = "Sign-in email; leave empty together with password for a managed dependent")
    private String email;

    @JsonProperty("phone")
    @Size(max = 20, message = "Enter a valid phone number")
    @Pattern(regexp = ValidationPatterns.PHONE, message = "Enter a valid phone number")
    @Schema(description = "Optional. A number another account already uses is stored as the patient's contact phone")
    private String phone;

    @JsonProperty("password")
    @Schema(description = "Required with email: 8 to 72 characters with at least one letter and one digit")
    private String password;

    /** Required true for a managed dependent; recorded (guardianAttestedAt) whenever it is true. */
    @JsonProperty("guardianAttested")
    @Schema(description = "The caregiver confirms being this person's parent or legal guardian; required for a managed dependent")
    private Boolean guardianAttested;

    // Health profile
    @JsonProperty("dateOfBirth")
    private String dateOfBirth;

    @JsonProperty("gender")
    private String gender;

    @JsonProperty("bloodType")
    private String bloodType;

    @JsonProperty("healthStatus")
    @Schema(description = "Ignored: a new patient always starts STABLE (only a related doctor sets it)", deprecated = true)
    private String healthStatus;

    @JsonProperty("allergies")
    @TextList(label = "allergies")
    private List<String> allergies;

    @JsonProperty("chronicConditions")
    @TextList(label = "chronic conditions")
    private List<String> chronicConditions;

    // Access grant
    @JsonProperty("relationship")
    @NotBlank(message = "Relationship is required")
    @Size(max = 80, message = "Relationship must be at most 80 characters")
    private String relationship;

    @JsonProperty("accessLevel")
    @Pattern(regexp = ACCESS_LEVEL_PATTERN, message = ACCESS_LEVEL_MESSAGE)
    @Schema(description = "FULL_ACCESS, VIEW_ONLY, EDIT_ONLY or CUSTOM; default VIEW_ONLY")
    private String accessLevel;

    @JsonProperty("expiresAt")
    private String expiresAt;

    @JsonProperty("canViewRecords")
    private Boolean canViewRecords;

    @JsonProperty("canViewAppointments")
    private Boolean canViewAppointments;

    @JsonProperty("canViewMedications")
    private Boolean canViewMedications;

    @JsonProperty("canViewLabResults")
    private Boolean canViewLabResults;

    @JsonProperty("canViewImaging")
    private Boolean canViewImaging;

    @JsonProperty("canMessageCareTeam")
    private Boolean canMessageCareTeam;

    /** Requested permission to join video calls (communication permissions need the patient's approval). */
    @JsonProperty("canJoinCalls")
    private Boolean canJoinCalls;

    @JsonProperty("canViewConsultations")
    private Boolean canViewConsultations;

    @JsonProperty("canScheduleAppointments")
    private Boolean canScheduleAppointments;

    @JsonProperty("canManageCarePlan")
    private Boolean canManageCarePlan;

    /** View the patient's invoices, payments and insurance claims. */
    @JsonProperty("canViewBilling")
    private Boolean canViewBilling;

    /** Pay invoices and manage payment methods and claims for the patient. */
    @JsonProperty("canManageBilling")
    private Boolean canManageBilling;

    // ==================== NORMALISING SETTERS (used by Jackson) ====================

    public void setFirstName(String firstName) {
        this.firstName = trim(firstName);
    }

    public void setLastName(String lastName) {
        this.lastName = trim(lastName);
    }

    public void setEmail(String email) {
        String value = blankToNull(email);
        this.email = value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    public void setPhone(String phone) {
        this.phone = blankToNull(phone);
    }

    /** Blank counts as absent; otherwise the password is kept exactly as typed. */
    public void setPassword(String password) {
        this.password = password == null || password.isBlank() ? null : password;
    }

    public void setDateOfBirth(String dateOfBirth) {
        this.dateOfBirth = blankToNull(dateOfBirth);
    }

    public void setGender(String gender) {
        this.gender = blankToNull(gender);
    }

    public void setBloodType(String bloodType) {
        this.bloodType = blankToNull(bloodType);
    }

    public void setRelationship(String relationship) {
        this.relationship = trim(relationship);
    }

    public void setAccessLevel(String accessLevel) {
        String value = blankToNull(accessLevel);
        this.accessLevel = value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    public void setExpiresAt(String expiresAt) {
        this.expiresAt = blankToNull(expiresAt);
    }

    static String trim(String value) {
        return value == null ? null : value.trim();
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    // ==================== LIST CONSTRAINT ====================

    /**
     * A list of short free-text entries (allergies, chronic conditions): at most 50 entries, each at most 200
     * characters after trimming. Null is valid. Reported on the list field itself.
     */
    @Documented
    @Constraint(validatedBy = TextListValidator.class)
    @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface TextList {
        String message() default "Invalid list";

        /** Plural noun used in the messages, for example "allergies". */
        String label();

        int maxItems() default 50;

        int maxLength() default 200;

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    public static class TextListValidator implements ConstraintValidator<TextList, List<String>> {

        private String label;
        private int maxItems;
        private int maxLength;

        @Override
        public void initialize(TextList annotation) {
            this.label = annotation.label();
            this.maxItems = annotation.maxItems();
            this.maxLength = annotation.maxLength();
        }

        @Override
        public boolean isValid(List<String> values, ConstraintValidatorContext context) {
            if (values == null) {
                return true;
            }
            String problem = null;
            if (values.size() > maxItems) {
                problem = "At most " + maxItems + " " + label + " are allowed";
            } else if (values.stream().anyMatch(v -> v != null && v.trim().length() > maxLength)) {
                problem = "Each of the " + label + " must be at most " + maxLength + " characters";
            }
            if (problem == null) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(problem).addConstraintViolation();
            return false;
        }
    }
}
