package com.carenexus.api.admin.user.repository;

import com.carenexus.api.auth.model.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The users queries of the admin module. Searches go through {@link JpaSpecificationExecutor}; the unmapped column
 * {@code users.deactivation_reason} (V32) and the activity counts on other tables are read with native SQL.
 */
public interface AdminUserRepository extends Repository<User, Integer>, JpaSpecificationExecutor<User> {

    Optional<User> findBySystemId(String systemId);

    User save(User user);

    /**
     * Every active administrator, locked for update: serialises concurrent deactivations so two administrators
     * cannot deactivate each other and leave the system without one.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.role = 'ADMIN' AND u.isActive = true ORDER BY u.userId")
    List<User> lockActiveAdmins();

    @Query(value = "SELECT deactivation_reason FROM users WHERE system_id = :systemId", nativeQuery = true)
    String findDeactivationReason(@Param("systemId") String systemId);

    /** Sets (or with null clears) the reason of the last administrator deactivation. */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE users SET deactivation_reason = :reason WHERE system_id = :systemId", nativeQuery = true)
    int updateDeactivationReason(@Param("systemId") String systemId, @Param("reason") String reason);

    /** Sessions that can still be refreshed: distinct session ids with a token that is neither revoked nor expired. */
    @Query(value = "SELECT COUNT(DISTINCT session_id) FROM refresh_tokens "
            + "WHERE system_id = :systemId AND is_revoked = FALSE AND expires_at > :now", nativeQuery = true)
    long countActiveSessions(@Param("systemId") String systemId, @Param("now") LocalDateTime now);

    @Query(value = "SELECT COUNT(*) FROM appointments WHERE doctor_system_id = :systemId "
            + "AND status IN ('SCHEDULED', 'RESCHEDULED') AND scheduled_at > :now", nativeQuery = true)
    long countFutureActiveAppointmentsAsDoctor(@Param("systemId") String systemId, @Param("now") LocalDateTime now);

    @Query(value = "SELECT COUNT(*) FROM appointments WHERE patient_system_id = :systemId "
            + "AND status IN ('SCHEDULED', 'RESCHEDULED') AND scheduled_at > :now", nativeQuery = true)
    long countFutureActiveAppointmentsAsPatient(@Param("systemId") String systemId, @Param("now") LocalDateTime now);
}
