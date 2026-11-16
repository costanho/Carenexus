package com.carenexus.api.communications.dto.response;

import lombok.*;


@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecipientResponse {

    private String systemId;

    private String name;

    private String role;

    private String label;

    private Boolean canStartPrivate;

    /** Can be included in a new CARE_TEAM conversation started by the caller. */
    private Boolean canAddToCareTeam;

    /** Caller may add this person to an existing CARE_TEAM conversation (caller is the patient or a doctor). */
    private Boolean canAddToExistingCareTeam;
}
