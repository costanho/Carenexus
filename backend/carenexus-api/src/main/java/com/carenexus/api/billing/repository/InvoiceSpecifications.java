package com.carenexus.api.billing.repository;

import com.carenexus.api.billing.model.Invoice;
import org.springframework.data.jpa.domain.Specification;

import java.util.Collection;

/**
 * Optional list filters for {@link InvoiceRepository#findAll(Specification, org.springframework.data.domain.Pageable)}.
 * Every factory returns a specification that matches all rows when its argument is null (or empty), so callers
 * chain them without null checks:
 * <pre>
 * invoiceRepository.findAll(InvoiceSpecifications.patient(patientSystemId)
 *         .and(InvoiceSpecifications.doctor(doctorSystemId))
 *         .and(InvoiceSpecifications.statusIn(statuses)), pageable);
 * </pre>
 */
public final class InvoiceSpecifications {

    private InvoiceSpecifications() {
    }

    public static Specification<Invoice> patient(String patientSystemId) {
        return (root, query, cb) -> patientSystemId == null
                ? cb.conjunction() : cb.equal(root.get("patientSystemId"), patientSystemId);
    }

    public static Specification<Invoice> doctor(String doctorSystemId) {
        return (root, query, cb) -> doctorSystemId == null
                ? cb.conjunction() : cb.equal(root.get("doctorSystemId"), doctorSystemId);
    }

    /** Null or empty = every status. */
    public static Specification<Invoice> statusIn(Collection<String> statuses) {
        return (root, query, cb) -> statuses == null || statuses.isEmpty()
                ? cb.conjunction() : root.get("status").in(statuses);
    }

    public static Specification<Invoice> appointment(Integer appointmentId) {
        return (root, query, cb) -> appointmentId == null
                ? cb.conjunction() : cb.equal(root.get("appointmentId"), appointmentId);
    }

    public static Specification<Invoice> consultation(Integer consultationId) {
        return (root, query, cb) -> consultationId == null
                ? cb.conjunction() : cb.equal(root.get("consultationId"), consultationId);
    }

    /** Invoices that were sent to the patient at some point ({@code issuedAt} is set), whatever their status now. */
    public static Specification<Invoice> issued() {
        return (root, query, cb) -> cb.isNotNull(root.get("issuedAt"));
    }
}
