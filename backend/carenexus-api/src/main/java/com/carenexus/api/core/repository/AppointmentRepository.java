package com.carenexus.api.core.repository;

import com.carenexus.api.core.model.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, Integer> {
    List<Appointment> findByPatientSystemId(String patientSystemId);
    List<Appointment> findByDoctorSystemId(String doctorSystemId);
    List<Appointment> findByPatientSystemIdAndStatus(String patientSystemId, String status);
    List<Appointment> findByDoctorSystemIdAndStatus(String doctorSystemId, String status);
    List<Appointment> findByScheduledAtBetween(LocalDateTime start, LocalDateTime end);
    List<Appointment> findByDoctorSystemIdAndScheduledAtBetween(String doctorSystemId, LocalDateTime start, LocalDateTime end);
    Optional<Appointment> findByAppointmentIdAndPatientSystemId(Integer appointmentId, String patientSystemId);
    List<Appointment> findByStatus(String status);
}
