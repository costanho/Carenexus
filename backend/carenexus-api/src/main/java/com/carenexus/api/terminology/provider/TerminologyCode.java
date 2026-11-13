package com.carenexus.api.terminology.provider;

/**
 * One catalogue entry: an ICD-10 code in dotted upper-case form and its description.
 */
public record TerminologyCode(String code, String description) {
}
