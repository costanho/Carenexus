package com.carenexus.api.referrals.repository;

import com.carenexus.api.referrals.model.Referral;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Native reads and writes the referrals module makes on tables it does not own (users, doctors, care_team).
 */
public interface ReferralLookupRepository extends Repository<Referral, Integer> {

    /**
     * People named on referrals, in one batch: {@code [system_id, first_name, last_name, role, specialization]}
     * ({@code specialization} is null for anyone without a doctor profile).
     */
    @Query(nativeQuery = true, value = "SELECT u.system_id, u.first_name, u.last_name, u.role, d.specialization "
            + "FROM users u LEFT JOIN doctors d ON d.system_id = u.system_id "
            + "WHERE u.system_id IN (:systemIds)")
    List<Object[]> findPeople(@Param("systemIds") Collection<String> systemIds);

    /**
     * Whether a user can be named as platform specialist:
     * {@code [role, users.is_active, doctors.is_active, doctors.verification_status]} (doctor columns null when
     * there is no doctor profile); empty when the user does not exist.
     */
    @Query(nativeQuery = true, value = "SELECT u.role, u.is_active, d.is_active, d.verification_status "
            + "FROM users u LEFT JOIN doctors d ON d.system_id = u.system_id "
            + "WHERE u.system_id = :systemId")
    List<Object[]> findSpecialistCandidate(@Param("systemId") String systemId);

    /**
     * Gives an accepting platform specialist a care relationship with the patient: inserts an active care_team row
     * (role SPECIALIST, not primary, no provider name) unless an active row for this patient and provider already
     * exists, whatever its role. {@code ON CONFLICT DO NOTHING} also covers a concurrent insert of the same pair
     * (partial unique index on active pairs), so the referral transaction is never aborted.
     *
     * @return 1 when a row was inserted, 0 when the specialist already had an active row
     */
    @Modifying(flushAutomatically = true)
    @Query(nativeQuery = true, value = "INSERT INTO care_team (patient_system_id, provider_system_id, role, joined_date, "
            + "is_primary, is_active, assigned_at, created_at) "
            + "SELECT :patient, :provider, 'SPECIALIST', :joinedDate, FALSE, TRUE, :now, :now "
            + "WHERE NOT EXISTS (SELECT 1 FROM care_team ct WHERE ct.patient_system_id = :patient "
            + "AND ct.provider_system_id = :provider AND ct.is_active) "
            + "ON CONFLICT DO NOTHING")
    int insertSpecialistCareTeamRow(@Param("patient") String patientSystemId,
                                    @Param("provider") String providerSystemId,
                                    @Param("joinedDate") LocalDate joinedDate,
                                    @Param("now") LocalDateTime now);
}
