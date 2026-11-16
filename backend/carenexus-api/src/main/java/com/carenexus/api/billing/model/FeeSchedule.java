package com.carenexus.api.billing.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Consultation fee for one appointment type (table {@code billing_fee_schedule}, V49). A row without a doctor
 * is the clinic default; a row with a doctor overrides the default for that doctor. There is at most one row
 * per (doctor, appointment type), the clinic default counting as one "doctor". V49 ships the three default rows.
 */
@Entity
@Table(name = "billing_fee_schedule")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FeeSchedule {

    /** Same values as {@code appointments.type}, in the order fee lists are returned. */
    public static final List<String> APPOINTMENT_TYPES = List.of("VIDEO", "IN_PERSON", "PHONE");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "fee_id")
    private Integer feeId;

    /** Doctor the fee applies to; null = clinic default. */
    @Column(name = "doctor_system_id", length = 20)
    private String doctorSystemId;

    @Column(name = "appointment_type", nullable = false, length = 20)
    private String appointmentType;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /** ISO-4217 code. */
    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = Invoice.DEFAULT_CURRENCY;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** Also maintained by the {@code update_updated_at_column} trigger on every update. */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
