package com.carenexus.api.core.repository;

import com.carenexus.api.core.model.ImagingResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Imaging results. New list code uses the paged {@link #search} / {@link #findPendingReview} and the capped
 * {@code findTop200...} query; every list leaves out CANCELLED rows unless asked for them. The unpaged finders at
 * the top are kept for existing callers only. Names and documents are resolved through
 * {@link LabResultRepository#findPersonNames} / {@link LabResultRepository#findUsableDocumentIds}.
 */
@Repository
public interface ImagingResultRepository extends JpaRepository<ImagingResult, Integer> {

    String PENDING_REVIEW_WHERE =
            "r.reviewed_at IS NULL AND r.status IN ('REQUIRES_REVIEW', 'CRITICAL') "
                    + "AND (r.doctor_system_id = :doctorSystemId OR EXISTS (SELECT 1 FROM care_team ct "
                    + "WHERE ct.patient_system_id = r.patient_system_id AND ct.provider_system_id = :doctorSystemId "
                    + "AND ct.is_active = TRUE))";

    List<ImagingResult> findByPatientSystemId(String patientSystemId);
    List<ImagingResult> findByDoctorSystemId(String doctorSystemId);
    List<ImagingResult> findByImagingType(String imagingType);
    List<ImagingResult> findByStatus(String status);
    List<ImagingResult> findByPatientSystemIdAndStatus(String patientSystemId, String status);
    List<ImagingResult> findByDoctorSystemIdAndStatus(String doctorSystemId, String status);
    List<ImagingResult> findByConsultationId(Integer consultationId);
    List<ImagingResult> findByPatientSystemIdAndImagingType(String patientSystemId, String imagingType);
    List<ImagingResult> findByDoctorSystemIdAndImagingType(String doctorSystemId, String imagingType);
    List<ImagingResult> findByImageDateBetween(LocalDate startDate, LocalDate endDate);
    List<ImagingResult> findByPatientSystemIdAndImageDateBetween(String patientSystemId, LocalDate startDate, LocalDate endDate);
    List<ImagingResult> findByStatusAndReviewedBySystemIdIsNull(String status);
    List<ImagingResult> findByPatientSystemIdAndImagingTypeAndStatus(String patientSystemId, String imagingType, String status);
    Optional<ImagingResult> findByImagingIdAndPatientSystemId(Integer imagingId, String patientSystemId);

    /**
     * The canonical filtered list. Every filter is optional (null = no filter). Without {@code status} the CANCELLED
     * rows are left out; {@code excludedStatus} drops one more status (critical=false drops CRITICAL). Sort with the
     * pageable (image_date DESC, imaging_id DESC).
     */
    @Query("SELECT r FROM ImagingResult r WHERE "
            + "(:patientSystemId IS NULL OR r.patientSystemId = :patientSystemId) "
            + "AND (:doctorSystemId IS NULL OR r.doctorSystemId = :doctorSystemId) "
            + "AND ((:status IS NULL AND r.status <> 'CANCELLED') OR r.status = :status) "
            + "AND (:excludedStatus IS NULL OR r.status <> :excludedStatus) "
            + "AND (:imagingType IS NULL OR r.imagingType = :imagingType) "
            + "AND (:consultationId IS NULL OR r.consultationId = :consultationId)")
    Page<ImagingResult> search(@Param("patientSystemId") String patientSystemId,
                               @Param("doctorSystemId") String doctorSystemId,
                               @Param("status") String status,
                               @Param("excludedStatus") String excludedStatus,
                               @Param("imagingType") String imagingType,
                               @Param("consultationId") Integer consultationId,
                               Pageable pageable);

    /** The doctor's review queue, newest first. Pass an unsorted pageable (the order is in the query). */
    @Query(value = "SELECT r.* FROM imaging_results r WHERE " + PENDING_REVIEW_WHERE
            + " ORDER BY r.image_date DESC, r.imaging_id DESC",
            countQuery = "SELECT COUNT(*) FROM imaging_results r WHERE " + PENDING_REVIEW_WHERE,
            nativeQuery = true)
    Page<ImagingResult> findPendingReview(@Param("doctorSystemId") String doctorSystemId, Pageable pageable);

    /** Legacy patient array: newest first, at most 200 rows, without rows of {@code excludedStatus}. */
    List<ImagingResult> findTop200ByPatientSystemIdAndStatusNotOrderByImageDateDescImagingIdDesc(
            String patientSystemId, String excludedStatus);
}
