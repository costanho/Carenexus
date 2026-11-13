package com.carenexus.api.referrals.dto.request;

import com.carenexus.api.common.dto.ValidationPatterns;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PATCH /api/referrals/{id}/specialist}: exactly one of {@code specialistSystemId} and
 * {@code externalSpecialistName}. Strings are trimmed; blank optional strings become null.
 */
@Schema(description = "The specialist to assign: a platform doctor or a specialist outside the platform")
public record AssignReferralSpecialistRequest(

        @Schema(description = "Platform specialist: an active, approved doctor other than the referring doctor",
                example = "DOC-00005")
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
        String externalSpecialistContact) {

    public AssignReferralSpecialistRequest {
        specialistSystemId = TextInput.trim(specialistSystemId);
        externalSpecialistName = TextInput.trimToNull(externalSpecialistName);
        externalSpecialistOrganisation = TextInput.trimToNull(externalSpecialistOrganisation);
        externalSpecialistContact = TextInput.trimToNull(externalSpecialistContact);
    }
}
