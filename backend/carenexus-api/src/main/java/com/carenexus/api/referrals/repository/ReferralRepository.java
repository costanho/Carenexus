package com.carenexus.api.referrals.repository;

import com.carenexus.api.referrals.model.Referral;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Referrals. Every list is paged; callers pass the fixed order {@code createdAt DESC, referralId DESC}
 * (see {@code ReferralService.LIST_SORT}). A null {@code status} or {@code patientSystemId} filter means "any".
 */
public interface ReferralRepository extends JpaRepository<Referral, Integer> {

    /** The row locked for update, so two concurrent decisions on one referral are applied one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Referral r WHERE r.referralId = :id")
    Optional<Referral> findByIdForUpdate(@Param("id") Integer id);

    /** Referrals the doctor issued. */
    @Query("SELECT r FROM Referral r WHERE r.referringDoctorSystemId = :doctor "
            + "AND (:status IS NULL OR r.status = :status) "
            + "AND (:patient IS NULL OR r.patientSystemId = :patient)")
    Page<Referral> findOutgoing(@Param("doctor") String doctorSystemId,
                                @Param("status") String status,
                                @Param("patient") String patientSystemId,
                                Pageable pageable);

    /** Referrals addressed to the doctor as platform specialist (every status). */
    @Query("SELECT r FROM Referral r WHERE r.specialistSystemId = :doctor "
            + "AND (:status IS NULL OR r.status = :status) "
            + "AND (:patient IS NULL OR r.patientSystemId = :patient)")
    Page<Referral> findIncoming(@Param("doctor") String doctorSystemId,
                                @Param("status") String status,
                                @Param("patient") String patientSystemId,
                                Pageable pageable);

    /** A patient's referrals, every status. */
    Page<Referral> findByPatientSystemId(String patientSystemId, Pageable pageable);
}
