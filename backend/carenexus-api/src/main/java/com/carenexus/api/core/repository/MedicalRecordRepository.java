package com.carenexus.api.core.repository;

import com.carenexus.api.core.model.MedicalRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface MedicalRecordRepository extends JpaRepository<MedicalRecord, Integer> {

    /**
     * Filters of {@link #search}. Every filter is optional: an empty string (or 0 for {@code consultationId}) means
     * "no filter", so no parameter is ever bound as SQL NULL. {@code qPattern} is a ready ILIKE pattern
     * ({@code %text%} with {@code %}, {@code _} and {@code \} escaped by the caller) matched against the title and
     * the code / description of every stored ICD code (older rows may hold plain strings in the array).
     */
    String SEARCH_WHERE = """
            FROM medical_records mr
            WHERE mr.is_deleted = FALSE
              AND (:patientSystemId = '' OR mr.patient_system_id = :patientSystemId)
              AND (:doctorSystemId = '' OR mr.doctor_system_id = :doctorSystemId)
              AND (:recordType = '' OR mr.record_type = :recordType)
              AND (:consultationId = 0 OR mr.consultation_id = :consultationId)
              AND (:qPattern = ''
                   OR mr.title ILIKE :qPattern
                   OR EXISTS (
                        SELECT 1
                        FROM jsonb_array_elements(CASE WHEN jsonb_typeof(mr.icd_codes) = 'array'
                                                       THEN mr.icd_codes ELSE CAST('[]' AS jsonb) END) AS e(item)
                        WHERE (jsonb_typeof(e.item) = 'object'
                               AND (e.item ->> 'code' ILIKE :qPattern OR e.item ->> 'description' ILIKE :qPattern))
                           OR (jsonb_typeof(e.item) = 'string'
                               AND btrim(CAST(e.item AS text), '"') ILIKE :qPattern)))
            """;

    /** Live records matching the filters, newest first ({@code created_at DESC, record_id DESC}). */
    @Query(value = "SELECT mr.* " + SEARCH_WHERE + " ORDER BY mr.created_at DESC, mr.record_id DESC",
            countQuery = "SELECT COUNT(*) " + SEARCH_WHERE,
            nativeQuery = true)
    Page<MedicalRecord> search(@Param("patientSystemId") String patientSystemId,
                               @Param("doctorSystemId") String doctorSystemId,
                               @Param("recordType") String recordType,
                               @Param("consultationId") int consultationId,
                               @Param("qPattern") String qPattern,
                               Pageable pageable);

    /** True when another live CONSULT record exists for the consultation ({@code recordId} 0 excludes nothing). */
    boolean existsByConsultationIdAndRecordTypeAndIsDeletedFalseAndRecordIdNot(
            Integer consultationId, String recordType, Integer recordId);

    /** {@code [system_id, first_name, last_name]} of the given users, for doctor names on record lists. */
    @Query(value = "SELECT u.system_id, u.first_name, u.last_name FROM users u WHERE u.system_id IN (:systemIds)",
            nativeQuery = true)
    List<Object[]> findUserNames(@Param("systemIds") Collection<String> systemIds);

    List<MedicalRecord> findByPatientSystemIdAndIsDeletedFalse(String patientSystemId);
    List<MedicalRecord> findByDoctorSystemIdAndIsDeletedFalse(String doctorSystemId);
    List<MedicalRecord> findByRecordTypeAndIsDeletedFalse(String recordType);
    List<MedicalRecord> findByPatientSystemIdAndRecordTypeAndIsDeletedFalse(String patientSystemId, String recordType);
    List<MedicalRecord> findByDoctorSystemIdAndRecordTypeAndIsDeletedFalse(String doctorSystemId, String recordType);
    List<MedicalRecord> findByConsultationIdAndIsDeletedFalse(Integer consultationId);
    List<MedicalRecord> findByDoctorSystemIdAndPatientSystemIdAndIsDeletedFalse(String doctorSystemId, String patientSystemId);
    Optional<MedicalRecord> findByRecordIdAndIsDeletedFalse(Integer recordId);
}
