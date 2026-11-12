package com.carenexus.api.communications.repository;

import com.carenexus.api.communications.model.MessageThread;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
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

    /** Placeholder for an empty {@code IN} list (no patient id has this value). */
    String NO_PATIENT = "-";

    /**
     * The caller's conversation list (4.2): PRIVATE and CARE_TEAM threads where {@code systemId} is an active member
     * and the thread's patient is one of {@code patientSystemIds}, plus, when {@code includeSupport} is true, the
     * SUPPORT threads the caller opened (open and closed). Filtered by the member's own archive flag.
     * Order: last_message_at DESC NULLS LAST, thread_id DESC. Pass {@link #NO_PATIENT} for an empty patient list.
     */
    @Query(value = "SELECT t FROM MessageThread t, ThreadMember tm WHERE tm.threadId = t.threadId AND tm.systemId = :systemId AND"
            + ACTIVE_MEMBER + "AND ((t.conversationType <> 'SUPPORT' AND t.patientSystemId IN :patientSystemIds)"
            + " OR (:includeSupport = true AND t.conversationType = 'SUPPORT' AND t.createdBySystemId = :systemId))"
            + " AND ((:archived = true AND tm.archivedAt IS NOT NULL) OR (:archived = false AND tm.archivedAt IS NULL))"
            + " ORDER BY t.lastMessageAt DESC NULLS LAST, t.threadId DESC",
            countQuery = "SELECT COUNT(t) FROM MessageThread t, ThreadMember tm WHERE tm.threadId = t.threadId AND tm.systemId = :systemId AND"
            + ACTIVE_MEMBER + "AND ((t.conversationType <> 'SUPPORT' AND t.patientSystemId IN :patientSystemIds)"
            + " OR (:includeSupport = true AND t.conversationType = 'SUPPORT' AND t.createdBySystemId = :systemId))"
            + " AND ((:archived = true AND tm.archivedAt IS NOT NULL) OR (:archived = false AND tm.archivedAt IS NULL))")
    Page<MessageThread> findForMemberWithSupport(@Param("systemId") String systemId,
                                                 @Param("patientSystemIds") Collection<String> patientSystemIds,
                                                 @Param("includeSupport") boolean includeSupport,
                                                 @Param("archived") boolean archived,
                                                 Pageable pageable);

    // ==================== SUPPORT THREADS ====================

    /** The user's open SUPPORT thread (at most one, ux_message_threads_open_support). */
    @Query("SELECT t FROM MessageThread t WHERE t.conversationType = 'SUPPORT' AND t.createdBySystemId = :systemId"
            + " AND t.closedAt IS NULL")
    Optional<MessageThread> findOpenSupportThread(@Param("systemId") String systemId);

    /**
     * Administrator inbox (5.2): SUPPORT threads with at least one message, newest activity first.
     *
     * @param openFilter 0 = open and closed, 1 = only open, 2 = only closed
     */
    @Query(value = "SELECT t FROM MessageThread t WHERE t.conversationType = 'SUPPORT' AND t.lastMessageId IS NOT NULL"
            + " AND (:openFilter = 0 OR (:openFilter = 1 AND t.closedAt IS NULL) OR (:openFilter = 2 AND t.closedAt IS NOT NULL))"
            + " ORDER BY t.lastMessageAt DESC NULLS LAST, t.threadId DESC",
            countQuery = "SELECT COUNT(t) FROM MessageThread t WHERE t.conversationType = 'SUPPORT' AND t.lastMessageId IS NOT NULL"
            + " AND (:openFilter = 0 OR (:openFilter = 1 AND t.closedAt IS NULL) OR (:openFilter = 2 AND t.closedAt IS NOT NULL))")
    Page<MessageThread> findSupportInbox(@Param("openFilter") int openFilter, Pageable pageable);

    /**
     * Of the given SUPPORT threads, those with a message not sent by {@code adminSystemId} that is newer than that
     * administrator's read position (0 when the administrator is not a member). One query for a whole page.
     */
    @Query(value = "SELECT t.thread_id FROM message_threads t WHERE t.thread_id IN (:threadIds) AND EXISTS ("
            + " SELECT 1 FROM messages m WHERE m.thread_id = t.thread_id AND m.is_deleted = false"
            + " AND m.sender_system_id <> :adminSystemId"
            + " AND m.message_id > COALESCE((SELECT tm.last_read_message_id FROM thread_members tm"
            + "   WHERE tm.thread_id = t.thread_id AND tm.system_id = :adminSystemId), 0))",
            nativeQuery = true)
    List<Integer> findUnreadSupportThreadIds(@Param("adminSystemId") String adminSystemId,
                                             @Param("threadIds") Collection<Integer> threadIds);

    /**
     * Marks the administrator's unread SUPPORT_THREAD notifications of this thread read (they carry no thread id,
     * only resource_type / resource_id). Used by POST /threads/{id}/read for an administrator.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE notifications SET is_read = true, read_at = :now WHERE system_id = :systemId"
            + " AND resource_type = 'SUPPORT_THREAD' AND resource_id = :threadId AND is_read = false",
            nativeQuery = true)
    int markSupportNotificationsRead(@Param("systemId") String systemId, @Param("threadId") Integer threadId,
                                     @Param("now") LocalDateTime now);
}
