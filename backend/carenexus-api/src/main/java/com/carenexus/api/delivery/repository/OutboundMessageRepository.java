package com.carenexus.api.delivery.repository;

import com.carenexus.api.delivery.model.OutboundMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface OutboundMessageRepository extends JpaRepository<OutboundMessage, Integer>,
        JpaSpecificationExecutor<OutboundMessage> {

    /** Ids of due rows, oldest due first. Not locked: each row is claimed on its own with {@link #lockDue}. */
    @Query(value = "SELECT message_id FROM outbound_messages WHERE status = 'PENDING' AND next_attempt_at <= :now "
            + "ORDER BY next_attempt_at, message_id LIMIT :limit", nativeQuery = true)
    List<Integer> findDueIds(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * Claims one due row for the current transaction. Empty when the row is no longer due (another worker sent it)
     * or another transaction holds it (FOR UPDATE SKIP LOCKED). Call inside a transaction.
     */
    @Query(value = "SELECT * FROM outbound_messages WHERE message_id = :id AND status = 'PENDING' "
            + "AND next_attempt_at <= :now FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<OutboundMessage> lockDue(@Param("id") Integer id, @Param("now") Instant now);

    Optional<OutboundMessage> findByDedupeKey(String dedupeKey);
}
