package com.carenexus.api.core.repository;

import com.carenexus.api.core.model.LabResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface LabResultRepository extends JpaRepository<LabResult, Integer> {
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
    List<LabResult> findByStatusAndReviewedBySystemIdIsNull(String status);
    Optional<LabResult> findByResultIdAndPatientSystemId(Integer resultId, String patientSystemId);
}
