package com.carenexus.api.scheduling.repository;

import com.carenexus.api.scheduling.model.DoctorTimeOff;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface DoctorTimeOffRepository extends JpaRepository<DoctorTimeOff, Integer> {

    Optional<DoctorTimeOff> findByTimeOffIdAndDoctorSystemId(Integer timeOffId, String doctorSystemId);

    /** Every range of the doctor (past ones included); order comes from the pageable. */
    Page<DoctorTimeOff> findByDoctorSystemId(String doctorSystemId, Pageable pageable);

    /** Running and upcoming ranges of the doctor ({@code endsAt} after {@code now}); order comes from the pageable. */
    Page<DoctorTimeOff> findByDoctorSystemIdAndEndsAtAfter(String doctorSystemId, LocalDateTime now, Pageable pageable);

    /** Ranges of the doctor that overlap {@code [start, end)}, soonest first. */
    @Query("SELECT t FROM DoctorTimeOff t WHERE t.doctorSystemId = :doctorSystemId "
            + "AND t.startsAt < :end AND t.endsAt > :start ORDER BY t.startsAt ASC, t.timeOffId ASC")
    List<DoctorTimeOff> findOverlapping(@Param("doctorSystemId") String doctorSystemId,
                                        @Param("start") LocalDateTime start,
                                        @Param("end") LocalDateTime end);

    /** Number of ranges of the doctor that overlap {@code [start, end)}. */
    @Query("SELECT COUNT(t) FROM DoctorTimeOff t WHERE t.doctorSystemId = :doctorSystemId "
            + "AND t.startsAt < :end AND t.endsAt > :start")
    long countOverlapping(@Param("doctorSystemId") String doctorSystemId,
                          @Param("start") LocalDateTime start,
                          @Param("end") LocalDateTime end);
}
