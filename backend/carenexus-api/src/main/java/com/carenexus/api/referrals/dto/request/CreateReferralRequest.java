package com.carenexus.api.referrals.dto.request;

import com.carenexus.api.common.dto.ValidationPatterns;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/referrals}. Strings are trimmed; optional strings that are blank become null, except
 * {@code specialistSystemId}, which must be absent, null or a valid system id. The referring doctor is always the
 * caller (a doctor id in the body is ignored).
 */
@Schema(description = "A new referral, issued by the caller as referring doctor")
public record CreateReferralRequest(

        @Schema(description = "Patient being referred", example = "PAT-00021", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "is required")
        @Pattern(regexp = "^$|" + ValidationPatterns.SYSTEM_ID, message = "must be a valid system id")
        String patientSystemId,

        @Schema(description = "Reason for referral", maxLength = 2000, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "is required")
        @Size(max = 2000, message = "must be at most 2000 characters")
        String reason,

        @Schema(description = "Clinical summary for the specialist", maxLength = 5000)
        @Size(max = 5000, message = "must be at most 5000 characters")
        String clinicalSummary,

        @Schema(description = "ROUTINE (default), URGENT or EMERGENCY, case-insensitive", example = "URGENT")
        String priority,

        @Schema(description = "Platform specialist (an active, approved doctor other than the caller); "
                + "omit for an external or not yet chosen specialist", example = "DOC-00005")
        @Pattern(regexp = ValidationPatterns.SYSTEM_ID, message = "must be a valid system id")
        String specialistSystemId,

        @Schema(description = "Name of a specialist outside the platform", maxLength = 150)
        @Size(max = 150, message = "must be at most 150 characters")
        String externalSpecialistName,

        @Schema(description = "Hospital or practice of the external specialist", maxLength = 150)
        @Size(max = 150, message = "must be at most 150 characters")
        String externalSpecialistOrganisation,

        @Schema(description = "Phone or email of the external specialist", maxLength = 150)
        @Size(max = 150, message = "must be at most 150 characters")
        String externalSpecialistContact,

        @Schema(description = "Consultation of this patient the referral is issued from", example = "318")
        @Positive(message = "must be greater than 0")
        Integer consultationId) {

    public CreateReferralRequest {
        patientSystemId = TextInput.trim(patientSystemId);
        reason = TextInput.trim(reason);
        clinicalSummary = TextInput.trimToNull(clinicalSummary);
        priority = TextInput.trimToNull(priority);
        specialistSystemId = TextInput.trim(specialistSystemId);
        externalSpecialistName = TextInput.trimToNull(externalSpecialistName);
        externalSpecialistOrganisation = TextInput.trimToNull(externalSpecialistOrganisation);
        externalSpecialistContact = TextInput.trimToNull(externalSpecialistContact);
    }
}
