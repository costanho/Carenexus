package com.carenexus.api.ai.alert.repository;

import com.carenexus.api.ai.alert.model.AiAlert;
import com.carenexus.api.core.repository.CareTeamRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Reads and writes of {@code ai_alerts}. Lists are filtered and ordered in the database ({@code created_at DESC,
 * alert_id DESC}); pass an unsorted {@link Pageable}. Reads of tables this module does not own
 * ({@code care_team}, {@code appointments}, {@code consultations}, {@code disease_detection}, {@code users},
 * {@code patients}, {@code doctors}) are native and read-only.
 */
public interface AiAlertRepository extends JpaRepository<AiAlert, Integer> {

    /**
     * Visibility of an alert to the doctor {@code :viewer} (null viewer = administrator, every alert): the alert is
     * addressed to them, or they are related to its patient by the rule of
     * {@code RelationshipService.isDoctorAssignedToPatient} (active care-team doctor role, a non-cancelled
     * appointment, or a consultation).
     */
    String VISIBLE_TO_VIEWER = """
            (CAST(:viewer AS VARCHAR) IS NULL
             OR a.doctor_system_id = CAST(:viewer AS VARCHAR)
             OR EXISTS (SELECT 1 FROM care_team ct
                         WHERE ct.patient_system_id = a.patient_system_id
                           AND ct.provider_system_id = CAST(:viewer AS VARCHAR)
                           AND ct.is_active = TRUE
                           AND ct.role IN """ + CareTeamRepository.DOCTOR_ROLES + """
                        )
             OR EXISTS (SELECT 1 FROM appointments ap
                         WHERE ap.patient_system_id = a.patient_system_id
                           AND ap.doctor_system_id = CAST(:viewer AS VARCHAR)
                           AND ap.status <> 'CANCELLED')
             OR EXISTS (SELECT 1 FROM consultations co
                         WHERE co.patient_system_id = a.patient_system_id
                           AND co.doctor_system_id = CAST(:viewer AS VARCHAR)))
            """;

    /** Optional inbox filters; a null parameter does not filter. */
    String INBOX_FILTERS = """
             AND (CAST(:acknowledged AS BOOLEAN) IS NULL OR a.is_acknowledged = CAST(:acknowledged AS BOOLEAN))
             AND (CAST(:priority AS VARCHAR) IS NULL OR a.priority = CAST(:priority AS VARCHAR))
             AND (CAST(:alertType AS VARCHAR) IS NULL OR a.alert_type = CAST(:alertType AS VARCHAR))
             AND (CAST(:patientSystemId AS VARCHAR) IS NULL OR a.patient_system_id = CAST(:patientSystemId AS VARCHAR))
            """;

    /**
     * Patient-facing filter: the non-diagnostic types, and alerts whose detection a doctor CONFIRMED. Applied only
     * when {@code :patientFacing} is true.
     */
    String PATIENT_FACING_FILTER = """
             AND (:patientFacing = FALSE
                  OR a.alert_type IN ('MISSED_MEDICATION', 'FOLLOW_UP_DUE')
                  OR (a.detection_id IS NOT NULL AND d.review_status = 'CONFIRMED'))
            """;

    /** The doctor inbox (or the administrator's view of every alert), newest first. */
    @Query(value = "SELECT a.* FROM ai_alerts a WHERE " + VISIBLE_TO_VIEWER + INBOX_FILTERS
            + " ORDER BY a.created_at DESC, a.alert_id DESC",
            countQuery = "SELECT COUNT(*) FROM ai_alerts a WHERE " + VISIBLE_TO_VIEWER + INBOX_FILTERS,
            nativeQuery = true)
    Page<AiAlert> findInbox(@Param("viewer") String viewer,
                            @Param("acknowledged") Boolean acknowledged,
                            @Param("priority") String priority,
                            @Param("alertType") String alertType,
                            @Param("patientSystemId") String patientSystemId,
                            Pageable pageable);

    /** Number of rows {@link #findInbox} would return with the same arguments. */
    @Query(value = "SELECT COUNT(*) FROM ai_alerts a WHERE " + VISIBLE_TO_VIEWER + INBOX_FILTERS, nativeQuery = true)
    long countInbox(@Param("viewer") String viewer,
                    @Param("acknowledged") Boolean acknowledged,
                    @Param("priority") String priority,
                    @Param("alertType") String alertType,
                    @Param("patientSystemId") String patientSystemId);

    /** One patient's alerts, newest first; with {@code patientFacing} only those a patient or caregiver may see. */
    @Query(value = "SELECT a.* FROM ai_alerts a LEFT JOIN disease_detection d ON d.detection_id = a.detection_id"
            + " WHERE a.patient_system_id = :patientSystemId" + PATIENT_FACING_FILTER
            + " ORDER BY a.created_at DESC, a.alert_id DESC",
            countQuery = "SELECT COUNT(*) FROM ai_alerts a LEFT JOIN disease_detection d ON d.detection_id = a.detection_id"
                    + " WHERE a.patient_system_id = :patientSystemId" + PATIENT_FACING_FILTER,
            nativeQuery = true)
    Page<AiAlert> findForPatient(@Param("patientSystemId") String patientSystemId,
                                 @Param("patientFacing") boolean patientFacing,
                                 Pageable pageable);

    /**
     * Stores an alert unless its dedupe key is already used. The repeat is skipped with ON CONFLICT DO NOTHING
     * (never a constraint violation, which would abort the caller's PostgreSQL transaction); a null key never
     * conflicts.
     *
     * @return the new alert id, or an empty list when the dedupe key already exists
     */
    @Query(value = """
            INSERT INTO ai_alerts (patient_system_id, doctor_system_id, detection_id, alert_type, title, message,
                                   priority, action_required, suggested_action, source_type, source_id,
                                   is_acknowledged, dedupe_key, created_at)
            VALUES (:patientSystemId, CAST(:doctorSystemId AS VARCHAR), CAST(:detectionId AS INTEGER), :alertType,
                    :title, :message, :priority, :actionRequired, CAST(:suggestedAction AS TEXT),
                    CAST(:sourceType AS VARCHAR), CAST(:sourceId AS INTEGER), FALSE, CAST(:dedupeKey AS VARCHAR),
                    :createdAt)
            ON CONFLICT (dedupe_key) DO NOTHING
            RETURNING alert_id
            """, nativeQuery = true)
    List<Integer> insertIfAbsent(@Param("patientSystemId") String patientSystemId,
                                 @Param("doctorSystemId") String doctorSystemId,
                                 @Param("detectionId") Integer detectionId,
                                 @Param("alertType") String alertType,
                                 @Param("title") String title,
                                 @Param("message") String message,
                                 @Param("priority") String priority,
                                 @Param("actionRequired") boolean actionRequired,
                                 @Param("suggestedAction") String suggestedAction,
                                 @Param("sourceType") String sourceType,
                                 @Param("sourceId") Integer sourceId,
                                 @Param("dedupeKey") String dedupeKey,
                                 @Param("createdAt") LocalDateTime createdAt);

    /**
     * Acknowledges an unacknowledged alert. A repeat changes nothing, so the first acknowledger and time are kept.
     *
     * @return 1 when this call acknowledged the alert, 0 when it was already acknowledged (or does not exist)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE ai_alerts
               SET is_acknowledged = TRUE, acknowledged_by_system_id = :acknowledgedBy, acknowledged_at = :now
             WHERE alert_id = :alertId AND is_acknowledged = FALSE
            """, nativeQuery = true)
    int acknowledge(@Param("alertId") Integer alertId,
                    @Param("acknowledgedBy") String acknowledgedBy,
                    @Param("now") LocalDateTime now);

    // ==================== read-only lookups of other modules' tables ====================

    /** Review status of the given detections (owned by the detection module). */
    @Query(value = "SELECT d.detection_id AS detectionId, d.review_status AS reviewStatus"
            + " FROM disease_detection d WHERE d.detection_id IN (:detectionIds)", nativeQuery = true)
    List<DetectionStatusRow> findDetectionReviewStatuses(@Param("detectionIds") Collection<Integer> detectionIds);

    /** First and last names of the given accounts. */
    @Query(value = "SELECT u.system_id AS systemId, u.first_name AS firstName, u.last_name AS lastName"
            + " FROM users u WHERE u.system_id IN (:systemIds)", nativeQuery = true)
    List<PersonNameRow> findNames(@Param("systemIds") Collection<String> systemIds);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM patients p WHERE p.system_id = :systemId)", nativeQuery = true)
    boolean patientExists(@Param("systemId") String systemId);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM doctors d WHERE d.system_id = :systemId)", nativeQuery = true)
    boolean doctorExists(@Param("systemId") String systemId);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM disease_detection d WHERE d.detection_id = :detectionId)",
            nativeQuery = true)
    boolean detectionExists(@Param("detectionId") Integer detectionId);

    interface DetectionStatusRow {

        Integer getDetectionId();

        String getReviewStatus();
    }

    interface PersonNameRow {

        String getSystemId();

        String getFirstName();

        String getLastName();
    }
}
