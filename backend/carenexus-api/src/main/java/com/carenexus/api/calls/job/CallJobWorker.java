package com.carenexus.api.calls.job;

import com.carenexus.api.calls.service.CallJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs due provider jobs (CREATE_ROOM, DELETE_ROOM, REMOVE_PARTICIPANT). Safe with several API instances:
 * jobs are claimed with FOR UPDATE SKIP LOCKED.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "carenexus.calls.worker-enabled", havingValue = "true", matchIfMissing = true)
public class CallJobWorker {

    private final CallJobService callJobService;

    @Scheduled(fixedDelayString = "${carenexus.calls.worker-interval-ms:5000}",
            initialDelayString = "${carenexus.calls.worker-interval-ms:5000}")
    public void run() {
        try {
            callJobService.runOnce();
        } catch (RuntimeException e) {
            log.error("Call job worker pass failed: {}", e.getClass().getSimpleName());
        }
    }
}
