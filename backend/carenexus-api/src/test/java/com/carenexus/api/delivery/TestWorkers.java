package com.carenexus.api.delivery;

import com.carenexus.api.common.job.JobSupport;
import com.carenexus.api.delivery.config.DeliveryProperties;
import com.carenexus.api.delivery.email.EmailSender;
import com.carenexus.api.delivery.job.OutboundMessageWorker;
import com.carenexus.api.delivery.push.PushSender;
import com.carenexus.api.delivery.repository.OutboundMessageRepository;
import com.carenexus.api.delivery.repository.PushDeviceRepository;
import com.carenexus.api.delivery.sms.SmsSender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

/**
 * Builds extra OutboundMessageWorker instances for tests: the application's repositories and transaction manager,
 * with the test's own clock, senders, properties or meter registry. The application's worker bean is not replaced.
 */
public final class TestWorkers {

    private TestWorkers() {
    }

    /** The application's senders and properties, driven by {@code clock}. */
    public static OutboundMessageWorker withClock(ApplicationContext context, Clock clock) {
        return build(context, context.getBean(EmailSender.class), context.getBean(SmsSender.class),
                context.getBean(PushSender.class), context.getBean(DeliveryProperties.class), clock,
                context.getBean(MeterRegistry.class));
    }

    public static OutboundMessageWorker build(ApplicationContext context, EmailSender email, SmsSender sms,
                                              PushSender push, DeliveryProperties properties, Clock clock,
                                              MeterRegistry meterRegistry) {
        return new OutboundMessageWorker(context.getBean(JobSupport.class),
                context.getBean(OutboundMessageRepository.class), context.getBean(PushDeviceRepository.class),
                email, sms, push, properties, context.getBean(PlatformTransactionManager.class), clock,
                context.getBean(ObjectMapper.class), meterRegistry);
    }
}
