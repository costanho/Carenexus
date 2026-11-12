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
    Optional<User> findFirstByEmailIgnoreCaseOrderByUserIdAsc(String email);
    boolean existsByEmailIgnoreCase(String email);
    boolean existsByRoleAndIsActiveTrue(String role);
    boolean existsByEmailIgnoreCaseAndIsActiveTrue(String email);
    boolean existsByIsActiveTrueAndPasswordHashIn(java.util.Collection<String> passwordHashes);
    /** True when an active account uses one of the given email addresses (pass them lower-case). */
    @Query("SELECT COUNT(u) > 0 FROM User u WHERE u.isActive = true AND LOWER(u.email) IN :emails")
    boolean existsActiveByEmailIn(@Param("emails") java.util.Collection<String> emails);

    Optional<User> findByPhone(String phone);
    Optional<User> findByUserIdAndIsActiveTrue(Integer userId);
    Optional<User> findBySystemId(String systemId);

    boolean existsBySystemId(String systemId);

    /**
     * Next value of one of the system id sequences created in V23 ({@code system_id_pat_seq}, {@code system_id_doc_seq},
     * {@code system_id_cgv_seq}, {@code system_id_adm_seq}). Callers pass a fixed sequence name, never user input
     * (see SystemIdGenerator).
     */
    @Query(value = "SELECT nextval(CAST(:sequenceName AS regclass))", nativeQuery = true)
    long nextSystemIdSequenceValue(@Param("sequenceName") String sequenceName);
}
