package com.carenexus.api.billing.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Insurance claim against an invoice (table {@code insurance_claims}, V1 aligned by V49).
 * <p>
 * The database enforces the status set, {@code claimAmount > 0}, a unique {@code claimNo} and at most one open
 * claim ({@link #OPEN_STATUSES}) per invoice. Status transitions are owned by the claim service.
 * <p>
 * The legacy columns {@code patient_id} and {@code documents} are not mapped. Claim numbers come from the
 * {@code claim_no_seq} sequence (V57). The service sets {@code submittedAt}, {@code resolvedAt} and
 * {@code updatedAt} from the clinic clock; the callbacks below are only fallbacks.
 */
@Entity
@Table(name = "insurance_claims")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InsuranceClaim {

    public static final String STATUS_SUBMITTED = "SUBMITTED";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_APPEALED = "APPEALED";

    /** Every stored status (same set as the database CHECK). */
    public static final List<String> STATUSES = List.of(STATUS_SUBMITTED, STATUS_PENDING, STATUS_APPROVED,
            STATUS_REJECTED, STATUS_PARTIAL, STATUS_APPEALED);

    /** A claim in one of these statuses is open; an invoice has at most one open claim. */
    public static final List<String> OPEN_STATUSES = List.of(STATUS_SUBMITTED, STATUS_PENDING, STATUS_APPEALED);

    /** A claim in one of these statuses has been decided by an administrator. */
    public static final List<String> DECIDED_STATUSES = List.of(STATUS_APPROVED, STATUS_PARTIAL, STATUS_REJECTED);

    /** A decided claim in one of these statuses can be appealed. */
    public static final List<String> APPEALABLE_STATUSES = List.of(STATUS_REJECTED, STATUS_PARTIAL);

    /** Statuses an administrator can move a claim to (adjudication). */
    public static final List<String> ADJUDICATION_TARGETS = List.of(STATUS_PENDING, STATUS_APPROVED, STATUS_PARTIAL,
            STATUS_REJECTED);

    /** Prefix of a claim number: {@code CLM-<year>-<six or more digits>}. */
    public static final String CLAIM_NO_PREFIX = "CLM-";

    /** Value of {@link #documentIds} when no document is attached. */
    public static final String NO_DOCUMENTS = "[]";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "claim_id")
    private Integer claimId;

    @Column(name = "patient_system_id", nullable = false, length = 20)
    private String patientSystemId;

    /** Payment that settled the approved amount onto the invoice. */
    @Column(name = "payment_id")
    private Integer paymentId;

    @Column(name = "invoice_id")
    private Integer invoiceId;

    /** Human-readable claim number; unique when set. */
    @Column(name = "claim_no", length = 50, unique = true)
    private String claimNo;

    @Column(name = "insurance_provider", nullable = false, length = 150)
    private String insuranceProvider;

    @Column(name = "policy_number", nullable = false, length = 100)
    private String policyNumber;

    @Column(name = "member_number", length = 100)
    private String memberNumber;

    @Column(name = "claim_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal claimAmount;

    /** Cumulative amount approved so far; null until a decision approves something. */
    @Column(name = "approved_amount", precision = 12, scale = 2)
    private BigDecimal approvedAmount;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "appeal_reason", columnDefinition = "TEXT")
    private String appealReason;

    /** Reference of the claim at the claims provider. */
    @Column(name = "provider_reference", length = 255)
    private String providerReference;

    /**
     * JSONB column holding a JSON array of Integer document ids, for example {@code [12, 15]};
     * {@code []} when none. Must be valid JSON text.
     */
    @Column(name = "document_ids", nullable = false, columnDefinition = "JSONB")
    @JdbcTypeCode(SqlTypes.JSON)
    @Builder.Default
    private String documentIds = NO_DOCUMENTS;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_SUBMITTED;

    /** Account that submitted the claim (patient, caregiver or administrator). */
    @Column(name = "submitted_by_system_id", length = 20)
    private String submittedBySystemId;

    /** Administrator who recorded the last decision. */
    @Column(name = "reviewed_by_system_id", length = 20)
    private String reviewedBySystemId;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    /** Also maintained by the {@code update_updated_at_column} trigger on every update. */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (submittedAt == null) {
            submittedAt = now;
        }
        if (updatedAt == null) {
            updatedAt = submittedAt;
        }
        if (documentIds == null) {
            documentIds = NO_DOCUMENTS;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
