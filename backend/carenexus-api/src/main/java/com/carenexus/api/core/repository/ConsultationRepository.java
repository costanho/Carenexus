package com.carenexus.api.core.repository;

import com.carenexus.api.core.model.Consultation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConsultationRepository extends JpaRepository<Consultation, Integer> {
    List<Consultation> findByPatientId(Integer patientId);
    List<Consultation> findByDoctorId(Integer doctorId);
    List<Consultation> findByStatus(String status);
    List<Consultation> findByPatientIdAndStatus(Integer patientId, String status);
    List<Consultation> findByDoctorIdAndStatus(Integer doctorId, String status);
    List<Consultation> findByAppointmentId(Integer appointmentId);
    Optional<Consultation> findByAppointmentIdAndPatientId(Integer appointmentId, Integer patientId);
}
