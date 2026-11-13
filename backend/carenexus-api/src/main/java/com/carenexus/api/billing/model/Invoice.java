package com.carenexus.api.billing.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Invoice of one patient, usually for one visit (table {@code invoices}, V1 aligned by V49).
 * <p>
 * The database enforces: the status set, amounts that are never negative, {@code amountPaid <= total}, and at most
 * one invoice per appointment and per consultation among the rows whose status is not {@code CANCELLED}
 * (a {@code REFUNDED} invoice still counts as the visit's invoice). Status transitions are owned by the invoice
 * service, not by this class.
 * <p>
 * The legacy integer column {@code patient_id} is not mapped.
 */
@Entity
@Table(name = "invoices")
@EntityListeners(InvoiceClockListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Invoice {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_PARTIALLY_PAID = "PARTIALLY_PAID";
    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_OVERDUE = "OVERDUE";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_REFUNDED = "REFUNDED";

    /** Every stored status, in lifecycle order (same set as the database CHECK). */
    public static final List<String> STATUSES = List.of(STATUS_DRAFT, STATUS_SENT, STATUS_PARTIALLY_PAID,
            STATUS_PAID, STATUS_OVERDUE, STATUS_CANCELLED, STATUS_REFUNDED);

    /** Statuses in which an invoice has a balance that can still be paid or claimed. */
    public static final List<String> PAYABLE_STATUSES = List.of(STATUS_SENT, STATUS_PARTIALLY_PAID, STATUS_OVERDUE);

    public static final String DEFAULT_CURRENCY = "KES";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "invoice_id")
    private Integer invoiceId;

    @Column(name = "patient_system_id", nullable = false, length = 20)
    private String patientSystemId;

    /** Issuing doctor: the doctor of the linked consultation or appointment. Null only on legacy rows. */
    @Column(name = "doctor_system_id", length = 20)
    private String doctorSystemId;

    /** Account that created the invoice (the doctor or an administrator). */
    @Column(name = "created_by_system_id", length = 20)
    private String createdBySystemId;

    @Column(name = "appointment_id")
    private Integer appointmentId;

    @Column(name = "consultation_id")
    private Integer consultationId;

    /** {@code INV-<yyyy>-<6 digits of invoice_no_seq>}; unique. */
    @Column(name = "invoice_no", nullable = false, length = 50, unique = true)
    private String invoiceNo;

    /**
     * JSONB column holding a JSON array; each element is
     * {@code {"description": string, "quantity": integer, "unitPrice": number, "amount": number}}.
     * Must be valid JSON text; Hibernate binds it as json.
     */
    @Column(name = "line_items", columnDefinition = "JSONB")
    @JdbcTypeCode(SqlTypes.JSON)
    private String lineItems;

    @Column(name = "subtotal", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal subtotal = zero();

    @Column(name = "tax", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal tax = zero();

    @Column(name = "discount", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal discount = zero();

    @Column(name = "total", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal total = zero();

    /** Sum of the payments applied so far; never above {@link #total}. */
    @Column(name = "amount_paid", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal amountPaid = zero();

    /** ISO-4217 code. */
    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = DEFAULT_CURRENCY;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_DRAFT;

    /** Clinic calendar date. */
    @Column(name = "due_date")
    private LocalDate dueDate;

    /** When the invoice was sent to the patient; null while it was never issued. */
    @Column(name = "issued_at")
    private LocalDateTime issuedAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    /** Free text shown to the patient on the invoice; never clinical detail. */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * Clinic wall-clock time of the last change: stamped by {@link InvoiceClockListener} from the application's clinic
     * {@code Clock}, and also maintained by the {@code update_updated_at_column} trigger on every update (the JDBC
     * session runs in the JVM zone, which the application pins to the clinic zone at startup).
     */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    private static BigDecimal zero() {
        return new BigDecimal("0.00");
    }
}
