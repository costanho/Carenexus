package com.carenexus.api.account.datarights.job;

import com.carenexus.api.account.datarights.model.AccountRequest;
import com.carenexus.api.account.datarights.repository.AccountRequestRepository;
import com.carenexus.api.common.job.JobSupport;
import com.carenexus.api.storage.FileStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Job {@code data-export-expiry}: every READY export whose download window ended becomes COMPLETED and its file is
 * deleted. Rows are claimed with {@code FOR UPDATE SKIP LOCKED} in batches of at most 200 inside
 * {@link JobSupport#runExclusive}; the files are deleted after that transaction committed, so a rolled-back run never
 * leaves a READY row without its file. Idempotent on re-run.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataExportExpirySweeper {

    public static final String JOB_NAME = "data-export-expiry";
    static final int BATCH_SIZE = 200;

    private final JobSupport jobs;
    private final AccountRequestRepository requests;
    private final FileStorage fileStorage;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${carenexus.jobs.data-export-expiry.interval-ms:3600000}")
    void tick() {
        if (jobs.enabled()) {
            sweepOnce();
        }
    }

    /**
     * Expires one batch.
     *
     * @return the number of exports moved to COMPLETED
     */
    public int sweepOnce() {
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        List<String> keys = new ArrayList<>();
        int[] expired = {0};
        jobs.runExclusive(JOB_NAME, () -> {
            List<Integer> ids = requests.claimExpiredExports(now, BATCH_SIZE);
            for (AccountRequest export : requests.findAllById(ids)) {
                if (!export.hasStatus(AccountRequest.STATUS_READY)) {
                    continue;
                }
                export.setStatus(AccountRequest.STATUS_COMPLETED);
                export.setUpdatedAt(now);
                if (export.getResultRef() != null) {
                    keys.add(export.getResultRef());
                }
                expired[0]++;
            }
            requests.flush();
        });
        for (String key : keys) {
            try {
                fileStorage.delete(key);
            } catch (RuntimeException e) {
                log.warn("Could not delete an expired data export file ({})", e.getClass().getSimpleName());
            }
        }
        if (expired[0] > 0) {
            log.info("Expired {} data export(s)", expired[0]);
        }
        return expired[0];
    }
}
