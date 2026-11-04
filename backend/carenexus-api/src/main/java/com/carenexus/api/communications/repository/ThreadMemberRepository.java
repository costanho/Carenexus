package com.carenexus.api.communications.repository;

import com.carenexus.api.communications.model.ThreadMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ThreadMemberRepository extends JpaRepository<ThreadMember, Long> {

    String ACTIVE = " tm.leftAt IS NULL AND tm.removedAt IS NULL ";

    Optional<ThreadMember> findByThreadIdAndSystemId(Integer threadId, String systemId);

    @Query("SELECT tm FROM ThreadMember tm WHERE tm.threadId = :threadId AND" + ACTIVE + "ORDER BY tm.threadMemberId ASC")
    List<ThreadMember> findActiveByThreadId(@Param("threadId") Integer threadId);

    @Query("SELECT tm FROM ThreadMember tm WHERE tm.threadId IN :threadIds AND" + ACTIVE + "ORDER BY tm.threadId ASC, tm.threadMemberId ASC")
    List<ThreadMember> findActiveByThreadIds(@Param("threadIds") Collection<Integer> threadIds);

    /** Active memberships of a user in conversations about this patient. */
    @Query("SELECT tm FROM ThreadMember tm WHERE tm.systemId = :systemId AND" + ACTIVE
            + "AND tm.threadId IN (SELECT t.threadId FROM MessageThread t WHERE t.patientSystemId = :patientSystemId)")
    List<ThreadMember> findActiveByUserAndPatient(@Param("systemId") String systemId,
                                                 @Param("patientSystemId") String patientSystemId);

    /** [threadId, patientSystemId] for every active membership of the user. */
    @Query("SELECT tm.threadId, t.patientSystemId FROM ThreadMember tm, MessageThread t WHERE t.threadId = tm.threadId"
            + " AND tm.systemId = :systemId AND" + ACTIVE)
    List<Object[]> findActiveThreadPatientsForUser(@Param("systemId") String systemId);
}
