package com.carenexus.api.billing.claim;

import com.carenexus.api.billing.model.InsuranceClaim;
import org.springframework.data.jpa.domain.Specification;

import java.util.Collection;

/**
 * Optional list filters for the claim lists. Every factory matches all rows when its argument is null (or empty),
 * so callers chain them without null checks.
 */
public final class ClaimSpecifications {

    private ClaimSpecifications() {
    }

    public static Specification<InsuranceClaim> patient(String patientSystemId) {
        return (root, query, cb) -> patientSystemId == null
                ? cb.conjunction() : cb.equal(root.get("patientSystemId"), patientSystemId);
    }

    /** Null or empty = every status. */
    public static Specification<InsuranceClaim> statusIn(Collection<String> statuses) {
        return (root, query, cb) -> statuses == null || statuses.isEmpty()
                ? cb.conjunction() : root.get("status").in(statuses);
    }

    public static Specification<InsuranceClaim> invoice(Integer invoiceId) {
        return (root, query, cb) -> invoiceId == null
                ? cb.conjunction() : cb.equal(root.get("invoiceId"), invoiceId);
    }
}
