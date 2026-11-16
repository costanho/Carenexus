package com.carenexus.api.billing.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Saved payment method of one account (table {@code payment_methods}, V1 aligned by V49).
 * <p>
 * The database enforces the type set and at most one default per owner among the rows with
 * {@code isDefault AND isActive AND NOT isDeleted}.
 * <p>
 * {@link #providerToken} is stored only and must never appear in a response or a log line. The legacy columns
 * {@code user_id} and {@code token} are not mapped.
 */
@Entity
@Table(name = "payment_methods")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentMethod {

    public static final String TYPE_CARD = "CARD";
    public static final String TYPE_INSURANCE = "INSURANCE";
    public static final String TYPE_CASH = "CASH";
    public static final String TYPE_MPESA = "MPESA";
    public static final String TYPE_BANK_TRANSFER = "BANK_TRANSFER";

    /**
     * Every accepted type (same set as the database CHECK on {@code payment_methods.type} and on
     * {@code payments.method}).
     */
    public static final List<String> TYPES = List.of(TYPE_CARD, TYPE_INSURANCE, TYPE_CASH, TYPE_MPESA,
            TYPE_BANK_TRANSFER);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "method_id")
    private Integer methodId;

    /** Owner of the method. */
    @Column(name = "system_id", nullable = false, length = 20)
    private String systemId;

    @Column(name = "type", nullable = false, length = 20)
    private String type;

    /** CARD: card brand; MPESA and INSURANCE: set by the server. */
    @Column(name = "provider", length = 100)
    private String provider;

    @Column(name = "last_four", length = 4)
    private String lastFour;

    /** MPESA: the phone number as the owner typed it (trimmed). */
    @Column(name = "mpesa_phone", length = 20)
    private String mpesaPhone;

    @Column(name = "insurer_name", length = 100)
    private String insurerName;

    @Column(name = "policy_number", length = 100)
    private String policyNumber;

    @Column(name = "member_number", length = 100)
    private String memberNumber;

    /** Masked label shown in lists; never a full card or phone number. */
    @Column(name = "display_label", length = 120)
    private String displayLabel;

    /** Opaque token of the payment gateway. Stored only: never returned, never logged. */
    @Column(name = "provider_token", length = 255)
    private String providerToken;

    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private Boolean isDefault = false;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    /** Soft delete: removed by the owner, kept for the payments that used it. */
    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private Boolean isDeleted = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
