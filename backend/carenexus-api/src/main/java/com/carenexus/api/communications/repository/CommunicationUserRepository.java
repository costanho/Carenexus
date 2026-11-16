package com.carenexus.api.communications.repository;

import com.carenexus.api.auth.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * Batch user lookups for labels (names / roles) in conversations. Read-only use.
 */
@Repository
public interface CommunicationUserRepository extends JpaRepository<User, Integer> {

    List<User> findBySystemIdIn(Collection<String> systemIds);

    /** System ids of every active administrator (recipients of support-thread notifications). */
    @Query("SELECT u.systemId FROM User u WHERE UPPER(u.role) = 'ADMIN' AND u.isActive = true AND u.systemId IS NOT NULL"
            + " ORDER BY u.systemId")
    List<String> findActiveAdminSystemIds();
}
