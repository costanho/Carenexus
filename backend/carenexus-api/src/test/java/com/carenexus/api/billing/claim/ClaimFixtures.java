package com.carenexus.api.billing.claim;

import com.carenexus.api.auth.model.User;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Invoice and document fixtures for the claim tests, written with plain SQL so they do not depend on the invoice
 * and document services. Invoice numbers are unique per fixture instance (INV-2099-7xxxxx, outside the sequence).
 */
public final class ClaimFixtures {

    private final JdbcTemplate jdbc;
    private final AtomicInteger counter = new AtomicInteger();

    public ClaimFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * An invoice of the patient issued by the doctor, single line, currency KES. {@code issuedAt} is set for every
     * status except DRAFT.
     */
    public Integer invoice(User patient, User doctor, String status, String total, String amountPaid) {
        BigDecimal totalValue = new BigDecimal(total);
        BigDecimal paid = new BigDecimal(amountPaid);
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        String invoiceNo = String.format("INV-2099-%06d", 700000 + counter.incrementAndGet());
        return jdbc.queryForObject("INSERT INTO invoices (patient_system_id, doctor_system_id, created_by_system_id, "
                        + "invoice_no, line_items, subtotal, tax, discount, total, amount_paid, currency, status, due_date, "
                        + "issued_at, paid_at, created_at, updated_at) VALUES (?, ?, ?, ?, CAST(? AS JSONB), ?, 0.00, 0.00, "
                        + "?, ?, 'KES', ?, ?, ?, ?, ?, ?) RETURNING invoice_id",
                Integer.class, patient.getSystemId(), doctor.getSystemId(), doctor.getSystemId(), invoiceNo,
                "[{\"description\": \"Video consultation\", \"quantity\": 1, \"unitPrice\": " + total
                        + ", \"amount\": " + total + "}]",
                totalValue, totalValue, paid, status, LocalDate.now().plusDays(14),
                "DRAFT".equals(status) ? null : now.minusDays(1),
                "PAID".equals(status) ? now : null, now.minusDays(1), now.minusDays(1));
    }

    /** A stored, not deleted document of the patient; returns its id. */
    public Integer document(User patient, String title) {
        return jdbc.queryForObject("INSERT INTO documents (patient_system_id, uploaded_by_system_id, document_type, "
                        + "title, file_url, is_deleted, uploaded_at, storage_key) VALUES (?, ?, 'INSURANCE', ?, "
                        + "'local://test', FALSE, now(), ?) RETURNING document_id",
                Integer.class, patient.getSystemId(), patient.getSystemId(), title,
                "test/" + UUID.randomUUID());
    }

    /** Soft-deletes a document the way DELETE /api/documents/{id} does. */
    public void softDeleteDocument(Integer documentId) {
        jdbc.update("UPDATE documents SET is_deleted = TRUE, deleted_at = now() WHERE document_id = ?", documentId);
    }

    public String invoiceStatus(Integer invoiceId) {
        return jdbc.queryForObject("SELECT status FROM invoices WHERE invoice_id = ?", String.class, invoiceId);
    }

    public BigDecimal amountPaid(Integer invoiceId) {
        return jdbc.queryForObject("SELECT amount_paid FROM invoices WHERE invoice_id = ?", BigDecimal.class, invoiceId);
    }

    public int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
}
