package com.carenexus.api.core.repository;

import com.carenexus.api.core.model.ImagingResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ImagingResultRepository extends JpaRepository<ImagingResult, Integer> {
    List<ImagingResult> findByPatientId(Integer patientId);
    List<ImagingResult> findByDoctorId(Integer doctorId);
    List<ImagingResult> findByImagingType(String imagingType);
    List<ImagingResult> findByStatus(String status);
    List<ImagingResult> findByPatientIdAndStatus(Integer patientId, String status);
    List<ImagingResult> findByDoctorIdAndStatus(Integer doctorId, String status);
    List<ImagingResult> findByConsultationId(Integer consultationId);
    List<ImagingResult> findByPatientIdAndImagingType(Integer patientId, String imagingType);
    List<ImagingResult> findByDoctorIdAndImagingType(Integer doctorId, String imagingType);
    List<ImagingResult> findByImageDateBetween(LocalDate startDate, LocalDate endDate);
    List<ImagingResult> findByPatientIdAndImageDateBetween(Integer patientId, LocalDate startDate, LocalDate endDate);
    List<ImagingResult> findByStatusAndReviewedByIsNull(String status);
    List<ImagingResult> findByPatientIdAndImagingTypeAndStatus(Integer patientId, String imagingType, String status);
    Optional<ImagingResult> findByImagingIdAndPatientId(Integer imagingId, Integer patientId);
}
