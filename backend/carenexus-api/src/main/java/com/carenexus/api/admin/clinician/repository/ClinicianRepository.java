package com.carenexus.api.admin.clinician.repository;

import com.carenexus.api.core.model.Doctor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The doctors queries of the admin module (clinician verification queue, verification decisions, licence edits).
 * List queries join the users row in the same statement and are ordered by registration time, then row id
 * (oldest first: the queue is a work list). Each row is {@code [Doctor, User]}.
 */
public interface ClinicianRepository extends Repository<Doctor, Integer> {

    Optional<Doctor> findBySystemId(String systemId);

    Doctor save(Doctor doctor);

    List<Doctor> findBySystemIdIn(Collection<String> systemIds);

    /** Another doctor (not {@code systemId}) already holds this licence number, whatever the letter case. */
    @Query("SELECT COUNT(d) > 0 FROM Doctor d WHERE LOWER(d.licenseNo) = LOWER(:licenseNo) AND d.systemId <> :systemId")
    boolean licenceTakenByAnother(@Param("licenseNo") String licenseNo, @Param("systemId") String systemId);

    @Query(value = "SELECT d, u FROM Doctor d JOIN User u ON u.systemId = d.systemId "
            + "WHERE d.verificationStatus = :status ORDER BY d.createdAt ASC, d.doctorId ASC",
            countQuery = "SELECT COUNT(d) FROM Doctor d JOIN User u ON u.systemId = d.systemId "
                    + "WHERE d.verificationStatus = :status")
    Page<Object[]> findWithUserByStatus(@Param("status") String status, Pageable pageable);

    @Query(value = "SELECT d, u FROM Doctor d JOIN User u ON u.systemId = d.systemId "
            + "ORDER BY d.createdAt ASC, d.doctorId ASC",
            countQuery = "SELECT COUNT(d) FROM Doctor d JOIN User u ON u.systemId = d.systemId")
    Page<Object[]> findAllWithUser(Pageable pageable);

    /** System ids of every active administrator, for the "new clinician registration" notice. */
    @Query("SELECT u.systemId FROM User u WHERE u.role = 'ADMIN' AND u.isActive = true ORDER BY u.userId")
    List<String> findActiveAdminSystemIds();
}
