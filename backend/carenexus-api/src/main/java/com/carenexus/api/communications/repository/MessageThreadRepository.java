package com.carenexus.api.communications.repository;

import com.carenexus.api.communications.model.MessageThread;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface MessageThreadRepository extends JpaRepository<MessageThread, Integer> {

    String ACTIVE_MEMBER = " tm.leftAt IS NULL AND tm.removedAt IS NULL ";

    Optional<MessageThread> findByCreatedBySystemIdAndClientThreadId(String createdBySystemId, String clientThreadId);

    /**
     * Thread row lock for sends: serializes inserts per thread so message ids commit in id order (the client's
     * {@code after} cursor relies on that) and last_message_id only moves forward.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM MessageThread t WHERE t.threadId = :threadId")
    Optional<MessageThread> findForUpdateById(@Param("threadId") Integer threadId);

    /**
     * Threads where {@code systemId} is an active member, limited to the given patients, filtered by the member's
     * own archive flag. Order: last_message_at DESC NULLS LAST, thread_id DESC.
     */
    @Query(value = "SELECT t FROM MessageThread t, ThreadMember tm WHERE tm.threadId = t.threadId AND tm.systemId = :systemId AND"
            + ACTIVE_MEMBER + "AND t.patientSystemId IN :patientSystemIds"
            + " AND ((:archived = true AND tm.archivedAt IS NOT NULL) OR (:archived = false AND tm.archivedAt IS NULL))"
            + " ORDER BY t.lastMessageAt DESC NULLS LAST, t.threadId DESC",
            countQuery = "SELECT COUNT(t) FROM MessageThread t, ThreadMember tm WHERE tm.threadId = t.threadId AND tm.systemId = :systemId AND"
            + ACTIVE_MEMBER + "AND t.patientSystemId IN :patientSystemIds"
            + " AND ((:archived = true AND tm.archivedAt IS NOT NULL) OR (:archived = false AND tm.archivedAt IS NULL))")
    Page<MessageThread> findForMember(@Param("systemId") String systemId,
                                      @Param("patientSystemIds") Collection<String> patientSystemIds,
                                      @Param("archived") boolean archived,
                                      Pageable pageable);

    /**
     * PRIVATE threads of the patient whose ACTIVE members are exactly {@code a} and {@code b}.
     */
    @Query("SELECT t FROM MessageThread t WHERE t.patientSystemId = :patientSystemId AND t.conversationType = 'PRIVATE'"
            + " AND EXISTS (SELECT tm.threadMemberId FROM ThreadMember tm WHERE tm.threadId = t.threadId AND tm.systemId = :a AND" + ACTIVE_MEMBER + ")"
            + " AND EXISTS (SELECT tm.threadMemberId FROM ThreadMember tm WHERE tm.threadId = t.threadId AND tm.systemId = :b AND" + ACTIVE_MEMBER + ")"
            + " AND (SELECT COUNT(tm) FROM ThreadMember tm WHERE tm.threadId = t.threadId AND" + ACTIVE_MEMBER + ") = 2"
            + " ORDER BY t.threadId ASC")
    List<MessageThread> findPrivateThreadsBetween(@Param("patientSystemId") String patientSystemId,
                                                  @Param("a") String a,
                                                  @Param("b") String b);
}
