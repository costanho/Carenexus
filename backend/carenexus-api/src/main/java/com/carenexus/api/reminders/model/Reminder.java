package com.carenexus.api.reminders.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * A reminder ({@code reminders}, V1 / V54): a title and message sent as an in-app notification to
 * {@link #recipientSystemId} at {@link #reminderTime} (clinic wall-clock) on the days its {@link #frequency} gives,
 * from {@link #startDate} to {@link #endDate}.
 *
 * {@link #patientSystemId} is the patient the reminder is about; the recipient is that patient, or a doctor for
 * reminders created from a result that waits for review or the doctor's copy of a care-plan review. Manual reminders
 * ({@link #SOURCE_MANUAL}) are created over HTTP; the others by events, keyed by {@link #sourceType} and
 * {@link #sourceId}. {@link #nextDueAt} is non-null exactly when {@link #isActive} (V54 CHECK). The legacy integer
 * {@code patient_id} column is intentionally not mapped.
 */
@Entity
@Table(name = "reminders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Reminder {

    // ---- type (V1 CHECK) ----
    public static final String TYPE_MEDICATION = "MEDICATION";
    public static final String TYPE_APPOINTMENT = "APPOINTMENT";
    public static final String TYPE_LAB_FOLLOWUP = "LAB_FOLLOWUP";
    public static final String TYPE_CARE_PLAN = "CARE_PLAN";
    public static final String TYPE_CHECK_IN = "CHECK_IN";
    public static final String TYPE_VITAL_SIGN = "VITAL_SIGN";
    /** Every type the API accepts, in display order. */
    public static final List<String> TYPES = List.of(TYPE_MEDICATION, TYPE_APPOINTMENT, TYPE_LAB_FOLLOWUP,
            TYPE_CARE_PLAN, TYPE_CHECK_IN, TYPE_VITAL_SIGN);

    // ---- frequency (V1 CHECK; CUSTOM is allowed by the table but never accepted or written) ----
    public static final String FREQUENCY_ONCE = "ONCE";
    public static final String FREQUENCY_DAILY = "DAILY";
    public static final String FREQUENCY_WEEKLY = "WEEKLY";
    public static final String FREQUENCY_MONTHLY = "MONTHLY";
    /** Every frequency the API accepts. */
    public static final List<String> FREQUENCIES = List.of(FREQUENCY_ONCE, FREQUENCY_DAILY, FREQUENCY_WEEKLY,
            FREQUENCY_MONTHLY);

    // ---- source_type (V54 CHECK) ----
    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_CONSULTATION = "CONSULTATION";
    public static final String SOURCE_LAB_RESULT = "LAB_RESULT";
    public static final String SOURCE_IMAGING_RESULT = "IMAGING_RESULT";
    public static final String SOURCE_CARE_PLAN = "CARE_PLAN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reminder_id")
    private Integer reminderId;

    @Column(name = "patient_system_id", nullable = false, length = 20)
    private String patientSystemId;

    @Column(name = "recipient_system_id", nullable = false, length = 20)
    private String recipientSystemId;

    @Column(name = "created_by_system_id", length = 20)
    private String createdBySystemId;

    @Column(name = "type", nullable = false, length = 20)
    private String type;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    /** Time of day, clinic wall-clock. */
    @Column(name = "reminder_time", nullable = false)
    private LocalTime reminderTime;

    @Column(name = "frequency", nullable = false, length = 20)
    private String frequency;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    /** Last day an occurrence may fall on (inclusive); null = no end; always null for ONCE. */
    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

    /** Clinic wall-clock time of the next occurrence; null when paused or ended. */
    @Column(name = "next_due_at")
    private LocalDateTime nextDueAt;

    /** Clinic wall-clock time the dispatcher last processed this reminder (even when the notification was suppressed). */
    @Column(name = "last_sent_at")
    private LocalDateTime lastSentAt;

    @Column(name = "source_type", nullable = false, length = 20)
    private String sourceType;

    @Column(name = "source_id")
    private Integer sourceId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public boolean active() {
        return Boolean.TRUE.equals(isActive);
    }

    public boolean isManual() {
        return SOURCE_MANUAL.equals(sourceType);
    }

    /** True when the reminder goes to the patient it is about (the only reminders with an HTTP surface). */
    public boolean addressedToPatient() {
        return patientSystemId != null && patientSystemId.equals(recipientSystemId);
    }
}
