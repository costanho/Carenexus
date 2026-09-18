package com.carenexus.api.core.repository;

import com.carenexus.api.core.model.Prescription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface PrescriptionRepository extends JpaRepository<Prescription, Integer> {
    List<Prescription> findByPatientId(Integer patientId);
    List<Prescription> findByDoctorId(Integer doctorId);
    List<Prescription> findByStatus(String status);
    List<Prescription> findByPatientIdAndStatus(Integer patientId, String status);
    List<Prescription> findByDoctorIdAndStatus(Integer doctorId, String status);
    List<Prescription> findByConsultationId(Integer consultationId);
    List<Prescription> findByExpiryDateBeforeAndStatus(LocalDate date, String status);
    Optional<Prescription> findByPrescriptionIdAndPatientId(Integer prescriptionId, Integer patientId);
}
