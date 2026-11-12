package com.carenexus.api.terminology.provider;

/**
 * Result of {@link TerminologyProvider#validate(String)}.
 */
public enum CodeValidation {
    /** The code is in the provider's catalogue. */
    VERIFIED,
    /** The code is well-formed but the provider's catalogue does not contain it. */
    UNVERIFIED,
    /** The code is not an acceptable ICD-10 code. */
    INVALID
}
