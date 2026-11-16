package com.carenexus.api.caregiveraccess.repository;

import com.carenexus.api.auth.model.User;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Read-only lookups of people around access requests and grants: one query per kind, never one per row.
 */
public interface AccessPeopleRepository extends Repository<User, Integer> {

    /** One row per existing account among {@code systemIds} (inactive accounts included). */
    @Query("SELECT u.systemId AS systemId, u.firstName AS firstName, u.lastName AS lastName, u.email AS email, "
            + "u.role AS role, u.isActive AS active FROM User u WHERE u.systemId IN :systemIds")
    List<Person> findPeople(@Param("systemIds") Collection<String> systemIds);

    /**
     * System ids of active PATIENT accounts with a patients row whose email equals {@code email} (case-insensitive).
     * Managed dependents have no email and never match. Normally zero or one row.
     */
    @Query("SELECT u.systemId FROM User u WHERE LOWER(u.email) = :email AND UPPER(u.role) = 'PATIENT' "
            + "AND u.isActive = true AND EXISTS (SELECT p.patientId FROM Patient p WHERE p.systemId = u.systemId) "
            + "ORDER BY u.userId")
    List<String> findActivePatientIdsByEmail(@Param("email") String email);

    /**
     * Locks the caller's caregivers row for the rest of the transaction and returns its id, or nothing when the
     * account has no caregiver profile. Serialises one caregiver's request submissions, so the "reuse the pending
     * request" check and the insert can never race (the pending unique indexes all start with the caregiver).
     */
    @Query(value = "SELECT c.caregiver_id FROM caregivers c WHERE c.system_id = :systemId FOR UPDATE",
            nativeQuery = true)
    List<Integer> lockCaregiverProfile(@Param("systemId") String systemId);

    /** True when the account exists, is active, has role CAREGIVER and a caregivers row. */
    @Query("SELECT COUNT(u) > 0 FROM User u WHERE u.systemId = :systemId AND u.isActive = true "
            + "AND UPPER(u.role) = 'CAREGIVER' AND EXISTS (SELECT c.caregiverId FROM Caregiver c WHERE c.systemId = u.systemId)")
    boolean isActiveCaregiver(@Param("systemId") String systemId);

    interface Person {

        String getSystemId();

        String getFirstName();

        String getLastName();

        String getEmail();

        String getRole();

        Boolean getActive();
    }
}
