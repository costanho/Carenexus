package com.carenexus.api.billing.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One payment towards an invoice (table {@code payments}, V1 aligned by V49).
 * <p>
 * The database enforces the status and method sets, {@code amount > 0} and
 * {@code 0 <= refundedAmount <= amount}. {@link #method} takes the {@code PaymentMethod.TYPE_*} values.
 * <p>
 * {@link #gatewayResponse} is stored only and must never appear in a response. The legacy integer column
 * {@code patient_id} is not mapped.
 */
@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_REFUNDED = "REFUNDED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    /** Every stored status (same set as the database CHECK). */
    public static final List<String> STATUSES = List.of(STATUS_PENDING, STATUS_COMPLETED, STATUS_FAILED,
            STATUS_REFUNDED, STATUS_CANCELLED);

    /** What the payer must do while the payment is PENDING (V56 {@code next_action}). */
    public static final String NEXT_ACTION_NONE = "NONE";
    public static final String NEXT_ACTION_STK_PUSH = "STK_PUSH";
    public static final String NEXT_ACTION_AWAIT_CONFIRMATION = "AWAIT_CONFIRMATION";
    public static final String NEXT_ACTION_REDIRECT = "REDIRECT";

    /** Every stored next action (same set as the database CHECK). */
    public static final List<String> NEXT_ACTIONS = List.of(NEXT_ACTION_NONE, NEXT_ACTION_STK_PUSH,
            NEXT_ACTION_AWAIT_CONFIRMATION, NEXT_ACTION_REDIRECT);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_id")
    private Integer paymentId;

    @Column(name = "patient_system_id", nullable = false, length = 20)
    private String patientSystemId;

    @Column(name = "appointment_id")
    private Integer appointmentId;

    @Column(name = "invoice_id")
    private Integer invoiceId;

    /** Saved method used for the payment, when there was one ({@code payment_methods.method_id}). */
    @Column(name = "payment_method_id")
    private Integer paymentMethodId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /** ISO-4217 code. */
    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = Invoice.DEFAULT_CURRENCY;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_PENDING;

    /** One of {@link PaymentMethod#TYPES}; recorded on the payment itself. */
    @Column(name = "method", nullable = false, length = 20)
    private String method;

    /** Confirmation or receipt code reported when the payment settled; unique when set. */
    @Column(name = "transaction_id", length = 255, unique = true)
    private String transactionId;

    /** Name of the gateway that handled the payment. */
    @Column(name = "gateway", length = 50)
    private String gateway;

    /**
     * JSONB column: the sanitised gateway response (no card data, no secrets). Must be valid JSON text.
     * Stored only: never returned by the API.
     */
    @Column(name = "gateway_response", columnDefinition = "JSONB")
    @JdbcTypeCode(SqlTypes.JSON)
    private String gatewayResponse;

    /** Reference of the payment at the gateway, or the reference typed for a manual payment. */
    @Column(name = "provider_reference", length = 255)
    private String providerReference;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    /** Account that started or recorded the payment (patient, caregiver or administrator). */
    @Column(name = "initiated_by_system_id", length = 20)
    private String initiatedBySystemId;

    /** Cumulative amount refunded; never above {@link #amount}. */
    @Column(name = "refunded_amount", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal refundedAmount = new BigDecimal("0.00");

    @Column(name = "refund_reason", length = 500)
    private String refundReason;

    /** MPESA: phone number the payment prompt was sent to. Returned masked only. */
    @Column(name = "payer_phone", length = 20)
    private String payerPhone;

    /**
     * While PENDING: what the payer must do ({@link #NEXT_ACTIONS}), as the gateway reported at initiation. Null for
     * rows written without a gateway (manual, insurance) and cleared when the payment reaches a final state.
     */
    @Column(name = "next_action", length = 30)
    private String nextAction;

    /** Gateway page for {@link #NEXT_ACTION_REDIRECT}; null otherwise. */
    @Column(name = "next_action_url", length = 500)
    private String nextActionUrl;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

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
