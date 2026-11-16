package com.carenexus.api.billing.provider;

import com.carenexus.api.billing.model.InsuranceClaim;
import com.carenexus.api.billing.provider.claims.InsuranceClaimProviderException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * Seam to an insurer or clearing house that receives insurance claims.
 * <p>
 * The built-in implementation is {@link ManualInsuranceClaimProvider}: it makes no external call and the claim's
 * status is moved by an administrator (PATCH /api/admin/billing/claims/{id}). A real adapter replaces it through
 * {@code carenexus.billing.claims-provider} (see {@code BillingClaimsConfig}) and adds its own signed webhook
 * endpoint that feeds {@link #parseStatusUpdate} into the claim service.
 * <p>
 * Implementations never log secrets, policy or member numbers, or request and response bodies.
 */
public interface InsuranceClaimProvider {

    /** Configuration name of the provider, for example {@code manual}. */
    String name();

    /**
     * Hands a new claim to the provider. Called once per claim, inside the submitting transaction, after the claim
     * number is assigned and before the row is saved.
     *
     * @param claim the claim to submit (claim number, invoice, patient, insurer, policy, amount and documents set)
     * @return the provider's reference of the claim (stored as {@code providerReference}); never null
     * @throws InsuranceClaimProviderException when the provider cannot be reached or refuses the request; the
     *                                         submission then fails with 503 and nothing is stored
     */
    String submit(InsuranceClaim claim);

    /**
     * Reads a status callback of the provider. The caller has already checked the transport (signature, size).
     *
     * @param rawBody the raw request body
     * @param headers request headers, lower-case names
     * @return the update, or empty when the body is not a claim status update this provider understands
     */
    Optional<ClaimStatusUpdate> parseStatusUpdate(String rawBody, Map<String, String> headers);

    /**
     * A status reported by the provider for one claim.
     *
     * @param providerReference the reference returned by {@link #submit}
     * @param status            one of {@code InsuranceClaim.ADJUDICATION_TARGETS}
     * @param approvedAmount    cumulative approved amount for APPROVED or PARTIAL; null otherwise
     * @param reason            rejection reason or reviewer's note; null when none
     * @param eventId           the provider's unique id of this update (idempotency); null when it has none
     */
    record ClaimStatusUpdate(String providerReference, String status, BigDecimal approvedAmount, String reason,
                             String eventId) {
    }
}
