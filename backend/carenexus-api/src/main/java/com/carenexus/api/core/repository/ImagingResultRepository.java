package com.carenexus.api.core.repository;

import com.carenexus.api.core.model.ImagingResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ImagingResultRepository extends JpaRepository<ImagingResult, Integer> {
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
}
