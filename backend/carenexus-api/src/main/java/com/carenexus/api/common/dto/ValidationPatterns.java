package com.carenexus.api.common.dto;

/**
 * Regular expressions shared by request DTO constraints ({@code @Pattern(regexp = ValidationPatterns.X)}).
 * Bean Validation treats null as valid for {@code @Pattern}; combine with {@code @NotBlank} when the field is required.
 */
public final class ValidationPatterns {

    /** A person's system id: role prefix, dash, at least five digits (PAT-00001, DOC-00012, CGV-00003, ADM-00001). */
    public static final String SYSTEM_ID = "^(PAT|DOC|CGV|ADM)-\\d{5,}$";

    /** Phone number: optional leading +, then 7 to 15 digits; spaces, dashes, dots and brackets are allowed between them. */
    public static final String PHONE = "^\\+?(?:[ .()-]*\\d){7,15}[ .()-]*$";

    /** 24-hour wall-clock time, zero padded: 00:00 to 23:59. */
    public static final String TIME_HH_MM = "^([01]\\d|2[0-3]):[0-5]\\d$";

    private ValidationPatterns() {
    }
}
