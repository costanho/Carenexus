package com.carenexus.api.billing.provider;

import com.carenexus.api.billing.model.InsuranceClaim;

import java.util.Map;
import java.util.Optional;

/**
 * Built-in claims provider ({@code carenexus.billing.claims-provider=manual}, the default). It makes no external
 * call: the claim is reviewed inside the clinic and an administrator moves its status through
 * PATCH /api/admin/billing/claims/{id}. The provider reference is {@code MANUAL-<claimNo>}. There are no provider
 * callbacks, so {@link #parseStatusUpdate} never returns an update.
 */
public class ManualInsuranceClaimProvider implements InsuranceClaimProvider {

    public static final String NAME = "manual";
    public static final String REFERENCE_PREFIX = "MANUAL-";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String submit(InsuranceClaim claim) {
        if (claim == null || claim.getClaimNo() == null || claim.getClaimNo().isBlank()) {
            throw new IllegalArgumentException("A claim number is required before a claim is submitted");
        }
        return REFERENCE_PREFIX + claim.getClaimNo();
    }

    @Override
    public Optional<ClaimStatusUpdate> parseStatusUpdate(String rawBody, Map<String, String> headers) {
        return Optional.empty();
    }
}
