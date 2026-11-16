package com.carenexus.api.admin.clinician.provider;

/**
 * Seam to a medical-council licence registry. Selected by {@code carenexus.auth.licence-registry}
 * ({@code manual} = {@link ManualReviewLicenseRegistryClient}, the shipped implementation).
 *
 * Implementations must be fast or cache their answers: the clinician queue calls {@link #lookup} once per row it
 * returns. They never throw for an unknown licence (that is {@link RegistryStatus#NOT_FOUND}); when the registry
 * cannot be reached they answer {@link RegistryStatus#UNVERIFIED_MANUAL_REVIEW}. Licence numbers are not logged.
 */
public interface LicenseRegistryClient {

    /** Provider id as configured, for example {@code manual}. */
    String providerName();

    /**
     * @param licenseNo     licence number as stored (never null or blank: callers skip the lookup without one)
     * @param licensingBody issuing regulator; may be null
     * @param country       ISO 3166-1 alpha-2 country of the licence, upper-case; may be null
     */
    RegistryStatus lookup(String licenseNo, String licensingBody, String country);
}
