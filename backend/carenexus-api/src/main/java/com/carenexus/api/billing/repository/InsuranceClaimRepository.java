package com.carenexus.api.billing.repository;

import com.carenexus.api.billing.model.InsuranceClaim;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface InsuranceClaimRepository extends JpaRepository<InsuranceClaim, Integer>,
        JpaSpecificationExecutor<InsuranceClaim> {

    /** Row-locked read for every status change of one claim. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM InsuranceClaim c WHERE c.claimId = :claimId")
    Optional<InsuranceClaim> findByIdForUpdate(@Param("claimId") Integer claimId);

    Optional<InsuranceClaim> findByClaimNo(String claimNo);

    /**
     * The keys of a claim without loading the entity into the persistence context, so that a later
     * {@link #findByIdForUpdate} reads the row fresh under its lock.
     */
    Optional<ClaimKey> findKeyByClaimId(Integer claimId);

    /** Next value of {@code claim_no_seq} (V57): the numeric part of a new claim number. */
    @Query(value = "SELECT nextval('claim_no_seq')", nativeQuery = true)
    long nextClaimNumber();

    /** Whether the invoice has an open claim other than {@code claimId} (with {@code InsuranceClaim.OPEN_STATUSES}). */
    boolean existsByInvoiceIdAndStatusInAndClaimIdNot(Integer invoiceId, Collection<String> statuses, Integer claimId);

    // ---- by patient ----

    Page<InsuranceClaim> findByPatientSystemId(String patientSystemId, Pageable pageable);

    Page<InsuranceClaim> findByPatientSystemIdAndStatusIn(String patientSystemId, Collection<String> statuses,
                                                          Pageable pageable);

    // ---- by status (administrator queue) ----

    Page<InsuranceClaim> findByStatusIn(Collection<String> statuses, Pageable pageable);

    // ---- by invoice ----

    List<InsuranceClaim> findByInvoiceIdOrderBySubmittedAtDescClaimIdDesc(Integer invoiceId);

    /** With {@code InsuranceClaim.OPEN_STATUSES}: whether the invoice already has an open claim. */
    boolean existsByInvoiceIdAndStatusIn(Integer invoiceId, Collection<String> statuses);

    /** With {@code InsuranceClaim.OPEN_STATUSES}: the open claim of the invoice (at most one exists). */
    Optional<InsuranceClaim> findFirstByInvoiceIdAndStatusInOrderByClaimIdDesc(Integer invoiceId,
                                                                               Collection<String> statuses);

    /** Projection of the keys of one claim (see {@link #findKeyByClaimId}). */
    interface ClaimKey {
        Integer getClaimId();

        Integer getInvoiceId();

        String getPatientSystemId();
    }
}
