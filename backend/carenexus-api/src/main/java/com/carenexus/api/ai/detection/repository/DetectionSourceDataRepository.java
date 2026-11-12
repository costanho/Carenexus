package com.carenexus.api.ai.detection.repository;

import com.carenexus.api.ai.detection.model.DiseaseDetection;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Read-only native queries of tables the detection module does not own ({@code patients}, {@code users},
 * {@code symptom_logs}, {@code health_metrics}, {@code prescriptions}). Used to build the de-identified screening
 * input and to resolve patient names. Dates are returned as {@code YYYY-MM-DD} text (clinic calendar).
 */
public interface DetectionSourceDataRepository extends Repository<DiseaseDetection, Integer> {

    /** Age in whole years on {@code today} (null when the date of birth is unknown), sex and chronic conditions. */
    @Query(value = """
            SELECT CAST(date_part('year', age(CAST(:today AS DATE), p.date_of_birth)) AS INTEGER) AS ageYears,
                   p.gender AS gender,
                   CAST(p.chronic_conditions AS TEXT) AS chronicConditions
              FROM patients p
             WHERE p.system_id = :patientSystemId
            """, nativeQuery = true)
    Optional<PatientProfileRow> findPatientProfile(@Param("patientSystemId") String patientSystemId,
                                                   @Param("today") LocalDate today);

    /** Non-deleted symptom logs since {@code from}, newest first, at most {@code limit}. */
    @Query(value = """
            SELECT to_char(s.logged_at, 'YYYY-MM-DD') AS loggedOn,
                   s.severity AS severity,
                   CAST(s.symptoms AS TEXT) AS symptoms
              FROM symptom_logs s
             WHERE s.patient_system_id = :patientSystemId
               AND s.is_deleted = FALSE
               AND s.logged_at >= :from
             ORDER BY s.logged_at DESC, s.symptom_id DESC
             LIMIT :limit
            """, nativeQuery = true)
    List<SymptomRow> findRecentSymptomLogs(@Param("patientSystemId") String patientSystemId,
                                           @Param("from") LocalDateTime from,
                                           @Param("limit") int limit);

    /** The latest reading per type recorded since {@code from} (BMI is derived and never stored as a reading). */
    @Query(value = """
            SELECT DISTINCT ON (m.type)
                   m.type AS type,
                   m.value AS value,
                   m.unit AS unit,
                   to_char(m.recorded_at, 'YYYY-MM-DD') AS recordedOn
              FROM health_metrics m
             WHERE m.patient_system_id = :patientSystemId
               AND m.recorded_at >= :from
               AND m.type <> 'BMI'
             ORDER BY m.type, m.recorded_at DESC, m.metric_id DESC
            """, nativeQuery = true)
    List<ReadingRow> findLatestReadings(@Param("patientSystemId") String patientSystemId,
                                        @Param("from") LocalDateTime from);

    /** Names of the patient's ACTIVE prescriptions that have not expired on {@code today}. */
    @Query(value = """
            SELECT DISTINCT r.medication_name
              FROM prescriptions r
             WHERE r.patient_system_id = :patientSystemId
               AND r.status = 'ACTIVE'
               AND (r.expiry_date IS NULL OR r.expiry_date >= CAST(:today AS DATE))
             ORDER BY r.medication_name
             LIMIT 50
            """, nativeQuery = true)
    List<String> findActiveMedicationNames(@Param("patientSystemId") String patientSystemId,
                                           @Param("today") LocalDate today);

    /** First and last names of the given accounts. */
    @Query(value = "SELECT u.system_id AS systemId, u.first_name AS firstName, u.last_name AS lastName"
            + " FROM users u WHERE u.system_id IN (:systemIds)", nativeQuery = true)
    List<PersonNameRow> findNames(@Param("systemIds") Collection<String> systemIds);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM patients p WHERE p.system_id = :systemId)", nativeQuery = true)
    boolean patientExists(@Param("systemId") String systemId);

    /** True when the symptom log row exists (soft-deleted logs included), so {@code symptom_id} may reference it. */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM symptom_logs s WHERE s.symptom_id = :symptomId)", nativeQuery = true)
    boolean symptomLogExists(@Param("symptomId") Integer symptomId);

    interface PatientProfileRow {

        Integer getAgeYears();

        String getGender();

        String getChronicConditions();
    }

    interface SymptomRow {

        String getLoggedOn();

        String getSeverity();

        String getSymptoms();
    }

    interface ReadingRow {

        String getType();

        BigDecimal getValue();

        String getUnit();

        String getRecordedOn();
    }

    interface PersonNameRow {

        String getSystemId();

        String getFirstName();

        String getLastName();
    }
}
