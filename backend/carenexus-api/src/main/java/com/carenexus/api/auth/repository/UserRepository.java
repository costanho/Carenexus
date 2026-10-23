package com.carenexus.api.auth.repository;

import com.carenexus.api.auth.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Integer> {
    Optional<User> findByEmail(String email);
    Optional<User> findByPhone(String phone);
    Optional<User> findByUserIdAndIsActiveTrue(Integer userId);
    Optional<User> findBySystemId(String systemId);

    @Query("SELECT CAST(SUBSTRING(u.systemId, 5) AS Long) FROM User u WHERE u.systemId LIKE :prefix% ORDER BY u.systemId DESC LIMIT 1")
    Long findMaxSequenceByPrefix(@Param("prefix") String prefix);
}
