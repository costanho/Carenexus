package com.carenexus.api.account.mfa.repository;

import com.carenexus.api.account.mfa.model.MfaRecoveryCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface MfaRecoveryCodeRepository extends JpaRepository<MfaRecoveryCode, Integer> {

    long countBySystemIdAndUsedAtIsNull(String systemId);

    /**
     * Consumes one unused code: sets used_at when the hash matches an unused code of the account.
     * Returns 1 when a code was consumed, 0 when it is wrong or already used (a conditional update, so two
     * concurrent uses of the same code cannot both succeed).
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE MfaRecoveryCode c SET c.usedAt = :usedAt "
            + "WHERE c.systemId = :systemId AND c.codeHash = :codeHash AND c.usedAt IS NULL")
    int markUsed(@Param("systemId") String systemId, @Param("codeHash") String codeHash,
                 @Param("usedAt") LocalDateTime usedAt);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM MfaRecoveryCode c WHERE c.systemId = :systemId")
    int deleteBySystemId(@Param("systemId") String systemId);
}
