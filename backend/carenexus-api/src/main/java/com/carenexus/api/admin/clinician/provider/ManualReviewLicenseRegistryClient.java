package com.carenexus.api.admin.clinician.provider;

/**
 * The local licence registry: no external registry is consulted, every licence is reviewed by an administrator.
 * Always answers {@link RegistryStatus#UNVERIFIED_MANUAL_REVIEW}.
 */
public class ManualReviewLicenseRegistryClient implements LicenseRegistryClient {

    public static final String PROVIDER = "manual";

    @Override
    public String providerName() {
        return PROVIDER;
    }

    @Override
    public RegistryStatus lookup(String licenseNo, String licensingBody, String country) {
        return RegistryStatus.UNVERIFIED_MANUAL_REVIEW;
    }
}
