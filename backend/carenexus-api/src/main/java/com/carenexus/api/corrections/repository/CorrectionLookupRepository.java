package com.carenexus.api.corrections.repository;

import com.carenexus.api.corrections.model.CorrectionRequest;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Read-only lookups into tables this module does not own: the clinical record a request is about, and the people
 * named in a request (names, role, active flag, specialization). Clinical tables are read with native queries
 * matched on both the record id and the patient, so a record of another patient is indistinguishable from a missing
 * one.
 *
 * Each record lookup returns the record's authoring doctor ({@code ''} when the column is empty) as a one-element
 * list, or an empty list when no record of that patient has this id.
 */
public interface CorrectionLookupRepository extends Repository<CorrectionRequest, Integer> {

    // ==================== CLINICAL RECORDS (author of the record) ====================

    @Query(value = "SELECT COALESCE(mr.doctor_system_id, '') FROM medical_records mr "
            + "WHERE mr.record_id = :id AND mr.patient_system_id = :patientSystemId AND mr.is_deleted = false",
            nativeQuery = true)
    List<String> medicalRecordAuthor(@Param("id") Integer id, @Param("patientSystemId") String patientSystemId);

    @Query(value = "SELECT COALESCE(lr.doctor_system_id, '') FROM lab_results lr "
            + "WHERE lr.result_id = :id AND lr.patient_system_id = :patientSystemId",
            nativeQuery = true)
    List<String> labResultAuthor(@Param("id") Integer id, @Param("patientSystemId") String patientSystemId);

    @Query(value = "SELECT COALESCE(ir.doctor_system_id, '') FROM imaging_results ir "
            + "WHERE ir.imaging_id = :id AND ir.patient_system_id = :patientSystemId",
            nativeQuery = true)
    List<String> imagingResultAuthor(@Param("id") Integer id, @Param("patientSystemId") String patientSystemId);

    @Query(value = "SELECT COALESCE(rx.doctor_system_id, '') FROM prescriptions rx "
            + "WHERE rx.prescription_id = :id AND rx.patient_system_id = :patientSystemId",
            nativeQuery = true)
    List<String> prescriptionAuthor(@Param("id") Integer id, @Param("patientSystemId") String patientSystemId);

    @Query(value = "SELECT COALESCE(c.doctor_system_id, '') FROM consultations c "
            + "WHERE c.consultation_id = :id AND c.patient_system_id = :patientSystemId",
            nativeQuery = true)
    List<String> consultationAuthor(@Param("id") Integer id, @Param("patientSystemId") String patientSystemId);

    // ==================== PEOPLE ====================

    /** One row per existing account among {@code systemIds} (inactive accounts included). */
    @Query("SELECT u.systemId AS systemId, u.firstName AS firstName, u.lastName AS lastName, u.role AS role, "
            + "u.isActive AS active FROM User u WHERE u.systemId IN :systemIds")
    List<PersonRow> findPeople(@Param("systemIds") Collection<String> systemIds);

    /** Active DOCTOR accounts among {@code systemIds}, with their specialization (null when not set). */
    @Query("SELECT u.systemId AS systemId, u.firstName AS firstName, u.lastName AS lastName, "
            + "d.specialization AS specialization FROM User u LEFT JOIN Doctor d ON d.systemId = u.systemId "
            + "WHERE u.systemId IN :systemIds AND u.role = 'DOCTOR' AND u.isActive = true")
    List<DoctorRow> findActiveDoctors(@Param("systemIds") Collection<String> systemIds);

    /** True when the account exists, has role DOCTOR and is active. */
    @Query("SELECT COUNT(u) > 0 FROM User u WHERE u.systemId = :systemId AND u.role = 'DOCTOR' AND u.isActive = true")
    boolean isActiveDoctor(@Param("systemId") String systemId);

    /** System ids of every active administrator, in a stable order. */
    @Query("SELECT u.systemId FROM User u WHERE u.role = 'ADMIN' AND u.isActive = true ORDER BY u.systemId")
    List<String> findActiveAdminIds();

    interface PersonRow {

        String getSystemId();

        String getFirstName();

        String getLastName();

        String getRole();

        Boolean getActive();
    }

    interface DoctorRow {

        String getSystemId();

        String getFirstName();

        String getLastName();

        String getSpecialization();
    }
}
