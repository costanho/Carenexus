package com.carenexus.api.calls.repository;

import com.carenexus.api.calls.model.CallParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CallParticipantRepository extends JpaRepository<CallParticipant, Long> {

    List<CallParticipant> findByCallSessionIdAndStatusIn(Long callSessionId, Collection<String> statuses);

    List<CallParticipant> findByCallSessionIdOrderByCallParticipantIdAsc(Long callSessionId);

    Optional<CallParticipant> findFirstByCallSessionIdAndSystemIdAndStatusIn(Long callSessionId, String systemId,
                                                                             Collection<String> statuses);

    Optional<CallParticipant> findFirstByCallSessionIdAndSystemIdOrderByCallParticipantIdDesc(Long callSessionId,
                                                                                              String systemId);

    Optional<CallParticipant> findByCallParticipantIdAndCallSessionId(Long callParticipantId, Long callSessionId);

    Optional<CallParticipant> findByProviderIdentity(String providerIdentity);

    /** [participantId, sessionId] of the user's rows with one of {@code statuses} (ids only, nothing cached). */
    @Query("SELECT p.callParticipantId, p.callSessionId FROM CallParticipant p " +
            "WHERE p.systemId = :systemId AND p.status IN :statuses")
    List<Object[]> findIdsForUser(@Param("systemId") String systemId,
                                  @Param("statuses") Collection<String> statuses);

    /** [participantId, sessionId] of the user's rows in calls of the patient's appointments. */
    @Query("SELECT p.callParticipantId, p.callSessionId FROM CallParticipant p, CallSession s, Appointment a " +
            "WHERE p.callSessionId = s.callSessionId AND s.appointmentId = a.appointmentId " +
            "AND a.patientSystemId = :patientSystemId AND p.systemId = :systemId AND p.status IN :statuses")
    List<Object[]> findIdsForUserInPatientCalls(@Param("systemId") String systemId,
                                                @Param("patientSystemId") String patientSystemId,
                                                @Param("statuses") Collection<String> statuses);

    boolean existsByCallSessionIdAndRemovalPendingTrue(Long callSessionId);
}
