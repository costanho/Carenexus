package com.carenexus.api.delivery.job;

import com.carenexus.api.common.job.JobSupport;
import com.carenexus.api.delivery.OutboundMessageService;
import com.carenexus.api.delivery.TestWorkers;
import com.carenexus.api.delivery.config.DeliveryProperties;
import com.carenexus.api.delivery.email.EmailSender;
import com.carenexus.api.delivery.model.OutboundMessage;
import com.carenexus.api.delivery.push.PushSender;
import com.carenexus.api.delivery.repository.OutboundMessageRepository;
import com.carenexus.api.delivery.repository.PushDeviceRepository;
import com.carenexus.api.delivery.sms.SmsSender;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The scheduled entry point of the outbox worker: it follows the global job switch (off in tests, where
 * sweepOnce() is called directly), counts its runs as job "outbound" and reads its interval from
 * carenexus.jobs.outbound.interval-ms.
 */
public class OutboundMessageWorkerScheduleTest extends CommittedIntegrationTest {

    @Autowired
    private OutboundMessageWorker worker;

    @Autowired
    private OutboundMessageService outbound;

    @Autowired
    private OutboundMessageRepository messages;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MeterRegistry meterRegistry;

    private Integer queue() {
        return outbound.queueEmail("thomas@example.com", patient.getSystemId(), "delivery-test",
                Map.of("name", "Thomas"), null);
    }

    private double runs(String outcome) {
        var counter = meterRegistry.find("carenexus.jobs.runs").tags("job", "outbound", "outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void theScheduledTick_doesNothingWhileJobsAreSwitchedOff() {
        Integer id = queue();

        worker.tick();

        assertEquals(OutboundMessage.PENDING, messages.findById(id).orElseThrow().getStatus(),
                "carenexus.jobs.enabled=false in the test profile");
    }

    @Test
    void theScheduledTick_sendsDueRowsWhenJobsAreOn() {
        // The same worker on an instance where jobs are enabled.
        JobSupport enabledJobs = new JobSupport(true, context.getBean(JdbcTemplate.class),
                context.getBean(PlatformTransactionManager.class), context.getBeanProvider(MeterRegistry.class));
        OutboundMessageWorker enabled = new OutboundMessageWorker(enabledJobs, messages,
                context.getBean(PushDeviceRepository.class), context.getBean(EmailSender.class),
                context.getBean(SmsSender.class), context.getBean(PushSender.class),
                context.getBean(DeliveryProperties.class), context.getBean(PlatformTransactionManager.class),
                context.getBean(Clock.class), objectMapper, meterRegistry);
        Integer id = queue();
        double before = runs("success");

        enabled.tick();

        assertEquals(OutboundMessage.SENT, messages.findById(id).orElseThrow().getStatus());
        assertEquals(before + 1, runs("success"), "each pass is counted as a run of job 'outbound'");
    }

    @Test
    void sweepOnce_isCountedAsARunOfTheOutboundJob() {
        double before = runs("success");

        assertEquals(0, TestWorkers.withClock(context, context.getBean(Clock.class)).sweepOnce());

        assertEquals(before + 1, runs("success"));
        assertEquals("outbound", OutboundMessageWorker.JOB_NAME);
    }

    @Test
    void theIntervalComesFromTheJobsNamespace_defaultFiveSeconds() throws Exception {
        Scheduled scheduled = OutboundMessageWorker.class.getDeclaredMethod("tick").getAnnotation(Scheduled.class);

        assertEquals("${carenexus.jobs.outbound.interval-ms:5000}", scheduled.fixedDelayString());
    }
}
