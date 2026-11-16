package com.carenexus.api.calls.repository;

import com.carenexus.api.calls.model.CallJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface CallJobRepository extends JpaRepository<CallJob, Long> {

    /**
     * Claim jobs due at {@code now}; concurrent workers skip rows another worker holds. Call inside a transaction.
     * {@code now} comes from the same JVM clock that wrote {@code next_attempt_at}, so a clock difference between the
     * application host and the database cannot make a job run early or late.
     */
    @Query(value = "SELECT * FROM call_jobs WHERE status = 'PENDING' AND next_attempt_at <= :now " +
            "ORDER BY next_attempt_at LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<CallJob> lockDueJobs(@Param("now") Instant now, @Param("limit") int limit);

    /** Jobs left RUNNING by a crashed worker go back to PENDING. */
    @Modifying
    @Query(value = "UPDATE call_jobs SET status = 'PENDING', locked_at = NULL, updated_at = now() " +
            "WHERE status = 'RUNNING' AND locked_at < :cutoff", nativeQuery = true)
    int releaseStaleRunning(@Param("cutoff") Instant cutoff);

    @Query("SELECT j FROM CallJob j WHERE j.jobType = :jobType AND j.roomName = :roomName " +
            "AND COALESCE(j.providerIdentity, '') = :identity AND j.status IN ('PENDING','RUNNING')")
    Optional<CallJob> findOpen(@Param("jobType") String jobType, @Param("roomName") String roomName,
                               @Param("identity") String identity);

    List<CallJob> findByCallSessionIdAndJobTypeAndStatus(Long callSessionId, String jobType, String status);

    boolean existsByCallSessionIdAndJobTypeInAndStatusIn(Long callSessionId, Collection<String> jobTypes,
                                                         Collection<String> statuses);
}
