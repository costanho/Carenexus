package com.carenexus.api.account.datarights.repository;

import com.carenexus.api.auth.model.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The account queries of the data-rights module (the {@code users} entity belongs to the auth module; this is the
 * module's own view of it) plus the one write on {@code doctors} a closure or an anonymisation needs.
 */
public interface DataRightsUserRepository extends Repository<User, Integer> {

    Optional<User> findBySystemId(String systemId);

    /** Writes the account change at once (an anonymised email must be free before the transaction goes on). */
    User saveAndFlush(User user);

    List<User> findBySystemIdIn(Collection<String> systemIds);

    /** Locks the account row for the rest of the transaction (serialises requests of one user). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.systemId = :systemId")
    Optional<User> lockBySystemId(@Param("systemId") String systemId);

    /**
     * Locks every active, open ADMIN account in a fixed order, so two administrators closing their accounts at the
     * same time cannot both pass the last-administrator check.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.role = 'ADMIN' AND u.isActive = true AND u.closedAt IS NULL "
            + "ORDER BY u.userId")
    List<User> lockActiveAdmins();

    /** System ids of the active, open ADMIN accounts other than {@code excludedSystemId}. */
    @Query("SELECT u.systemId FROM User u WHERE u.role = 'ADMIN' AND u.isActive = true AND u.closedAt IS NULL "
            + "AND u.systemId <> :excludedSystemId ORDER BY u.userId")
    List<String> findActiveAdminSystemIdsExcept(@Param("excludedSystemId") String excludedSystemId);

    /** Takes a clinician out of the directory ({@code verification_status} is left as it is). */
    @Modifying
    @Query(value = "UPDATE doctors SET is_active = FALSE WHERE system_id = :systemId AND is_active = TRUE",
            nativeQuery = true)
    int deactivateDoctorProfile(@Param("systemId") String systemId);

    /**
     * Erasure of a patient: clears the doctor's free-text health-status note and its stamps (V52 columns, native SQL
     * only). The coded health_status itself stays, like the rest of the clinical record.
     */
    @Modifying
    @Query(value = "UPDATE patients SET health_status_note = NULL, health_status_updated_at = NULL, "
            + "health_status_updated_by = NULL WHERE system_id = :systemId", nativeQuery = true)
    int clearPatientHealthStatusNote(@Param("systemId") String systemId);
}
