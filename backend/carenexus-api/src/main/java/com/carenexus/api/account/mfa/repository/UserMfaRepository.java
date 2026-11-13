package com.carenexus.api.account.mfa.repository;

import com.carenexus.api.account.mfa.model.UserMfa;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface UserMfaRepository extends JpaRepository<UserMfa, String> {

    /** The enrolment row, locked until the end of the transaction (code checks and state changes). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM UserMfa m WHERE m.systemId = :systemId")
    Optional<UserMfa> findForUpdate(@Param("systemId") String systemId);

    /** True when the account has MFA turned on (not merely a pending setup). */
    boolean existsBySystemIdAndEnabledAtIsNotNull(String systemId);

    /**
     * Starts a pending enrolment unless the account already has a row; returns 1 when inserted, 0 otherwise.
     * Two concurrent setup calls therefore never fail on the primary key: the loser reads the winner's secret.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO user_mfa (system_id, secret_encrypted, created_at) "
            + "VALUES (:systemId, :secretEncrypted, :createdAt) ON CONFLICT (system_id) DO NOTHING",
            nativeQuery = true)
    int insertPendingIfAbsent(@Param("systemId") String systemId,
                              @Param("secretEncrypted") String secretEncrypted,
                              @Param("createdAt") LocalDateTime createdAt);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM UserMfa m WHERE m.systemId = :systemId")
    int deleteBySystemId(@Param("systemId") String systemId);
}
