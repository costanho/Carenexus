package com.carenexus.api.ai.detection.repository;

import com.carenexus.api.ai.detection.model.DiseaseDetection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes of {@code disease_detection}. The per-patient list is filtered and ordered in the database
 * ({@code created_at DESC, detection_id DESC}); pass an unsorted {@link Pageable}.
 */
public interface DiseaseDetectionRepository extends JpaRepository<DiseaseDetection, Integer> {

    /** Optional review-status filter; a null parameter does not filter. */
    String STATUS_FILTER =
            " AND (CAST(:reviewStatus AS VARCHAR) IS NULL OR d.review_status = CAST(:reviewStatus AS VARCHAR))";

    /** One patient's detections, newest first, optionally only those with {@code reviewStatus}. */
    @Query(value = "SELECT d.* FROM disease_detection d WHERE d.patient_system_id = :patientSystemId" + STATUS_FILTER
            + " ORDER BY d.created_at DESC, d.detection_id DESC",
            countQuery = "SELECT COUNT(*) FROM disease_detection d WHERE d.patient_system_id = :patientSystemId"
                    + STATUS_FILTER,
            nativeQuery = true)
    Page<DiseaseDetection> findForPatient(@Param("patientSystemId") String patientSystemId,
                                          @Param("reviewStatus") String reviewStatus,
                                          Pageable pageable);

    /** The detection stored under a dedupe key (one per patient, primary condition and clinic day). */
    Optional<DiseaseDetection> findByDedupeKey(String dedupeKey);

    /**
     * Stores a PENDING_REVIEW detection unless its dedupe key is already used. The repeat is skipped with
     * ON CONFLICT DO NOTHING (never a constraint violation, which would abort the caller's PostgreSQL transaction).
     *
     * @return the new detection id, or an empty list when the dedupe key already exists
     */
    @Query(value = """
            INSERT INTO disease_detection (patient_system_id, symptom_id, doctor_system_id, model_version,
                                           detected_conditions, confidence_score, risk_level, input_data,
                                           recommendations, is_confirmed, created_at, review_status, source_type,
                                           source_id, dedupe_key)
            VALUES (:patientSystemId, CAST(:symptomId AS INTEGER), CAST(:doctorSystemId AS VARCHAR), :modelVersion,
                    CAST(:detectedConditions AS JSONB), CAST(:confidenceScore AS NUMERIC), :riskLevel,
                    CAST(:inputData AS JSONB), CAST(:recommendations AS JSONB), FALSE, :createdAt, 'PENDING_REVIEW',
                    CAST(:sourceType AS VARCHAR), CAST(:sourceId AS INTEGER), :dedupeKey)
            ON CONFLICT (dedupe_key) DO NOTHING
            RETURNING detection_id
            """, nativeQuery = true)
    List<Integer> insertIfAbsent(@Param("patientSystemId") String patientSystemId,
                                 @Param("symptomId") Integer symptomId,
                                 @Param("doctorSystemId") String doctorSystemId,
                                 @Param("modelVersion") String modelVersion,
                                 @Param("detectedConditions") String detectedConditions,
                                 @Param("confidenceScore") BigDecimal confidenceScore,
                                 @Param("riskLevel") String riskLevel,
                                 @Param("inputData") String inputData,
                                 @Param("recommendations") String recommendations,
                                 @Param("createdAt") LocalDateTime createdAt,
                                 @Param("sourceType") String sourceType,
                                 @Param("sourceId") Integer sourceId,
                                 @Param("dedupeKey") String dedupeKey);

    /**
     * Records a doctor's review of a PENDING_REVIEW detection. Conditional on the pending state, so two concurrent
     * reviews cannot both apply. A confirmation also stamps the legacy {@code is_confirmed} / {@code confirmed_at}.
     *
     * @return 1 when this call reviewed the detection, 0 when it was already reviewed (or does not exist)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE disease_detection
               SET review_status = :reviewStatus,
                   review_notes = CAST(:reviewNotes AS TEXT),
                   reviewed_by_system_id = :reviewedBy,
                   reviewed_at = :now,
                   is_confirmed = :confirmed,
                   confirmed_at = CASE WHEN :confirmed THEN :now ELSE confirmed_at END
             WHERE detection_id = :detectionId AND review_status = 'PENDING_REVIEW'
            """, nativeQuery = true)
    int review(@Param("detectionId") Integer detectionId,
               @Param("reviewStatus") String reviewStatus,
               @Param("reviewNotes") String reviewNotes,
               @Param("reviewedBy") String reviewedBy,
               @Param("confirmed") boolean confirmed,
               @Param("now") LocalDateTime now);
}
