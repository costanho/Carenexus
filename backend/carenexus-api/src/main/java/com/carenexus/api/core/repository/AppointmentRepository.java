package com.carenexus.api.core.repository;

import com.carenexus.api.core.model.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, Integer> {
    List<Appointment> findByPatientId(Integer patientId);
    List<Appointment> findByDoctorId(Integer doctorId);
    List<Appointment> findByPatientIdAndStatus(Integer patientId, String status);
    List<Appointment> findByDoctorIdAndStatus(Integer doctorId, String status);
    List<Appointment> findByScheduledAtBetween(LocalDateTime start, LocalDateTime end);
    List<Appointment> findByDoctorIdAndScheduledAtBetween(Integer doctorId, LocalDateTime start, LocalDateTime end);
    Optional<Appointment> findByAppointmentIdAndPatientId(Integer appointmentId, Integer patientId);
    List<Appointment> findByStatus(String status);
}
