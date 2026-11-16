package com.carenexus.api.delivery.fanout;

import com.carenexus.api.auth.model.User;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Read-only queries of the notification fan-out. They run in the publisher's transaction and never write: the
 * {@code user_settings} entity and its repository belong to the profile-settings module, so the fan-out reads the
 * columns it needs with its own native queries.
 */
public interface NotificationFanOutRepository extends Repository<User, Integer> {

    /** The stored notification, only when it belongs to {@code recipientSystemId}. */
    @Query(value = """
            SELECT n.notification_id AS "notificationId",
                   n.type            AS "type",
                   n.title           AS "title",
                   n.message         AS "message",
                   n.action_url      AS "actionUrl",
                   n.priority        AS "priority"
              FROM notifications n
             WHERE n.notification_id = :notificationId
               AND n.system_id = :recipientSystemId
            """, nativeQuery = true)
    Optional<StoredNotification> findNotification(@Param("notificationId") Integer notificationId,
                                                  @Param("recipientSystemId") String recipientSystemId);

    /**
     * The recipient's addresses and delivery settings. The settings columns are null when the user has no
     * {@code user_settings} row (the caller applies the column defaults). Times are returned as text
     * ({@code HH:mm:ss}) so no driver-specific time type is involved.
     */
    @Query(value = """
            SELECT u.system_id                         AS "systemId",
                   u.email                             AS "email",
                   u.phone                             AS "phone",
                   s.email_notifications               AS "emailNotifications",
                   s.sms_notifications                 AS "smsNotifications",
                   s.push_notifications                AS "pushNotifications",
                   s.quiet_hours_enabled               AS "quietHoursEnabled",
                   CAST(s.quiet_hours_start AS VARCHAR) AS "quietHoursStart",
                   CAST(s.quiet_hours_end AS VARCHAR)   AS "quietHoursEnd",
                   s.timezone                          AS "timezone",
                   EXISTS (SELECT 1 FROM push_devices d WHERE d.system_id = u.system_id) AS "hasPushDevice"
              FROM users u
              LEFT JOIN user_settings s ON s.system_id = u.system_id
             WHERE u.system_id = :systemId
            """, nativeQuery = true)
    Optional<Recipient> findRecipient(@Param("systemId") String systemId);

    interface StoredNotification {

        Integer getNotificationId();

        String getType();

        String getTitle();

        String getMessage();

        String getActionUrl();

        String getPriority();
    }

    interface Recipient {

        String getSystemId();

        String getEmail();

        String getPhone();

        Boolean getEmailNotifications();

        Boolean getSmsNotifications();

        Boolean getPushNotifications();

        Boolean getQuietHoursEnabled();

        String getQuietHoursStart();

        String getQuietHoursEnd();

        String getTimezone();

        Boolean getHasPushDevice();
    }
}
