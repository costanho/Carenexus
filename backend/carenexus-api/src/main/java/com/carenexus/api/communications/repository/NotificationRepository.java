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
import java.util.List;
import java.util.Optional;

/**
 * A notification is "visible" to its recipient when it has not expired ({@code expiresAt} null or after
 * {@code now}) and it is not a MESSAGE notification for a thread the recipient can no longer access
 * ({@code threadIds} = threads the caller may access now; pass a non-empty collection, e.g. {@code [-1]}) or for a
 * message before the recipient's current history boundary.
 */
@Repository
public interface NotificationRepository extends JpaRepository<Notification, Integer> {

    String VISIBLE = " n.systemId = :systemId AND (n.expiresAt IS NULL OR n.expiresAt > :now)"
            + " AND (n.type <> 'MESSAGE' OR n.threadId IS NULL OR EXISTS ("
            + "SELECT tm.threadMemberId FROM ThreadMember tm WHERE tm.threadId = n.threadId AND tm.systemId = :systemId"
            + " AND tm.leftAt IS NULL AND tm.removedAt IS NULL AND tm.threadId IN :threadIds"
            + " AND (n.messageId IS NULL OR n.messageId > tm.historyStartMessageId))) ";

    /** No type filter for {@link #findVisible(String, Collection, boolean, String, LocalDateTime, Pageable)}. */
    String ANY_TYPE = "";

    String LIST_FILTER = "AND (:unreadOnly = false OR n.isRead = false) AND (:type = '' OR n.type = :type)";

    Optional<Notification> findByNotificationIdAndSystemId(Integer notificationId, String systemId);

    boolean existsBySystemIdAndMessageId(String systemId, Integer messageId);

    /**
     * Visible notifications of one recipient, newest first (the order is part of the query; pass an unsorted
     * {@link Pageable}).
     *
     * @param type a notification type, or {@link #ANY_TYPE} for all types
     * @param now  clinic wall-clock time; rows that expired before it are hidden
     */
    @Query(value = "SELECT n FROM Notification n WHERE" + VISIBLE + LIST_FILTER
            + " ORDER BY n.createdAt DESC, n.notificationId DESC",
            countQuery = "SELECT COUNT(n) FROM Notification n WHERE" + VISIBLE + LIST_FILTER)
    Page<Notification> findVisible(@Param("systemId") String systemId,
                                   @Param("threadIds") Collection<Integer> threadIds,
                                   @Param("unreadOnly") boolean unreadOnly,
                                   @Param("type") String type,
                                   @Param("now") LocalDateTime now,
                                   Pageable pageable);

    /** Visible notifications of every type as of now. */
    default Page<Notification> findVisible(String systemId, Collection<Integer> threadIds, boolean unreadOnly,
                                           Pageable pageable) {
        return findVisible(systemId, threadIds, unreadOnly, ANY_TYPE, LocalDateTime.now(), pageable);
    }

    @Query("SELECT COUNT(n) FROM Notification n WHERE" + VISIBLE + "AND n.isRead = false")
    long countVisibleUnread(@Param("systemId") String systemId, @Param("threadIds") Collection<Integer> threadIds,
                            @Param("now") LocalDateTime now);

    default long countVisibleUnread(String systemId, Collection<Integer> threadIds) {
        return countVisibleUnread(systemId, threadIds, LocalDateTime.now());
    }

    @Query("SELECT COUNT(n) FROM Notification n WHERE" + VISIBLE + "AND n.isRead = false AND n.type = 'MESSAGE'")
    long countVisibleUnreadMessages(@Param("systemId") String systemId, @Param("threadIds") Collection<Integer> threadIds,
                                    @Param("now") LocalDateTime now);

    default long countVisibleUnreadMessages(String systemId, Collection<Integer> threadIds) {
        return countVisibleUnreadMessages(systemId, threadIds, LocalDateTime.now());
    }

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

    @Modifying(flushAutomatically = true)
    @Query("UPDATE Notification n SET n.isRead = true, n.readAt = :now WHERE n.systemId = :systemId"
            + " AND n.actorSystemId = :actorSystemId AND n.actionUrl = :actionUrl AND n.isRead = false")
    int markReadForActorAndAction(@Param("systemId") String systemId, @Param("actorSystemId") String actorSystemId,
                                  @Param("actionUrl") String actionUrl, @Param("now") LocalDateTime now);

    /**
     * Stores a publisher notification unless the recipient already has one with the same dedupe key. The duplicate
     * is skipped with ON CONFLICT DO NOTHING (never a constraint violation, which would abort the surrounding
     * PostgreSQL transaction). A null dedupe key never conflicts.
     *
     * @return the new notification id, or an empty list when the dedupe key was already used for this recipient
     */
    @Query(value = """
            INSERT INTO notifications (system_id, type, title, message, is_read, action_url, icon, priority,
                                       expires_at, created_at, patient_system_id, actor_system_id, category, target,
                                       resource_type, resource_id, dedupe_key)
            VALUES (:systemId, :type, :title, :message, FALSE, CAST(:actionUrl AS VARCHAR), CAST(:icon AS VARCHAR),
                    :priority, CAST(:expiresAt AS TIMESTAMP), :createdAt, CAST(:patientSystemId AS VARCHAR),
                    CAST(:actorSystemId AS VARCHAR), :category, CAST(:target AS VARCHAR),
                    CAST(:resourceType AS VARCHAR), CAST(:resourceId AS INTEGER), CAST(:dedupeKey AS VARCHAR))
            ON CONFLICT (system_id, dedupe_key) WHERE dedupe_key IS NOT NULL DO NOTHING
            RETURNING notification_id
            """, nativeQuery = true)
    List<Integer> insertIfAbsent(@Param("systemId") String systemId,
                                 @Param("type") String type,
                                 @Param("title") String title,
                                 @Param("message") String message,
                                 @Param("actionUrl") String actionUrl,
                                 @Param("icon") String icon,
                                 @Param("priority") String priority,
                                 @Param("expiresAt") LocalDateTime expiresAt,
                                 @Param("createdAt") LocalDateTime createdAt,
                                 @Param("patientSystemId") String patientSystemId,
                                 @Param("actorSystemId") String actorSystemId,
                                 @Param("category") String category,
                                 @Param("target") String target,
                                 @Param("resourceType") String resourceType,
                                 @Param("resourceId") Integer resourceId,
                                 @Param("dedupeKey") String dedupeKey);
}
