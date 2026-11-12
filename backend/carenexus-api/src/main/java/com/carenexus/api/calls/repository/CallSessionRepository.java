package com.carenexus.api.calls.repository;

import com.carenexus.api.calls.model.CallSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CallSessionRepository extends JpaRepository<CallSession, Long> {

    Optional<CallSession> findByAppointmentId(Integer appointmentId);

    /** Row-locked read for state changes (serializes start/end/admit/sync on one session). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM CallSession s WHERE s.appointmentId = :appointmentId")
    Optional<CallSession> findForUpdateByAppointmentId(@Param("appointmentId") Integer appointmentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM CallSession s WHERE s.callSessionId = :id")
    Optional<CallSession> findForUpdateById(@Param("id") Long id);

    List<CallSession> findByAppointmentIdIn(Collection<Integer> appointmentIds);

    Optional<CallSession> findByRoomName(String roomName);

    @Query("SELECT s.callSessionId FROM CallSession s WHERE s.status IN :statuses AND s.joinClosesAt < :now")
    List<Long> findIdsClosedBefore(@Param("statuses") Collection<String> statuses, @Param("now") Instant now);

    @Query("SELECT s.callSessionId FROM CallSession s WHERE s.status = :status")
    List<Long> findIdsByStatus(@Param("status") String status);
}
