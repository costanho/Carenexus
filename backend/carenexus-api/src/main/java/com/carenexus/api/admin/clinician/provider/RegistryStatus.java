package com.carenexus.api.admin.clinician.provider;

/**
 * Answer of a licence registry lookup. Information for the administrator only: it never blocks or grants approval.
 */
public enum RegistryStatus {
    /** The registry confirmed the licence. */
    VERIFIED,
    /** The registry has no such licence. */
    NOT_FOUND,
    /** Nothing was checked automatically; an administrator reviews the licence by hand. */
    UNVERIFIED_MANUAL_REVIEW
}
