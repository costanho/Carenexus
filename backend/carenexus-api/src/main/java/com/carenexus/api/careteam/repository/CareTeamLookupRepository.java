package com.carenexus.api.careteam.repository;

import com.carenexus.api.auth.model.User;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Batched read-only lookups the care-team module needs around care_team rows: people (users), doctor profiles and
 * facilities. One query per kind, never one per row. Facilities are read natively from the V1 table
 * ({@code facilities(facility_id, name, is_active)}), which keeps this module independent of the facility module.
 */
public interface CareTeamLookupRepository extends Repository<User, Integer> {

    /** One row per existing account among {@code systemIds} (inactive accounts included). */
    @Query("SELECT u.systemId AS systemId, u.firstName AS firstName, u.lastName AS lastName, u.role AS role, "
            + "u.isActive AS active FROM User u WHERE u.systemId IN :systemIds")
    List<Person> findPeople(@Param("systemIds") Collection<String> systemIds);

    /** Doctor profile rows among {@code systemIds}. */
    @Query("SELECT d.systemId AS systemId, d.specialization AS specialization, "
            + "d.verificationStatus AS verificationStatus, d.isActive AS active FROM Doctor d WHERE d.systemId IN :systemIds")
    List<DoctorProfile> findDoctors(@Param("systemIds") Collection<String> systemIds);

    /** True when a user with role PATIENT has this system id (active or not). */
    @Query("SELECT COUNT(u) > 0 FROM User u WHERE u.systemId = :systemId AND UPPER(u.role) = 'PATIENT'")
    boolean existsPatient(@Param("systemId") String systemId);

    /** Facilities among {@code facilityIds}, active or not. */
    @Query(value = "SELECT f.facility_id AS facilityId, f.name AS name, f.is_active AS active "
            + "FROM facilities f WHERE f.facility_id IN (:facilityIds)", nativeQuery = true)
    List<FacilityRow> findFacilities(@Param("facilityIds") Collection<Integer> facilityIds);

    /** True when the facility exists and is active. */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM facilities f WHERE f.facility_id = :facilityId AND f.is_active)",
            nativeQuery = true)
    boolean existsActiveFacility(@Param("facilityId") Integer facilityId);

    interface Person {

        String getSystemId();

        String getFirstName();

        String getLastName();

        String getRole();

        Boolean getActive();
    }

    interface DoctorProfile {

        String getSystemId();

        String getSpecialization();

        String getVerificationStatus();

        Boolean getActive();
    }

    interface FacilityRow {

        Integer getFacilityId();

        String getName();

        Boolean getActive();
    }
}
