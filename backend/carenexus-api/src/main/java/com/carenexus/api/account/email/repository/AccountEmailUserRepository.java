package com.carenexus.api.account.email.repository;

import com.carenexus.api.auth.model.User;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Reads of {@code users} that the email verification and email change flows need beyond {@code UserRepository}.
 */
public interface AccountEmailUserRepository extends Repository<User, Integer> {

    /** True when an account other than {@code systemId} uses {@code email} (pass it lower case). */
    @Query("SELECT COUNT(u) > 0 FROM User u WHERE LOWER(u.email) = :email AND u.systemId <> :systemId")
    boolean emailUsedByAnotherAccount(@Param("email") String email, @Param("systemId") String systemId);
}
