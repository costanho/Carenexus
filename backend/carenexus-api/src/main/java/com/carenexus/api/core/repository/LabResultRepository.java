package com.carenexus.api.core.repository;

import com.carenexus.api.core.model.LabResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface LabResultRepository extends JpaRepository<LabResult, Integer> {
    List<LabResult> findByPatientId(Integer patientId);
    List<LabResult> findByDoctorId(Integer doctorId);
    List<LabResult> findByTestType(String testType);
    List<LabResult> findByStatus(String status);
    List<LabResult> findByPatientIdAndStatus(Integer patientId, String status);
    List<LabResult> findByDoctorIdAndStatus(Integer doctorId, String status);
    List<LabResult> findByConsultationId(Integer consultationId);
    List<LabResult> findByPatientIdAndTestType(Integer patientId, String testType);
    List<LabResult> findByDoctorIdAndTestType(Integer doctorId, String testType);
    List<LabResult> findByTestDateBetween(LocalDate startDate, LocalDate endDate);
    List<LabResult> findByPatientIdAndTestDateBetween(Integer patientId, LocalDate startDate, LocalDate endDate);
    List<LabResult> findByIsCriticalTrue();
    List<LabResult> findByPatientIdAndIsCriticalTrue(Integer patientId);
    List<LabResult> findByStatusAndReviewedByIsNull(String status);
    Optional<LabResult> findByResultIdAndPatientId(Integer resultId, Integer patientId);
}
