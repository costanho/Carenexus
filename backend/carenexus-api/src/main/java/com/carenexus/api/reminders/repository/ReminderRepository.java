package com.carenexus.api.reminders.repository;

import com.carenexus.api.reminders.model.Reminder;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Reminders for the HTTP surface. Event-driven upserts and the dispatcher's claim use {@link ReminderJdbc}.
 */
public interface ReminderRepository extends JpaRepository<Reminder, Integer> {

    /** The reminder with a row lock, for PATCH and DELETE. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reminder r WHERE r.reminderId = :reminderId")
    Optional<Reminder> findByIdForUpdate(@Param("reminderId") Integer reminderId);

    /**
     * Reminders whose patient AND recipient are {@code patientSystemId}, optionally only active ({@code true}) or
     * only paused and ended ({@code false}). Fixed order: active first, soonest next due time (nulls last), newest
     * id; pass an unsorted {@link Pageable}.
     */
    @Query(value = "SELECT r FROM Reminder r WHERE r.patientSystemId = :patientSystemId "
            + "AND r.recipientSystemId = :patientSystemId "
            + "AND (:active IS NULL OR r.isActive = :active) "
            + "ORDER BY r.isActive DESC, r.nextDueAt ASC NULLS LAST, r.reminderId DESC",
            countQuery = "SELECT COUNT(r) FROM Reminder r WHERE r.patientSystemId = :patientSystemId "
                    + "AND r.recipientSystemId = :patientSystemId "
                    + "AND (:active IS NULL OR r.isActive = :active)")
    Page<Reminder> findForPatient(@Param("patientSystemId") String patientSystemId,
                                  @Param("active") Boolean active,
                                  Pageable pageable);

    /**
     * Claims up to {@code limit} active reminders whose next due time has come, soonest first, skipping rows another
     * transaction holds. The returned entities are managed: changes are written when the claiming transaction ends.
     */
    @Query(value = "SELECT * FROM reminders WHERE is_active = TRUE AND next_due_at <= :now "
            + "ORDER BY next_due_at, reminder_id LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<Reminder> claimDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /** Active manual reminders of a patient (the per-patient cap on create). */
    @Query("SELECT COUNT(r) FROM Reminder r WHERE r.patientSystemId = :patientSystemId "
            + "AND r.recipientSystemId = :patientSystemId "
            + "AND r.sourceType = '" + Reminder.SOURCE_MANUAL + "' AND r.isActive = TRUE")
    long countActiveManual(@Param("patientSystemId") String patientSystemId);
}
