package com.carenexus.api.careteam.dto.request;

/**
 * Constraint texts and string normalisation shared by the care-team request DTOs.
 */
public final class CareTeamRequestRules {

    /** The six writable roles, case-insensitive, surrounding blanks allowed (the service trims and upper-cases). */
    public static final String ROLE_PATTERN = "(?i)^\\s*(DOCTOR|NURSE|LAB|PHARMACY|SPECIALIST|THERAPIST)\\s*$";
    public static final String ROLE_INVALID = "must be one of DOCTOR, NURSE, LAB, PHARMACY, SPECIALIST, THERAPIST";
    public static final String ROLE_REQUIRED = "is required";

    /**
     * {@code ValidationPatterns.PHONE} limited to the 20 characters of {@code care_team.provider_phone}, so a value
     * that is too long or not a phone number yields one error with one message.
     */
    public static final String PHONE_PATTERN = "^(?=.{1,20}$)\\+?(?:[ .()-]*\\d){7,15}[ .()-]*$";

    private CareTeamRequestRules() {
    }

    /** Trimmed value; null stays null and an empty result stays empty (so a pattern still rejects it). */
    static String trim(String value) {
        return value == null ? null : value.trim();
    }

    /** Trimmed value, or null when the value is null or blank. */
    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
