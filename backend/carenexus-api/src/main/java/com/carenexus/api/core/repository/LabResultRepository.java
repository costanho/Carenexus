package com.carenexus.api.core.repository;

import com.carenexus.api.core.model.LabResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Lab results. New list code uses the paged {@link #search} / {@link #findPendingReview} and the capped
 * {@code findTop200...} queries; every list leaves out CANCELLED rows unless asked for them. The unpaged finders
 * at the top are kept for existing callers only.
 */
@Repository
public interface LabResultRepository extends JpaRepository<LabResult, Integer> {

    /**
     * "This document still has a stored file", written so it holds whether or not documents.storage_key exists:
     * the column arrives with V41 (be-documents). Once it exists the predicate is {@code storage_key IS NOT NULL};
     * before that every non-deleted document counts.
     */
    String DOCUMENT_HAS_FILE =
            "(NOT jsonb_exists(to_jsonb(d), 'storage_key') OR (to_jsonb(d) ->> 'storage_key') IS NOT NULL)";

    /** Unreviewed results awaiting a doctor: authored by the doctor, or of a patient on the doctor's active care team. */
    String PENDING_REVIEW_WHERE =
            "r.reviewed_at IS NULL AND r.status IN ('REQUIRES_REVIEW', 'CRITICAL') "
                    + "AND (r.doctor_system_id = :doctorSystemId OR EXISTS (SELECT 1 FROM care_team ct "
                    + "WHERE ct.patient_system_id = r.patient_system_id AND ct.provider_system_id = :doctorSystemId "
                    + "AND ct.is_active = TRUE))";

    /** Display name parts of an account (users table). */
    interface PersonName {
        String getSystemId();

        String getFirstName();

        String getLastName();
    }

    List<LabResult> findByPatientSystemId(String patientSystemId);
    List<LabResult> findByDoctorSystemId(String doctorSystemId);
    List<LabResult> findByTestType(String testType);
    List<LabResult> findByStatus(String status);
    List<LabResult> findByPatientSystemIdAndStatus(String patientSystemId, String status);
    List<LabResult> findByDoctorSystemIdAndStatus(String doctorSystemId, String status);
    List<LabResult> findByConsultationId(Integer consultationId);
    List<LabResult> findByPatientSystemIdAndTestType(String patientSystemId, String testType);
    List<LabResult> findByDoctorSystemIdAndTestType(String doctorSystemId, String testType);
    List<LabResult> findByTestDateBetween(LocalDate startDate, LocalDate endDate);
    List<LabResult> findByPatientSystemIdAndTestDateBetween(String patientSystemId, LocalDate startDate, LocalDate endDate);
    List<LabResult> findByIsCriticalTrue();
    List<LabResult> findByPatientSystemIdAndIsCriticalTrue(String patientSystemId);
    List<LabResult> findByDoctorSystemIdAndIsCriticalTrue(String doctorSystemId);
    List<LabResult> findByStatusAndReviewedBySystemIdIsNull(String status);
    Optional<LabResult> findByResultIdAndPatientSystemId(Integer resultId, String patientSystemId);

    /**
     * The canonical filtered list. Every filter is optional (null = no filter). Without {@code status} the CANCELLED
     * rows are left out. Sort with the pageable (test_date DESC, result_id DESC).
     */
    @Query("SELECT r FROM LabResult r WHERE "
            + "(:patientSystemId IS NULL OR r.patientSystemId = :patientSystemId) "
            + "AND (:doctorSystemId IS NULL OR r.doctorSystemId = :doctorSystemId) "
            + "AND ((:status IS NULL AND r.status <> 'CANCELLED') OR r.status = :status) "
            + "AND (:testType IS NULL OR r.testType = :testType) "
            + "AND (:critical IS NULL OR r.isCritical = :critical) "
            + "AND (:consultationId IS NULL OR r.consultationId = :consultationId)")
    Page<LabResult> search(@Param("patientSystemId") String patientSystemId,
                           @Param("doctorSystemId") String doctorSystemId,
                           @Param("status") String status,
                           @Param("testType") String testType,
                           @Param("critical") Boolean critical,
                           @Param("consultationId") Integer consultationId,
                           Pageable pageable);

    /** The doctor's review queue, newest first. Pass an unsorted pageable (the order is in the query). */
    @Query(value = "SELECT r.* FROM lab_results r WHERE " + PENDING_REVIEW_WHERE
            + " ORDER BY r.test_date DESC, r.result_id DESC",
            countQuery = "SELECT COUNT(*) FROM lab_results r WHERE " + PENDING_REVIEW_WHERE,
            nativeQuery = true)
    Page<LabResult> findPendingReview(@Param("doctorSystemId") String doctorSystemId, Pageable pageable);

    /** Legacy patient array: newest first, at most 200 rows, without rows of {@code excludedStatus}. */
    List<LabResult> findTop200ByPatientSystemIdAndStatusNotOrderByTestDateDescResultIdDesc(
            String patientSystemId, String excludedStatus);

    /** Legacy doctor array: newest first, at most 200 rows, without rows of {@code excludedStatus}. */
    List<LabResult> findTop200ByDoctorSystemIdAndStatusNotOrderByTestDateDescResultIdDesc(
            String doctorSystemId, String excludedStatus);

    /** Name parts of the given accounts (one query for a whole page). Never call it with an empty collection. */
    @Query(value = "SELECT u.system_id AS \"systemId\", u.first_name AS \"firstName\", u.last_name AS \"lastName\" "
            + "FROM users u WHERE u.system_id IN (:systemIds)", nativeQuery = true)
    List<PersonName> findPersonNames(@Param("systemIds") Collection<String> systemIds);

    /**
     * Which of these documents are still usable (not soft-deleted, file stored). Never call it with an empty
     * collection.
     */
    @Query(value = "SELECT d.document_id FROM documents d WHERE d.document_id IN (:documentIds) "
            + "AND d.is_deleted = FALSE AND " + DOCUMENT_HAS_FILE, nativeQuery = true)
    List<Integer> findUsableDocumentIds(@Param("documentIds") Collection<Integer> documentIds);

    /** Whether the document exists, belongs to the patient, is not soft-deleted and has a stored file. */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM documents d WHERE d.document_id = :documentId "
            + "AND d.patient_system_id = :patientSystemId AND d.is_deleted = FALSE AND " + DOCUMENT_HAS_FILE + ")",
            nativeQuery = true)
    boolean isUsableDocumentOfPatient(@Param("documentId") Integer documentId,
                                      @Param("patientSystemId") String patientSystemId);
}
