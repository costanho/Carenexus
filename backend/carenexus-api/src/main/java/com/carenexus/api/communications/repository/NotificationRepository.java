package com.carenexus.api.communications.repository;

import com.carenexus.api.communications.model.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;

/**
 * A notification is "visible" to its recipient unless it is a MESSAGE notification for a thread the recipient
 * can no longer access ({@code threadIds} = threads the caller may access now; pass a non-empty collection, e.g.
 * {@code [-1]}) or for a message before the recipient's current history boundary.
 */
@Repository
public interface NotificationRepository extends JpaRepository<Notification, Integer> {

    String VISIBLE = " n.systemId = :systemId AND (n.type <> 'MESSAGE' OR n.threadId IS NULL OR EXISTS ("
            + "SELECT tm.threadMemberId FROM ThreadMember tm WHERE tm.threadId = n.threadId AND tm.systemId = :systemId"
            + " AND tm.leftAt IS NULL AND tm.removedAt IS NULL AND tm.threadId IN :threadIds"
            + " AND (n.messageId IS NULL OR n.messageId > tm.historyStartMessageId))) ";

    Optional<Notification> findByNotificationIdAndSystemId(Integer notificationId, String systemId);

    boolean existsBySystemIdAndMessageId(String systemId, Integer messageId);

    @Query(value = "SELECT n FROM Notification n WHERE" + VISIBLE + "AND (:unreadOnly = false OR n.isRead = false)"
            + " ORDER BY n.createdAt DESC, n.notificationId DESC",
            countQuery = "SELECT COUNT(n) FROM Notification n WHERE" + VISIBLE + "AND (:unreadOnly = false OR n.isRead = false)")
    Page<Notification> findVisible(@Param("systemId") String systemId,
                                   @Param("threadIds") Collection<Integer> threadIds,
                                   @Param("unreadOnly") boolean unreadOnly,
                                   Pageable pageable);

    @Query("SELECT COUNT(n) FROM Notification n WHERE" + VISIBLE + "AND n.isRead = false")
    long countVisibleUnread(@Param("systemId") String systemId, @Param("threadIds") Collection<Integer> threadIds);

    @Query("SELECT COUNT(n) FROM Notification n WHERE" + VISIBLE + "AND n.isRead = false AND n.type = 'MESSAGE'")
    long countVisibleUnreadMessages(@Param("systemId") String systemId, @Param("threadIds") Collection<Integer> threadIds);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE Notification n SET n.isRead = true, n.readAt = :now WHERE n.systemId = :systemId AND n.threadId = :threadId"
            + " AND n.messageId <= :upToMessageId AND n.isRead = false")
    int markThreadReadUpTo(@Param("systemId") String systemId, @Param("threadId") Integer threadId,
                           @Param("upToMessageId") Integer upToMessageId, @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE Notification n SET n.isRead = true, n.readAt = :now WHERE n.systemId = :systemId AND n.threadId IN :threadIds"
            + " AND n.isRead = false")
    int markThreadsRead(@Param("systemId") String systemId, @Param("threadIds") Collection<Integer> threadIds,
                        @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE Notification n SET n.isRead = true, n.readAt = :now WHERE n.systemId = :systemId AND n.isRead = false")
    int markAllRead(@Param("systemId") String systemId, @Param("now") LocalDateTime now);
}
