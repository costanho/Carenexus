package com.carenexus.api.scheduling.repository;

import com.carenexus.api.scheduling.model.DoctorAvailability;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DoctorAvailabilityRepository extends JpaRepository<DoctorAvailability, Integer> {

    /** The whole template of a doctor, ordered by weekday, then start time (at most 42 rows). */
    List<DoctorAvailability> findByDoctorSystemIdOrderByDayOfWeekAscStartTimeAscAvailabilityIdAsc(String doctorSystemId);

    /** Active blocks of one weekday, ordered by start time. */
    List<DoctorAvailability> findByDoctorSystemIdAndDayOfWeekAndIsActiveTrueOrderByStartTimeAscAvailabilityIdAsc(
            String doctorSystemId, Integer dayOfWeek);

    /** True when the doctor has stored at least one block (active or not): the template is in force. */
    boolean existsByDoctorSystemId(String doctorSystemId);

    /**
     * Removes the whole template of a doctor in one statement. A bulk delete runs at once, so the blocks inserted
     * right after it in the same transaction never meet the old rows in the unique key.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM DoctorAvailability a WHERE a.doctorSystemId = :doctorSystemId")
    int deleteAllOfDoctor(@Param("doctorSystemId") String doctorSystemId);

    /**
     * Transaction-scoped advisory lock on one doctor's template, so two concurrent replacements run one after the
     * other instead of failing on the unique key. Released at commit or rollback.
     */
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtext('carenexus.availability'), hashtext(:doctorSystemId))",
            nativeQuery = true)
    Integer lockTemplate(@Param("doctorSystemId") String doctorSystemId);
}
