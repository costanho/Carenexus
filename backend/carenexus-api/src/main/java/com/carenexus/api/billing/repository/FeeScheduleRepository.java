package com.carenexus.api.billing.repository;

import com.carenexus.api.billing.model.FeeSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Consultation fees. There are at most three rows per doctor and three clinic default rows (one per appointment
 * type), so the lists are not paged.
 */
@Repository
public interface FeeScheduleRepository extends JpaRepository<FeeSchedule, Integer> {

    /** The clinic default rows (no doctor), active or not. */
    List<FeeSchedule> findByDoctorSystemIdIsNull();

    /** The clinic default row of one appointment type, active or not. */
    Optional<FeeSchedule> findByDoctorSystemIdIsNullAndAppointmentType(String appointmentType);

    /** The override rows of one doctor, active or not. */
    List<FeeSchedule> findByDoctorSystemId(String doctorSystemId);

    /** The override row of one doctor for one appointment type, active or not. */
    Optional<FeeSchedule> findByDoctorSystemIdAndAppointmentType(String doctorSystemId, String appointmentType);

    /** The override rows of several doctors (batch fee resolution for a list). */
    List<FeeSchedule> findByDoctorSystemIdIn(Collection<String> doctorSystemIds);

    /**
     * Every active row that can price a visit of this doctor: the doctor's overrides and the clinic defaults.
     * The caller applies the precedence (override, then default, then zero).
     */
    @Query("SELECT f FROM FeeSchedule f WHERE f.isActive = true "
            + "AND (f.doctorSystemId IS NULL OR f.doctorSystemId = :doctorSystemId)")
    List<FeeSchedule> findActiveForDoctor(@Param("doctorSystemId") String doctorSystemId);
}
