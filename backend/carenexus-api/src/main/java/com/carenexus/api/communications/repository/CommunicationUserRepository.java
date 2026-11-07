package com.carenexus.api.communications.repository;

import com.carenexus.api.auth.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * Batch user lookups for labels (names / roles) in conversations. Read-only use.
 */
@Repository
public interface CommunicationUserRepository extends JpaRepository<User, Integer> {

    List<User> findBySystemIdIn(Collection<String> systemIds);
}
