package com.carenexus.api.caregiveraccess.job;

import com.carenexus.api.caregiveraccess.repository.AccessGrantRepository;
import com.carenexus.api.caregiveraccess.repository.AccessPeopleRepository.Person;
import com.carenexus.api.caregiveraccess.service.AccessNotifications;
import com.carenexus.api.caregiveraccess.service.AccessPeople;
import com.carenexus.api.common.job.JobSupport;
import com.carenexus.api.core.model.DependentAccess;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Job {@value #JOB_NAME} (daily by default, {@code carenexus.jobs.access-expiry-notice.interval-ms}): tells the
 * caregiver of every unrevoked grant that ends within the next {@value #NOTICE_DAYS} days that it expires soon
 * (category ACCESS_CHANGE, so the caregiver's accessChangeAlerts setting can switch it off). The dedupe key holds the
 * expiry date, so each expiry date is announced once and a new date after an extension is announced again.
 * Grants are read in id order in batches of {@value #BATCH}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccessExpiryNoticeJob {

    static final String JOB_NAME = "access-expiry-notice";
    static final int BATCH = 200;
    static final int NOTICE_DAYS = 3;

    private final AccessGrantRepository accessGrantRepository;
    private final AccessPeople people;
    private final AccessNotifications notifications;
    private final JobSupport jobSupport;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${carenexus.jobs.access-expiry-notice.interval-ms:86400000}")
    public void tick() {
        if (!jobSupport.enabled()) {
            return;
        }
        try {
            int noticed = sweepOnce();
            if (noticed > 0) {
                log.info("Sent {} caregiver access expiry notice(s)", noticed);
            }
        } catch (RuntimeException e) {
            log.error("Access expiry notice failed: {}", e.getClass().getSimpleName());
        }
    }

    /** One run at the application clock's clinic time. @return number of notices stored */
    public int sweepOnce() {
        return sweepOnce(LocalDateTime.now(clock));
    }

    /**
     * One run at {@code now} (clinic wall-clock), exclusive across API instances.
     *
     * @return number of notices stored (a repeat run for the same expiry dates stores none)
     */
    public int sweepOnce(LocalDateTime now) {
        LocalDateTime until = now.plusDays(NOTICE_DAYS);
        AtomicInteger noticed = new AtomicInteger();
        jobSupport.runExclusive(JOB_NAME, () -> {
            int afterAccessId = 0;
            while (true) {
                List<DependentAccess> batch = accessGrantRepository.findExpiringBetween(now, until, afterAccessId,
                        PageRequest.of(0, BATCH));
                if (batch.isEmpty()) {
                    break;
                }
                Map<String, Person> patients = people.byId(batch.stream().map(DependentAccess::getPatientSystemId).toList());
                for (DependentAccess grant : batch) {
                    if (notifications.accessExpiringSoon(grant, AccessPeople.fullName(patients.get(grant.getPatientSystemId())))) {
                        noticed.incrementAndGet();
                    }
                }
                afterAccessId = batch.get(batch.size() - 1).getAccessId();
                if (batch.size() < BATCH) {
                    break;
                }
            }
        });
        return noticed.get();
    }
}
