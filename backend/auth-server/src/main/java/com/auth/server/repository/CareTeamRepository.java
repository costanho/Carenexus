package com.auth.server.repository;

import com.auth.server.model.CareTeam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CareTeamRepository extends JpaRepository<CareTeam, Integer> {

    /**
     * Find if doctor is assigned to patient
     * Doctor can access patient's data if this relationship exists and is active
     */
    @Query("SELECT c FROM CareTeam c WHERE c.patientId = :patientId AND c.doctorId = :doctorId AND c.isActive = true")
    Optional<CareTeam> findActiveDoctorPatientRelationship(@Param("doctorId") Integer doctorId, @Param("patientId") Integer patientId);

    /**
     * Find if caregiver is assigned to patient
     */
    @Query("SELECT c FROM CareTeam c WHERE c.patientId = :patientId AND c.caregiverId = :caregiverId AND c.isActive = true")
    Optional<CareTeam> findActiveCaregiverPatientRelationship(@Param("caregiverId") Integer caregiverId, @Param("patientId") Integer patientId);

    /**
     * Get all patients assigned to a doctor
     */
    @Query("SELECT c FROM CareTeam c WHERE c.doctorId = :doctorId AND c.isActive = true")
    List<CareTeam> findAllPatientsForDoctor(@Param("doctorId") Integer doctorId);

    /**
     * Get all patients assigned to a caregiver
     */
    @Query("SELECT c FROM CareTeam c WHERE c.caregiverId = :caregiverId AND c.isActive = true")
    List<CareTeam> findAllPatientsForCaregiver(@Param("caregiverId") Integer caregiverId);

    /**
     * Get all doctors assigned to a patient
     */
    @Query("SELECT c FROM CareTeam c WHERE c.patientId = :patientId AND c.doctorId IS NOT NULL AND c.isActive = true")
    List<CareTeam> findAllDoctorsForPatient(@Param("patientId") Integer patientId);

    /**
     * Get all caregivers assigned to a patient
     */
    @Query("SELECT c FROM CareTeam c WHERE c.patientId = :patientId AND c.caregiverId IS NOT NULL AND c.isActive = true")
    List<CareTeam> findAllCaregiversForPatient(@Param("patientId") Integer patientId);

    /**
     * Check if any relationship exists between doctor and patient (by facility)
     */
    @Query("SELECT c FROM CareTeam c WHERE c.patientId = :patientId AND c.doctorId = :doctorId AND c.facilityId = :facilityId AND c.isActive = true")
    Optional<CareTeam> findDoctorPatientByFacility(@Param("doctorId") Integer doctorId, @Param("patientId") Integer patientId, @Param("facilityId") Integer facilityId);
}
