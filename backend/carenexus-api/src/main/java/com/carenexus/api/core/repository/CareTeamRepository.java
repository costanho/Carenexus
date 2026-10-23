package com.carenexus.api.core.repository;

import com.carenexus.api.core.model.CareTeam;
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
    @Query("SELECT c FROM CareTeam c WHERE c.patientSystemId = :patientSystemId AND c.providerSystemId = :providerSystemId AND c.isActive = true")
    Optional<CareTeam> findActiveDoctorPatientRelationship(@Param("providerSystemId") String providerSystemId, @Param("patientSystemId") String patientSystemId);

    /**
     * Get all patients assigned to a doctor
     */
    @Query("SELECT c FROM CareTeam c WHERE c.providerSystemId = :providerSystemId AND c.isActive = true")
    List<CareTeam> findAllPatientsForDoctor(@Param("providerSystemId") String providerSystemId);

    /**
     * Get all doctors assigned to a patient
     */
    @Query("SELECT c FROM CareTeam c WHERE c.patientSystemId = :patientSystemId AND c.isActive = true")
    List<CareTeam> findAllDoctorsForPatient(@Param("patientSystemId") String patientSystemId);

    /**
     * Find if caregiver is assigned to patient
     * Caregivers can access patient's data if this relationship exists and is active
     */
    @Query("SELECT c FROM CareTeam c WHERE c.patientSystemId = :patientSystemId AND c.providerSystemId = :providerSystemId AND c.isActive = true")
    Optional<CareTeam> findActiveCaregiverPatientRelationship(@Param("providerSystemId") String providerSystemId, @Param("patientSystemId") String patientSystemId);

    /**
     * Get all patients assigned to a caregiver
     */
    @Query("SELECT c FROM CareTeam c WHERE c.providerSystemId = :providerSystemId AND c.isActive = true")
    List<CareTeam> findAllPatientsForCaregiver(@Param("providerSystemId") String providerSystemId);

    /**
     * Get all caregivers assigned to a patient
     */
    @Query("SELECT c FROM CareTeam c WHERE c.patientSystemId = :patientSystemId AND c.isActive = true")
    List<CareTeam> findAllCaregiversForPatient(@Param("patientSystemId") String patientSystemId);
}
