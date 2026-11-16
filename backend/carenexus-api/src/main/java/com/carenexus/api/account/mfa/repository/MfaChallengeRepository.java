package com.carenexus.api.account.mfa.repository;

import com.carenexus.api.account.mfa.model.MfaChallenge;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface MfaChallengeRepository extends JpaRepository<MfaChallenge, Integer> {

    /**
     * The challenge with this token hash, locked until the end of the transaction so concurrent attempts on one
     * challenge are counted one after the other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM MfaChallenge c WHERE c.challengeHash = :challengeHash")
    Optional<MfaChallenge> findByHashForUpdate(@Param("challengeHash") String challengeHash);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM MfaChallenge c WHERE c.systemId = :systemId")
    int deleteBySystemId(@Param("systemId") String systemId);
}
