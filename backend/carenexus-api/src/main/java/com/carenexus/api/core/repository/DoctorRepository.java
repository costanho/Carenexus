package com.carenexus.api.core.repository;

import com.carenexus.api.core.model.Doctor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DoctorRepository extends JpaRepository<Doctor, Integer> {
    Optional<Doctor> findBySystemId(String systemId);
    Optional<Doctor> findByLicenseNo(String licenseNo);

    /** Another doctor holds this licence number, whatever its letter case. */
    boolean existsByLicenseNoIgnoreCaseAndSystemIdNot(String licenseNo, String systemId);

    /** Doctors in one verification state, oldest first (deterministic order for capped lists). */
    List<Doctor> findByVerificationStatusOrderByCreatedAtAscDoctorIdAsc(String verificationStatus, Pageable pageable);

    // ==================== PROFILE COUNTERS (COUNT queries, no row loading) ====================

    @Query("SELECT COUNT(a) FROM Appointment a WHERE a.doctorSystemId = :doctorSystemId")
    long countAppointmentsOfDoctor(@Param("doctorSystemId") String doctorSystemId);

    @Query("SELECT COUNT(c) FROM Consultation c WHERE c.doctorSystemId = :doctorSystemId")
    long countConsultationsOfDoctor(@Param("doctorSystemId") String doctorSystemId);

    @Query("SELECT COUNT(c) FROM Consultation c WHERE c.doctorSystemId = :doctorSystemId AND c.status = 'COMPLETED'")
    long countCompletedConsultationsOfDoctor(@Param("doctorSystemId") String doctorSystemId);

    @Query("SELECT COUNT(p) FROM Prescription p WHERE p.doctorSystemId = :doctorSystemId")
    long countPrescriptionsOfDoctor(@Param("doctorSystemId") String doctorSystemId);

    @Query("SELECT COUNT(m) FROM MedicalRecord m WHERE m.doctorSystemId = :doctorSystemId AND m.isDeleted = false")
    long countMedicalRecordsOfDoctor(@Param("doctorSystemId") String doctorSystemId);
}
