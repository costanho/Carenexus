package com.carenexus.api.core.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Id and name of one of the calling doctor's patients, for pickers and name lookups.
 */
@Schema(description = "A patient of the calling doctor, id and name only")
public record DoctorPatientOptionResponse(
        @Schema(example = "PAT-00001") String patientSystemId,
        @Schema(example = "Thomas") String firstName,
        @Schema(example = "Davis") String lastName,
        @Schema(description = "\"First Last\"", example = "Thomas Davis") String fullName) {

    public static DoctorPatientOptionResponse of(String patientSystemId, String firstName, String lastName) {
        String first = firstName == null ? "" : firstName;
        String last = lastName == null ? "" : lastName;
        return new DoctorPatientOptionResponse(patientSystemId, first, last, (first + " " + last).trim());
    }
}
