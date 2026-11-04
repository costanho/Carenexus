package com.carenexus.api.communications.repository;

import com.carenexus.api.communications.model.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * "Visible" = not deleted and {@code messageId > boundary} (the member's history_start_message_id).
 */
@Repository
public interface MessageRepository extends JpaRepository<Message, Integer> {

    Optional<Message> findByThreadIdAndSenderSystemIdAndClientMessageId(Integer threadId, String senderSystemId, String clientMessageId);

    /** Newest visible messages first (no cursor). */
    @Query("SELECT m FROM Message m WHERE m.threadId = :threadId AND m.messageId > :boundary AND m.isDeleted = false"
            + " ORDER BY m.messageId DESC")
    List<Message> findVisibleNewest(@Param("threadId") Integer threadId, @Param("boundary") int boundary, Pageable pageable);

    /** Visible messages older than {@code before}, newest first. */
    @Query("SELECT m FROM Message m WHERE m.threadId = :threadId AND m.messageId > :boundary AND m.messageId < :before"
            + " AND m.isDeleted = false ORDER BY m.messageId DESC")
    List<Message> findVisibleBefore(@Param("threadId") Integer threadId, @Param("boundary") int boundary,
                                    @Param("before") int before, Pageable pageable);

    /** Visible messages newer than {@code from} (already max(after, boundary)), oldest first. */
    @Query("SELECT m FROM Message m WHERE m.threadId = :threadId AND m.messageId > :from AND m.isDeleted = false"
            + " ORDER BY m.messageId ASC")
    List<Message> findVisibleAfter(@Param("threadId") Integer threadId, @Param("from") int from, Pageable pageable);

    @Query("SELECT COALESCE(MAX(m.messageId), 0) FROM Message m WHERE m.threadId = :threadId")
    Integer findMaxMessageId(@Param("threadId") Integer threadId);

    /** Unread for a member: visible, newer than max(boundary, last read) = {@code from}, not sent by the member. */
    @Query("SELECT COUNT(m) FROM Message m WHERE m.threadId = :threadId AND m.messageId > :from AND m.isDeleted = false"
            + " AND m.senderSystemId <> :systemId")
    long countUnread(@Param("threadId") Integer threadId, @Param("from") int from, @Param("systemId") String systemId);

    /**
     * [threadId, unreadCount, urgentUnreadCount] per thread for the member {@code systemId}, honouring that
     * member's history boundary and read position. Threads with no unread messages are absent.
     */
    @Query("SELECT m.threadId, COUNT(m), SUM(CASE WHEN m.isUrgent = true THEN 1 ELSE 0 END)"
            + " FROM Message m, ThreadMember tm WHERE tm.threadId = m.threadId AND tm.systemId = :systemId"
            + " AND m.threadId IN :threadIds AND m.isDeleted = false AND m.senderSystemId <> :systemId"
            + " AND m.messageId > tm.historyStartMessageId AND m.messageId > tm.lastReadMessageId"
            + " GROUP BY m.threadId")
    List<Object[]> findUnreadStats(@Param("systemId") String systemId, @Param("threadIds") Collection<Integer> threadIds);

    /** Newest visible message per thread for the member {@code systemId} (respects the history boundary). */
    @Query("SELECT m FROM Message m, ThreadMember tm WHERE tm.threadId = m.threadId AND tm.systemId = :systemId"
            + " AND m.threadId IN :threadIds AND m.isDeleted = false AND m.messageId > tm.historyStartMessageId"
            + " AND m.messageId = (SELECT MAX(m2.messageId) FROM Message m2 WHERE m2.threadId = m.threadId"
            + "   AND m2.isDeleted = false AND m2.messageId > tm.historyStartMessageId)")
    List<Message> findLastVisible(@Param("systemId") String systemId, @Param("threadIds") Collection<Integer> threadIds);
}
