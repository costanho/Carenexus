package com.carenexus.api.core.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * A medication prescription (table {@code prescriptions}, V1 re-keyed in V3).
 *
 * Lifecycle: {@link #ACTIVE} (initial) and {@link #ON_HOLD} interchange; either can become {@link #COMPLETED},
 * {@link #CANCELLED} or {@link #EXPIRED}, which are terminal. After issue only {@code specialInstructions},
 * {@code expiryDate} and {@code refillsAllowed} may change; rows are never deleted (cancel instead).
 */
@Entity
@Table(name = "prescriptions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Prescription {

    public static final String ACTIVE = "ACTIVE";
    public static final String ON_HOLD = "ON_HOLD";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";
    public static final String EXPIRED = "EXPIRED";

    /** Every status, in the order used by error messages. */
    public static final List<String> STATUSES = List.of(ACTIVE, ON_HOLD, COMPLETED, CANCELLED, EXPIRED);

    /** Statuses that can no longer change. */
    public static final Set<String> TERMINAL_STATUSES = Set.of(COMPLETED, CANCELLED, EXPIRED);

    public static final String ROUTE_ORAL = "ORAL";

    /** Administration routes allowed by the table's CHECK constraint. */
    public static final List<String> ROUTES = List.of("ORAL", "TOPICAL", "INJECTION", "INHALED", "SUBLINGUAL", "RECTAL", "IV");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "prescription_id")
    private Integer prescriptionId;

    @Column(name = "patient_system_id", nullable = false, length = 20)
    private String patientSystemId;

    @Column(name = "doctor_system_id", nullable = false, length = 20)
    private String doctorSystemId;

    @Column(name = "consultation_id")
    private Integer consultationId;

    @Column(name = "medication_name", nullable = false, length = 200)
    private String medicationName;

    @Column(name = "generic_name", length = 200)
    private String genericName;

    @Column(name = "dosage", nullable = false, length = 100)
    private String dosage;

    @Column(name = "frequency", nullable = false, length = 100)
    private String frequency;

    @Column(name = "route", nullable = false, length = 20)
    @Builder.Default
    private String route = ROUTE_ORAL;

    @Column(name = "quantity", length = 50)
    private String quantity;

    @Column(name = "refills_allowed", nullable = false)
    @Builder.Default
    private Integer refillsAllowed = 0;

    @Column(name = "special_instructions", columnDefinition = "TEXT")
    private String specialInstructions;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = ACTIVE;

    @Column(name = "prescribed_date", nullable = false)
    private LocalDate prescribedDate;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        // The JVM default zone is the clinic zone (set at startup), so these are clinic wall-clock values.
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (prescribedDate == null) {
            prescribedDate = LocalDate.now();
        }
    }

    /** True when the stored status can no longer change. */
    public boolean isTerminal() {
        return TERMINAL_STATUSES.contains(status);
    }

    /**
     * The status the expiry sweeper will store at its next run: {@link #EXPIRED} when the stored status is
     * {@link #ACTIVE} or {@link #ON_HOLD} and {@code expiryDate} is before {@code today}; otherwise the stored status.
     * A prescription is valid through its expiry date (inclusive).
     */
    public String effectiveStatus(LocalDate today) {
        if ((ACTIVE.equals(status) || ON_HOLD.equals(status)) && expiryDate != null && today != null
                && expiryDate.isBefore(today)) {
            return EXPIRED;
        }
        return status;
    }
}
