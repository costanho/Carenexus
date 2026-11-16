package com.carenexus.api.billing.repository;

import com.carenexus.api.billing.model.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Integer>, JpaSpecificationExecutor<Payment> {

    /** Row-locked read for every status change and refund of one payment. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.paymentId = :paymentId")
    Optional<Payment> findByIdForUpdate(@Param("paymentId") Integer paymentId);

    // ---- by patient ----

    Page<Payment> findByPatientSystemId(String patientSystemId, Pageable pageable);

    Page<Payment> findByPatientSystemIdAndStatusIn(String patientSystemId, Collection<String> statuses,
                                                   Pageable pageable);

    // ---- by status (administrator lists) ----

    Page<Payment> findByStatusIn(Collection<String> statuses, Pageable pageable);

    // ---- by invoice ----

    Page<Payment> findByInvoiceId(Integer invoiceId, Pageable pageable);

    List<Payment> findByInvoiceIdOrderByCreatedAtDescPaymentIdDesc(Integer invoiceId);

    List<Payment> findByInvoiceIdAndStatus(Integer invoiceId, String status);

    /** Sum of the payments of an invoice that are still waiting for the gateway; zero when there is none. */
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.invoiceId = :invoiceId AND p.status = 'PENDING'")
    BigDecimal sumPendingAmountByInvoiceId(@Param("invoiceId") Integer invoiceId);

    // ---- gateway lookups ----

    /**
     * The payment a gateway callback is about. Newest first: a gateway reference is unique in practice, while
     * manual payments (their own gateway name) may repeat a typed reference.
     */
    Optional<Payment> findFirstByGatewayAndProviderReferenceOrderByPaymentIdDesc(String gateway,
                                                                                 String providerReference);

    /**
     * Id of the payment a gateway callback is about (newest first), without loading the entity, so the caller can
     * then read it fresh under {@link #findByIdForUpdate}.
     */
    @Query("SELECT p.paymentId FROM Payment p WHERE p.gateway = :gateway AND p.providerReference = :providerReference "
            + "ORDER BY p.paymentId DESC")
    List<Integer> findIdsByGatewayAndProviderReference(@Param("gateway") String gateway,
                                                       @Param("providerReference") String providerReference,
                                                       Pageable pageable);

    Optional<Payment> findByTransactionId(String transactionId);

    boolean existsByTransactionId(String transactionId);

    /** True when a payment other than {@code paymentId} already carries this confirmation code. */
    boolean existsByTransactionIdAndPaymentIdNot(String transactionId, Integer paymentId);

    // ---- reconciliation sweep ----

    /** Ids of PENDING payments created before {@code cutoff}, oldest first. Pass a page request to bound the batch. */
    @Query("SELECT p.paymentId FROM Payment p WHERE p.status = 'PENDING' AND p.createdAt < :cutoff "
            + "ORDER BY p.createdAt ASC, p.paymentId ASC")
    List<Integer> findPendingIdsCreatedBefore(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    /**
     * Claims up to {@code limit} PENDING payments created before {@code cutoff}, oldest first, with
     * {@code FOR UPDATE SKIP LOCKED}: rows another transaction holds (a webhook settling them right now) are left
     * for the next run. The lock lasts until the end of the calling transaction.
     */
    @Query(value = "SELECT payment_id FROM payments WHERE status = 'PENDING' AND created_at < :cutoff "
            + "ORDER BY created_at ASC, payment_id ASC LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<Integer> claimPendingIdsCreatedBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
