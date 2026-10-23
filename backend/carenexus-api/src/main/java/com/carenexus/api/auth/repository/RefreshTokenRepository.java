package com.carenexus.api.auth.repository;

import com.carenexus.api.auth.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Integer> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);
    List<RefreshToken> findBySystemIdAndIsRevokedFalse(String systemId);
}
