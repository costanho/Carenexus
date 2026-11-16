package com.carenexus.api.calls.service;

import com.carenexus.api.calls.config.LiveKitProperties;
import com.carenexus.api.calls.model.CallJob;
import com.carenexus.api.calls.model.CallParticipant;
import com.carenexus.api.calls.model.CallSession;
import com.carenexus.api.calls.provider.CallProvider;
import com.carenexus.api.calls.provider.CallProviderException;
import com.carenexus.api.calls.repository.CallJobRepository;
import com.carenexus.api.calls.repository.CallParticipantRepository;
import com.carenexus.api.calls.repository.CallSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Durable provider job queue: enqueue (at most one open job per type/room/identity), claim with
 * FOR UPDATE SKIP LOCKED, execute outside any DB transaction, then record the outcome with bounded retries.
 * Logs carry job ids and outcomes only (never room names together with identities, never tokens).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallJobService {

    static final int CLAIM_BATCH_SIZE = 10;
    static final Duration STALE_LOCK = Duration.ofMinutes(2);
    private static final int MAX_ERROR_LENGTH = 500;

    private final CallJobRepository callJobRepository;
    private final CallSessionRepository callSessionRepository;
    private final CallParticipantRepository callParticipantRepository;
    private final CallProvider callProvider;
    private final LiveKitProperties liveKitProperties;
    private final PlatformTransactionManager transactionManager;

    // ==================== ENQUEUE (inside the caller's transaction) ====================

    /** CREATE_ROOM for the session's current room, first attempt at {@code notBefore}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public CallJob enqueueCreateRoom(CallSession session, Instant notBefore) {
        return enqueue(CallJob.CREATE_ROOM, session.getCallSessionId(), null, session.getRoomName(), null, notBefore);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public CallJob enqueueDeleteRoom(CallSession session, String roomName) {
        return enqueue(CallJob.DELETE_ROOM, session.getCallSessionId(), null, roomName, null, Instant.now());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public CallJob enqueueRemoveParticipant(CallSession session, CallParticipant participant) {
        return enqueue(CallJob.REMOVE_PARTICIPANT, session.getCallSessionId(), participant.getCallParticipantId(),
                session.getRoomName(), participant.getProviderIdentity(), Instant.now());
    }

    /** Remove an identity seen in a room that has no matching admitted participant (webhook). */
    @Transactional(propagation = Propagation.MANDATORY)
    public CallJob enqueueRemoveIdentity(CallSession session, Long participantId, String roomName, String identity) {
        return enqueue(CallJob.REMOVE_PARTICIPANT, session.getCallSessionId(), participantId, roomName, identity,
                Instant.now());
    }

    /**
     * Insert a PENDING job unless an open job with the same (type, room, identity) exists. An existing PENDING job
     * is pulled forward when the new request is due earlier.
     */
    CallJob enqueue(String jobType, Long sessionId, Long participantId, String roomName, String identity,
                    Instant notBefore) {
        Instant due = notBefore == null ? Instant.now() : notBefore;
        Optional<CallJob> open = callJobRepository.findOpen(jobType, roomName, identity == null ? "" : identity);
        if (open.isPresent()) {
            CallJob existing = open.get();
            if (CallJob.PENDING.equals(existing.getStatus()) && existing.getNextAttemptAt().isAfter(due)) {
                existing.setNextAttemptAt(due);
                return callJobRepository.save(existing);
            }
            return existing;
        }
        CallJob job = CallJob.builder()
                .jobType(jobType)
                .status(CallJob.PENDING)
                .callSessionId(sessionId)
                .callParticipantId(participantId)
                .roomName(roomName)
                .providerIdentity(identity)
                .attempts(0)
                .maxAttempts(CallJob.maxAttemptsFor(jobType))
                .nextAttemptAt(due)
                .build();
        return callJobRepository.save(job);
    }

    /** Cancel PENDING CREATE_ROOM jobs of the session (cancel, reschedule, expiry). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelPendingCreateRoom(Long sessionId) {
        List<CallJob> pending = callJobRepository.findByCallSessionIdAndJobTypeAndStatus(sessionId,
                CallJob.CREATE_ROOM, CallJob.PENDING);
        Instant now = Instant.now();
        for (CallJob job : pending) {
            job.setStatus(CallJob.CANCELLED);
            job.setCompletedAt(now);
            callJobRepository.save(job);
        }
    }

    // ==================== WORKER ====================

    /** One worker pass: release stale locks, claim due jobs, execute each. Returns the number of jobs claimed. */
    public int runOnce() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            int released = callJobRepository.releaseStaleRunning(Instant.now().minus(STALE_LOCK));
            if (released > 0) {
                log.warn("Released {} stale call job lock(s)", released);
            }
        });
        List<Long> claimed = tx.execute(status -> {
            Instant now = Instant.now();
            List<CallJob> due = callJobRepository.lockDueJobs(now, CLAIM_BATCH_SIZE);
            for (CallJob job : due) {
                job.setStatus(CallJob.RUNNING);
                job.setLockedAt(now);
                callJobRepository.save(job);
            }
            return due.stream().map(CallJob::getCallJobId).toList();
        });
        if (claimed == null) {
            return 0;
        }
        for (Long jobId : claimed) {
            try {
                execute(jobId);
            } catch (RuntimeException e) {
                log.error("Call job {} failed unexpectedly: {}", jobId, e.getClass().getSimpleName());
            }
        }
        return claimed.size();
    }

    /** Execute one claimed (RUNNING) job: decide, call the provider outside a transaction, record the outcome. */
    void execute(Long jobId) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CallJob job = tx.execute(status -> prepare(jobId));
        if (job == null) {
            return;
        }
        try {
            callProviderFor(job);
        } catch (RuntimeException e) {
            String reason = e instanceof CallProviderException
                    ? e.getMessage()
                    : "Provider call failed: " + e.getClass().getSimpleName();
            boolean retryable = !(e instanceof CallProviderException cpe) || cpe.isRetryable();
            tx.executeWithoutResult(status -> recordFailure(jobId, reason, retryable));
            return;
        }
        tx.executeWithoutResult(status -> recordSuccess(jobId));
    }

    /** Returns the job when the provider must be called, null when it was completed/cancelled here. */
    private CallJob prepare(Long jobId) {
        CallJob job = callJobRepository.findById(jobId).orElse(null);
        if (job == null || !CallJob.RUNNING.equals(job.getStatus())) {
            return null;
        }
        if (CallJob.CREATE_ROOM.equals(job.getJobType())) {
            CallSession session = callSessionRepository.findById(job.getCallSessionId()).orElse(null);
            if (session == null || !CallSession.PREPARING.equals(session.getStatus())
                    || !job.getRoomName().equals(session.getRoomName())) {
                complete(job, CallJob.CANCELLED);
                return null;
            }
        }
        return job;
    }

    private void callProviderFor(CallJob job) {
        switch (job.getJobType()) {
            case CallJob.CREATE_ROOM -> {
                CallSession session = callSessionRepository.findById(job.getCallSessionId()).orElse(null);
                Instant closes = session != null ? session.getJoinClosesAt() : null;
                callProvider.createRoom(job.getRoomName(), emptyTimeoutSeconds(closes),
                        liveKitProperties.getMaxParticipants());
            }
            case CallJob.DELETE_ROOM -> callProvider.deleteRoom(job.getRoomName());
            case CallJob.REMOVE_PARTICIPANT -> callProvider.removeParticipant(job.getRoomName(), job.getProviderIdentity());
            default -> throw new CallProviderException("Unknown job type", false);
        }
    }

    /** emptyTimeout = clamp(seconds until join_closes_at, 300, 86400). */
    static int emptyTimeoutSeconds(Instant joinClosesAt) {
        long seconds = joinClosesAt == null ? 300 : Duration.between(Instant.now(), joinClosesAt).getSeconds();
        return (int) Math.max(300, Math.min(86400, seconds));
    }

    private void recordSuccess(Long jobId) {
        CallJob job = callJobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }
        complete(job, CallJob.SUCCEEDED);
        switch (job.getJobType()) {
            case CallJob.CREATE_ROOM -> {
                CallSession session = callSessionRepository.findForUpdateById(job.getCallSessionId()).orElse(null);
                if (session != null && CallSession.PREPARING.equals(session.getStatus())
                        && job.getRoomName().equals(session.getRoomName())) {
                    session.setStatus(CallSession.READY);
                    session.setRoomCreatedAt(Instant.now());
                    session.setLastError(null);
                    callSessionRepository.save(session);
                } else if (session != null) {
                    // The session moved on (rescheduled/cancelled) while the room was being created: clean it up.
                    enqueue(CallJob.DELETE_ROOM, session.getCallSessionId(), null, job.getRoomName(), null, Instant.now());
                }
            }
            case CallJob.REMOVE_PARTICIPANT -> clearRemovalPending(job);
            default -> {
                // DELETE_ROOM: nothing else to record
            }
        }
        log.info("Call job {} ({}) succeeded", jobId, job.getJobType());
    }

    private void recordFailure(Long jobId, String reason, boolean retryable) {
        CallJob job = callJobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }
        int attempts = job.getAttempts() + 1;
        job.setAttempts(attempts);
        job.setLastError(truncate(reason));
        job.setLockedAt(null);
        if (!retryable || attempts >= job.getMaxAttempts()) {
            job.setStatus(CallJob.FAILED);
            job.setCompletedAt(Instant.now());
            callJobRepository.save(job);
            if (CallJob.CREATE_ROOM.equals(job.getJobType())) {
                callSessionRepository.findForUpdateById(job.getCallSessionId())
                        .filter(s -> CallSession.PREPARING.equals(s.getStatus()) && job.getRoomName().equals(s.getRoomName()))
                        .ifPresent(s -> {
                            s.setStatus(CallSession.FAILED);
                            s.setLastError(truncate(reason));
                            callSessionRepository.save(s);
                        });
            }
            log.warn("Call job {} ({}) failed permanently after {} attempt(s)", jobId, job.getJobType(), attempts);
            return;
        }
        job.setStatus(CallJob.PENDING);
        job.setNextAttemptAt(Instant.now().plusSeconds(CallJob.backoffSeconds(attempts)));
        callJobRepository.save(job);
        log.info("Call job {} ({}) attempt {} failed; retrying", jobId, job.getJobType(), attempts);
    }

    private void clearRemovalPending(CallJob job) {
        if (job.getCallParticipantId() == null) {
            return;
        }
        callParticipantRepository.findById(job.getCallParticipantId()).ifPresent(p -> {
            if (Boolean.TRUE.equals(p.getRemovalPending())) {
                p.setRemovalPending(false);
                callParticipantRepository.save(p);
            }
        });
    }

    private void complete(CallJob job, String status) {
        job.setStatus(status);
        job.setLockedAt(null);
        job.setCompletedAt(Instant.now());
        callJobRepository.save(job);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }
}
