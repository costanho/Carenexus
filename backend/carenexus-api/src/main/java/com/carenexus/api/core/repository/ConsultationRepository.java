package com.carenexus.api.core.repository;

import com.carenexus.api.core.model.Consultation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConsultationRepository extends JpaRepository<Consultation, Integer> {
    List<Consultation> findByPatientSystemId(String patientSystemId);
    List<Consultation> findByDoctorSystemId(String doctorSystemId);
    List<Consultation> findByStatus(String status);
    List<Consultation> findByPatientSystemIdAndStatus(String patientSystemId, String status);
    List<Consultation> findByDoctorSystemIdAndStatus(String doctorSystemId, String status);
    List<Consultation> findByAppointmentId(Integer appointmentId);
    Optional<Consultation> findByAppointmentIdAndPatientSystemId(Integer appointmentId, String patientSystemId);
}
