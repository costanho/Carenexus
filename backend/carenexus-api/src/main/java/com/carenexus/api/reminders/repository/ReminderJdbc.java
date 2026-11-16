package com.carenexus.api.reminders.repository;

import com.carenexus.api.reminders.model.Reminder;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Native SQL for the reminders module: the per-patient create lock, the event-driven upserts (on the V54 partial
 * unique index {@code ux_reminders_source}, so a repeated event never aborts the publisher's transaction), and the
 * recipients' time-zone setting for the reminder text.
 *
 * Every insert is guarded on the patient and the recipient having an account, so an event that names an unknown
 * user is skipped instead of failing on a foreign key.
 */
@Repository
@RequiredArgsConstructor
public class ReminderJdbc {

    /** ON CONFLICT target: the V54 partial unique index. */
    private static final String SOURCE_CONFLICT =
            "ON CONFLICT (source_type, source_id, type, recipient_system_id, reminder_time) "
                    + "WHERE source_type <> 'MANUAL' ";

    private static final String INSERT_AUTOMATIC =
            // created_by_system_id and last_sent_at are left to their NULL default
            "INSERT INTO reminders (patient_system_id, recipient_system_id, type, title, message, "
                    + "reminder_time, frequency, start_date, end_date, is_active, next_due_at, "
                    + "source_type, source_id, created_at) "
                    + "SELECT :patient, :recipient, :type, :title, :message, :time, :frequency, :startDate, "
                    + ":endDate, :active, :nextDueAt, :sourceType, :sourceId, :now "
                    + "WHERE EXISTS (SELECT 1 FROM users WHERE system_id = :patient) "
                    + "AND EXISTS (SELECT 1 FROM users WHERE system_id = :recipient) ";

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Serialises the creates of one patient's manual reminders until the end of the current transaction, so the
     * per-patient cap cannot be passed by concurrent requests.
     */
    public void lockPatientReminders(String patientSystemId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(:key))",
                new MapSqlParameterSource("key", "reminders:" + patientSystemId), rs -> null);
    }

    /** One automatic reminder row to write. */
    public record AutomaticReminder(String patientSystemId, String recipientSystemId, String type, String title,
                                    String message, LocalTime reminderTime, String frequency, LocalDate startDate,
                                    LocalDate endDate, LocalDateTime nextDueAt, String sourceType, Integer sourceId) {

        boolean active() {
            return nextDueAt != null;
        }
    }

    /**
     * Inserts the reminder unless the same source, type, recipient and time already has one (that row is left as
     * it is).
     *
     * @return 1 when a row was inserted, 0 otherwise
     */
    public int insertIfAbsent(AutomaticReminder reminder, LocalDateTime now) {
        return jdbc.update(INSERT_AUTOMATIC + SOURCE_CONFLICT + "DO NOTHING", params(reminder, now));
    }

    /**
     * Inserts the reminder, or, when the same source, type, recipient and time already has one with ANOTHER start
     * date (or another text, which for automatic reminders only changes with the date they name), moves that row:
     * start date, end date, message, active flag and next due time are replaced (the title, creation time and last
     * sent time are kept). A row with the same start date and text is left untouched, so a pause or a sent state
     * survives repeated events.
     *
     * @return 1 when a row was inserted or moved, 0 when it was left untouched or skipped
     */
    public int upsertMovingStartDate(AutomaticReminder reminder, LocalDateTime now) {
        return jdbc.update(INSERT_AUTOMATIC + SOURCE_CONFLICT
                + "DO UPDATE SET start_date = EXCLUDED.start_date, end_date = EXCLUDED.end_date, "
                + "message = EXCLUDED.message, is_active = EXCLUDED.is_active, next_due_at = EXCLUDED.next_due_at "
                + "WHERE reminders.start_date IS DISTINCT FROM EXCLUDED.start_date "
                + "OR reminders.message IS DISTINCT FROM EXCLUDED.message", params(reminder, now));
    }

    /** Deletes every automatic reminder of one source (all recipients). */
    public int deleteBySource(String sourceType, Integer sourceId) {
        return jdbc.update("DELETE FROM reminders WHERE source_type = :sourceType AND source_id = :sourceId",
                new MapSqlParameterSource().addValue("sourceType", sourceType).addValue("sourceId", sourceId));
    }

    /** Deletes the automatic reminders of one source whose recipient is not in {@code keepRecipients}. */
    public int deleteBySourceExceptRecipients(String sourceType, Integer sourceId, Collection<String> keepRecipients) {
        return jdbc.update("DELETE FROM reminders WHERE source_type = :sourceType AND source_id = :sourceId "
                        + "AND recipient_system_id NOT IN (:keep)",
                new MapSqlParameterSource().addValue("sourceType", sourceType).addValue("sourceId", sourceId)
                        .addValue("keep", keepRecipients));
    }

    /**
     * The stored {@code user_settings.timezone} of each recipient that has a non-blank one (validity is checked by
     * the caller). Recipients without a settings row or with no zone are absent from the map.
     */
    public Map<String, String> timezones(Collection<String> systemIds) {
        Map<String, String> zones = new HashMap<>();
        if (systemIds == null || systemIds.isEmpty()) {
            return zones;
        }
        jdbc.query("SELECT system_id, timezone FROM user_settings WHERE system_id IN (:ids) "
                        + "AND timezone IS NOT NULL AND btrim(timezone) <> ''",
                new MapSqlParameterSource("ids", List.copyOf(systemIds)),
                rs -> {
                    zones.put(rs.getString("system_id"), rs.getString("timezone").trim());
                });
        return zones;
    }

    private static MapSqlParameterSource params(AutomaticReminder reminder, LocalDateTime now) {
        return new MapSqlParameterSource()
                .addValue("patient", reminder.patientSystemId())
                .addValue("recipient", reminder.recipientSystemId())
                .addValue("type", reminder.type())
                .addValue("title", reminder.title())
                .addValue("message", reminder.message())
                .addValue("time", Time.valueOf(reminder.reminderTime()))
                .addValue("frequency", reminder.frequency())
                .addValue("startDate", Date.valueOf(reminder.startDate()))
                .addValue("endDate", reminder.endDate() == null ? null : Date.valueOf(reminder.endDate()),
                        java.sql.Types.DATE)
                .addValue("active", reminder.active())
                .addValue("nextDueAt", reminder.nextDueAt() == null ? null : Timestamp.valueOf(reminder.nextDueAt()),
                        java.sql.Types.TIMESTAMP)
                .addValue("sourceType", reminder.sourceType())
                .addValue("sourceId", reminder.sourceId())
                .addValue("now", Timestamp.valueOf(now));
    }

    /** Source types created by events (never {@link Reminder#SOURCE_MANUAL}). */
    public static boolean isAutomaticSource(String sourceType) {
        return sourceType != null && !Reminder.SOURCE_MANUAL.equals(sourceType);
    }
}
