package com.carenexus.api.communications.listener.clinical;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Reads the stored {@code notifications} rows (and the fan-out's {@code outbound_messages}) for the clinical
 * notification tests. Shared by the listener tests and NotificationFlowReleaseGateTest.
 */
public final class StoredNotifications {

    /** One stored notification, with the columns the clinical notification contract pins. */
    public record Row(Integer notificationId, String recipient, String type, String title, String message,
                      String actionUrl, String priority, String category, String target, String resourceType,
                      Integer resourceId, String patientSystemId, String actorSystemId, String dedupeKey) {
    }

    private static final String SELECT = """
            SELECT notification_id, system_id, type, title, message, action_url, priority, category, target,
                   resource_type, resource_id, patient_system_id, actor_system_id, dedupe_key
            FROM notifications
            """;

    private final JdbcTemplate jdbc;

    public StoredNotifications(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every stored notification, oldest first. */
    public List<Row> all() {
        return jdbc.query(SELECT + " ORDER BY notification_id", (rs, n) -> new Row(
                rs.getInt("notification_id"),
                rs.getString("system_id"),
                rs.getString("type"),
                rs.getString("title"),
                rs.getString("message"),
                rs.getString("action_url"),
                rs.getString("priority"),
                rs.getString("category"),
                rs.getString("target"),
                rs.getString("resource_type"),
                (Integer) rs.getObject("resource_id"),
                rs.getString("patient_system_id"),
                rs.getString("actor_system_id"),
                rs.getString("dedupe_key")));
    }

    /** The notifications of one recipient, oldest first. */
    public List<Row> of(String recipientSystemId) {
        return all().stream().filter(row -> recipientSystemId.equals(row.recipient())).toList();
    }

    /** The notifications of one recipient with this type, oldest first. */
    public List<Row> of(String recipientSystemId, String type) {
        return of(recipientSystemId).stream().filter(row -> type.equals(row.type())).toList();
    }

    /** The notifications of one recipient with this title, oldest first. */
    public List<Row> titled(String recipientSystemId, String title) {
        return of(recipientSystemId).stream().filter(row -> title.equals(row.title())).toList();
    }

    public int count() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class);
        return count == null ? 0 : count;
    }

    /** Channels of the outbound messages queued for one notification, in the order they were queued. */
    public List<String> channels(Integer notificationId) {
        return jdbc.queryForList("SELECT channel FROM outbound_messages WHERE notification_id = ? ORDER BY message_id",
                String.class, notificationId);
    }

    /** Inserts or replaces a user_settings row with one boolean toggle column set. */
    public void setting(String systemId, String column, boolean value) {
        if (!column.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("Unexpected column " + column);
        }
        int updated = jdbc.update("UPDATE user_settings SET " + column + " = ? WHERE system_id = ?", value, systemId);
        if (updated == 0) {
            jdbc.update("INSERT INTO user_settings (system_id, " + column + ") VALUES (?, ?)", systemId, value);
        }
    }
}
