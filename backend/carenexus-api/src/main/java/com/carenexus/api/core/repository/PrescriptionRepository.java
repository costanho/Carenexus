package com.carenexus.api.core.repository;

import com.carenexus.api.core.model.Prescription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface PrescriptionRepository extends JpaRepository<Prescription, Integer> {
    List<Prescription> findByPatientSystemId(String patientSystemId);
    List<Prescription> findByDoctorSystemId(String doctorSystemId);
    List<Prescription> findByStatus(String status);
    List<Prescription> findByPatientSystemIdAndStatus(String patientSystemId, String status);
    List<Prescription> findByDoctorSystemIdAndStatus(String doctorSystemId, String status);
    List<Prescription> findByConsultationId(Integer consultationId);
    List<Prescription> findByExpiryDateBeforeAndStatus(LocalDate date, String status);
    Optional<Prescription> findByPrescriptionIdAndPatientSystemId(Integer prescriptionId, String patientSystemId);
}
