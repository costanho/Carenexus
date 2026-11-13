package com.carenexus.api.communications.notify;

import com.carenexus.api.auth.model.User;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Read-only lookup of notification recipients: does the account exist, and which role decides its dashboard link.
 */
public interface NotificationRecipientRepository extends Repository<User, Integer> {

    /** One row per existing account among {@code systemIds}; inactive accounts are included. */
    @Query("SELECT u.systemId AS systemId, u.role AS role FROM User u WHERE u.systemId IN :systemIds")
    List<Recipient> findRecipients(@Param("systemIds") Collection<String> systemIds);

    interface Recipient {

        String getSystemId();

        String getRole();
    }
}
