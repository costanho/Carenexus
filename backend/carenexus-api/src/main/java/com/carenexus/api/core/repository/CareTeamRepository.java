package com.carenexus.api.core.repository;

import com.carenexus.api.core.model.CareTeam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * care_team holds formal provider assignments (doctors, specialists, nurses...).
 * Caregiver/proxy access does NOT live here: it is dependent_access (see DependentAccessRepository).
 *
 * The last three queries derive doctor-patient relationships from appointments and consultations, which are the
 * links the app actually creates today (see RelationshipService).
 */
@Repository
public interface CareTeamRepository extends JpaRepository<CareTeam, Integer> {

    String DOCTOR_ROLES = "('PRIMARY_DOCTOR','DOCTOR','SPECIALIST')";

    /**
     * Active care_team assignment of a doctor (DOCTOR / PRIMARY_DOCTOR / SPECIALIST) to a patient
     */
    @Query("SELECT c FROM CareTeam c WHERE c.patientSystemId = :patientSystemId AND c.providerSystemId = :providerSystemId AND c.isActive = true AND c.role IN " + DOCTOR_ROLES)
    Optional<CareTeam> findActiveDoctorPatientRelationship(@Param("providerSystemId") String providerSystemId, @Param("patientSystemId") String patientSystemId);

    /**
     * Get all active doctor assignments for a doctor
     */
    @Query("SELECT c FROM CareTeam c WHERE c.providerSystemId = :providerSystemId AND c.isActive = true AND c.role IN " + DOCTOR_ROLES)
    List<CareTeam> findAllPatientsForDoctor(@Param("providerSystemId") String providerSystemId);

    /**
     * Get all active doctor assignments for a patient
     */
    @Query("SELECT c FROM CareTeam c WHERE c.patientSystemId = :patientSystemId AND c.isActive = true AND c.role IN " + DOCTOR_ROLES)
    List<CareTeam> findAllDoctorsForPatient(@Param("patientSystemId") String patientSystemId);

    /**
     * Find an active care_team caregiver assignment (role CAREGIVER / ASSIGNED_CAREGIVER).
     * Not used for authorization: caregiver permissions come from dependent_access flags.
     */
    @Query("SELECT c FROM CareTeam c WHERE c.patientSystemId = :patientSystemId AND c.providerSystemId = :providerSystemId AND c.isActive = true AND c.role IN ('CAREGIVER','ASSIGNED_CAREGIVER')")
    Optional<CareTeam> findActiveCaregiverPatientRelationship(@Param("providerSystemId") String providerSystemId, @Param("patientSystemId") String patientSystemId);

    /**
     * Get all active care_team caregiver assignments for a caregiver
     */
    @Query("SELECT c FROM CareTeam c WHERE c.providerSystemId = :providerSystemId AND c.isActive = true AND c.role IN ('CAREGIVER','ASSIGNED_CAREGIVER')")
    List<CareTeam> findAllPatientsForCaregiver(@Param("providerSystemId") String providerSystemId);

    /**
     * Get all active care_team caregiver assignments for a patient
     */
    @Query("SELECT c FROM CareTeam c WHERE c.patientSystemId = :patientSystemId AND c.isActive = true AND c.role IN ('CAREGIVER','ASSIGNED_CAREGIVER')")
    List<CareTeam> findAllCaregiversForPatient(@Param("patientSystemId") String patientSystemId);

    // ==================== RELATIONSHIPS DERIVED FROM VISITS ====================

    /**
     * True if the doctor has any non-cancelled appointment with the patient.
     */
    @Query("SELECT COUNT(a) > 0 FROM Appointment a WHERE a.doctorSystemId = :doctorSystemId AND a.patientSystemId = :patientSystemId AND a.status <> 'CANCELLED'")
    boolean existsAppointmentLink(@Param("doctorSystemId") String doctorSystemId, @Param("patientSystemId") String patientSystemId);

    /**
     * True if the doctor has any consultation with the patient.
     */
    @Query("SELECT COUNT(c) > 0 FROM Consultation c WHERE c.doctorSystemId = :doctorSystemId AND c.patientSystemId = :patientSystemId")
    boolean existsConsultationLink(@Param("doctorSystemId") String doctorSystemId, @Param("patientSystemId") String patientSystemId);

    /**
     * Distinct patients the doctor has non-cancelled appointments or consultations with.
     */
    @Query("SELECT DISTINCT a.patientSystemId FROM Appointment a WHERE a.doctorSystemId = :doctorSystemId AND a.status <> 'CANCELLED'")
    List<String> findAppointmentPatientSystemIdsForDoctor(@Param("doctorSystemId") String doctorSystemId);

    @Query("SELECT DISTINCT c.patientSystemId FROM Consultation c WHERE c.doctorSystemId = :doctorSystemId")
    List<String> findConsultationPatientSystemIdsForDoctor(@Param("doctorSystemId") String doctorSystemId);

    /**
     * Distinct doctors with a non-cancelled appointment with the patient.
     */
    @Query("SELECT DISTINCT a.doctorSystemId FROM Appointment a WHERE a.patientSystemId = :patientSystemId AND a.status <> 'CANCELLED'")
    List<String> findAppointmentDoctorSystemIdsForPatient(@Param("patientSystemId") String patientSystemId);

    /**
     * Distinct doctors with a consultation with the patient.
     */
    @Query("SELECT DISTINCT c.doctorSystemId FROM Consultation c WHERE c.patientSystemId = :patientSystemId")
    List<String> findConsultationDoctorSystemIdsForPatient(@Param("patientSystemId") String patientSystemId);
}
