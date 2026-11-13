package com.carenexus.api.lifecycle.repository;

import com.carenexus.api.auth.model.User;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Native statements of the account clean-up on tables owned by other slices (table contracts in
 * api/be-account-lifecycle.md 5.2). Statements on tables that may not exist yet are only run after the matching
 * guard ({@link #tableExists}, {@link #columnExists}) returned true.
 */
public interface LifecycleAccountRepository extends Repository<User, Integer> {

    /** Stored role of an account ({@code PATIENT}, {@code DOCTOR}, {@code CAREGIVER}, {@code ADMIN}). */
    @Query(value = "SELECT role FROM users WHERE system_id = :systemId", nativeQuery = true)
    Optional<String> findRole(@Param("systemId") String systemId);

    /** {@code to_regclass} guard: true when the table exists in the search path. */
    @Query(value = "SELECT to_regclass(CAST(:tableName AS text)) IS NOT NULL", nativeQuery = true)
    boolean tableExists(@Param("tableName") String tableName);

    /** True when the column exists on the table in the current schema. */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = current_schema() "
            + "AND table_name = :tableName AND column_name = :columnName)", nativeQuery = true)
    boolean columnExists(@Param("tableName") String tableName, @Param("columnName") String columnName);

    /** Pending referrals addressed to the account as the platform specialist (count only; nothing is changed). */
    @Query(value = "SELECT COUNT(*) FROM referrals WHERE specialist_system_id = :systemId AND status = 'PENDING'",
            nativeQuery = true)
    long countPendingReferralsAsSpecialist(@Param("systemId") String systemId);

    /** Switches off the active reminders addressed to the account (be-reminders V54 columns). */
    @Modifying
    @Query(value = "UPDATE reminders SET is_active = FALSE, next_due_at = NULL "
            + "WHERE recipient_system_id = :systemId AND is_active", nativeQuery = true)
    int deactivateRemindersOfRecipient(@Param("systemId") String systemId);

    /** Same as {@link #deactivateRemindersOfRecipient} for a schema without {@code reminders.next_due_at}. */
    @Modifying
    @Query(value = "UPDATE reminders SET is_active = FALSE WHERE recipient_system_id = :systemId AND is_active",
            nativeQuery = true)
    int deactivateRemindersOfRecipientWithoutNextDue(@Param("systemId") String systemId);

    /** Cancels the pending caregiver access requests about the patient (be-caregiver-requests-consent V44). */
    @Modifying
    @Query(value = "UPDATE caregiver_access_requests SET status = 'CANCELLED' "
            + "WHERE status = 'PENDING' AND patient_system_id = :systemId", nativeQuery = true)
    int cancelPendingAccessRequestsOfPatient(@Param("systemId") String systemId);

    /** Cancels the caregiver's own pending access requests (be-caregiver-requests-consent V44). */
    @Modifying
    @Query(value = "UPDATE caregiver_access_requests SET status = 'CANCELLED' "
            + "WHERE status = 'PENDING' AND caregiver_system_id = :systemId", nativeQuery = true)
    int cancelPendingAccessRequestsOfCaregiver(@Param("systemId") String systemId);
}
