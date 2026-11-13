package com.carenexus.api.communications.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

/** A person the caller may message about a patient. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecipientResponse {

    private String systemId;

    @Schema(description = "\"First Last\"")
    private String name;

    @Schema(description = "PATIENT, DOCTOR or CAREGIVER")
    private String role;

    @Schema(description = "Display label, e.g. \"Dr. Amara Osei\" or \"Sarah Dube (Caregiver for Thomas Dube)\"")
    private String label;

    @Schema(description = "The caller and this person form an allowed PRIVATE conversation (a doctor with the patient "
            + "or one of the patient's caregivers)")
    private Boolean canStartPrivate;

    /** Can be included in a new CARE_TEAM conversation started by the caller. */
    @Schema(description = "May be included when the caller starts a CARE_TEAM conversation")
    private Boolean canAddToCareTeam;

    /** Caller may add this person to an existing CARE_TEAM conversation (caller is the patient or a doctor). */
    @Schema(description = "The caller may add this person to an existing CARE_TEAM conversation (caller is the "
            + "patient or a doctor)")
    private Boolean canAddToExistingCareTeam;
}
