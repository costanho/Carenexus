package com.carenexus.api.delivery.job;

import com.carenexus.api.common.job.JobSupport;
import com.carenexus.api.delivery.DeliveryChannel;
import com.carenexus.api.delivery.DeliveryException;
import com.carenexus.api.delivery.OutboundMessageService;
import com.carenexus.api.delivery.SendResult;
import com.carenexus.api.delivery.config.DeliveryProperties;
import com.carenexus.api.delivery.email.EmailMessage;
import com.carenexus.api.delivery.email.EmailSender;
import com.carenexus.api.delivery.model.OutboundMessage;
import com.carenexus.api.delivery.model.PushDevice;
import com.carenexus.api.delivery.push.PushMessage;
import com.carenexus.api.delivery.push.PushSender;
import com.carenexus.api.delivery.push.PushTarget;
import com.carenexus.api.delivery.repository.OutboundMessageRepository;
import com.carenexus.api.delivery.repository.PushDeviceRepository;
import com.carenexus.api.delivery.sms.SmsMessage;
import com.carenexus.api.delivery.sms.SmsSender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Sends the outbox (job {@code outbound}, every {@code carenexus.jobs.outbound.interval-ms}, default 5 s).
 *
 * One pass takes up to {@code carenexus.delivery.batch-size} due rows (PENDING with {@code next_attempt_at} reached)
 * and handles each in its own transaction: the row is claimed with {@code FOR UPDATE SKIP LOCKED}, handed to the
 * channel's sender and updated. A provider call therefore never holds more than one row, and a crash in the middle
 * of a pass leaves the remaining rows PENDING.
 *
 * <pre>
 * accepted by the sender                -> SENT     (sent_at, provider, provider_message_id, attempts + 1)
 * retryable failure, attempts left      -> PENDING  (attempts + 1, last_error, next_attempt_at = now + backoff)
 * last attempt or non-retryable failure -> FAILED
 * provider disabled or not configured   -> SKIPPED  ("Provider disabled")
 * EMAIL or SMS without an address       -> SKIPPED  ("No recipient address")
 * PUSH recipient without a device       -> SKIPPED  ("No registered device")
 * </pre>
 * Backoff after failed attempt n is {@code min(30 s * 2^(n-1), 1 h)}. Delivery is at-least-once: senders receive
 * the row id as {@code messageKey} so a provider with idempotency keys can drop a repeat.
 *
 * Outcomes are counted in Micrometer: {@code carenexus.outbound.messages{channel, outcome=sent|retried|failed|skipped}}.
 * Scheduled runs are off when {@code carenexus.jobs.enabled=false} (tests call {@link #sweepOnce()}).
 */
@Slf4j
@Component
public class OutboundMessageWorker {

    public static final String JOB_NAME = "outbound";
    public static final String OUTCOME_METRIC = "carenexus.outbound.messages";

    public static final String SENT = "sent";
    public static final String RETRIED = "retried";
    public static final String FAILED = "failed";
    public static final String SKIPPED = "skipped";

    public static final String PROVIDER_DISABLED = "Provider disabled";
    public static final String NO_ADDRESS = "No recipient address";
    public static final String NO_DEVICE = "No registered device";

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private final JobSupport jobs;
    private final OutboundMessageRepository messages;
    private final PushDeviceRepository pushDevices;
    private final EmailSender emailSender;
    private final SmsSender smsSender;
    private final PushSender pushSender;
    private final DeliveryProperties properties;
    private final TransactionTemplate perMessage;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Autowired
    public OutboundMessageWorker(JobSupport jobs, OutboundMessageRepository messages, PushDeviceRepository pushDevices,
                                 EmailSender emailSender, SmsSender smsSender, PushSender pushSender,
                                 DeliveryProperties properties, PlatformTransactionManager transactionManager,
                                 Clock clock, ObjectMapper objectMapper, ObjectProvider<MeterRegistry> meterRegistry) {
        this(jobs, messages, pushDevices, emailSender, smsSender, pushSender, properties, transactionManager, clock,
                objectMapper, meterRegistry.getIfAvailable());
    }

    /** For tests that drive the worker with their own senders or clock; {@code meterRegistry} may be null. */
    public OutboundMessageWorker(JobSupport jobs, OutboundMessageRepository messages, PushDeviceRepository pushDevices,
                                 EmailSender emailSender, SmsSender smsSender, PushSender pushSender,
                                 DeliveryProperties properties, PlatformTransactionManager transactionManager,
                                 Clock clock, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.jobs = jobs;
        this.messages = messages;
        this.pushDevices = pushDevices;
        this.emailSender = emailSender;
        this.smsSender = smsSender;
        this.pushSender = pushSender;
        this.properties = properties;
        this.perMessage = new TransactionTemplate(transactionManager);
        this.perMessage.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${carenexus.jobs.outbound.interval-ms:5000}",
            initialDelayString = "${carenexus.jobs.outbound.interval-ms:5000}")
    void tick() {
        if (!jobs.enabled()) {
            return;
        }
        try {
            sweepOnce();
        } catch (RuntimeException e) {
            // JobSupport already counted and logged the failed run; the next tick tries again.
            log.debug("Outbound worker pass ended early: {}", e.getClass().getName());
        }
    }

    /**
     * One pass over the due rows.
     *
     * @return how many messages reached an outcome (sent, retried, failed or skipped); 0 when nothing was due or
     *         another API instance is running the job
     */
    public int sweepOnce() {
        AtomicInteger handled = new AtomicInteger();
        jobs.runExclusive(JOB_NAME, () -> handled.set(processDue()));
        return handled.get();
    }

    private int processDue() {
        Instant now = clock.instant();
        List<Integer> due = messages.findDueIds(now, properties.safeBatchSize());
        int handled = 0;
        for (Integer id : due) {
            try {
                Outcome outcome = perMessage.execute(tx -> processOne(id, now));
                if (outcome != null) {
                    handled++;
                    count(outcome);
                }
            } catch (RuntimeException e) {
                // The row's transaction rolled back, so it is still PENDING and due: the next pass picks it up.
                log.error("Outbound message {} could not be processed: {}", id, e.getClass().getName());
            }
        }
        return handled;
    }

    private record Outcome(String channel, String outcome) {
    }

    /** Runs in its own transaction. Null when the row was already handled or is held by another worker. */
    private Outcome processOne(Integer id, Instant now) {
        Optional<OutboundMessage> claimed = messages.lockDue(id, now);
        if (claimed.isEmpty()) {
            return null;
        }
        OutboundMessage message = claimed.get();
        String outcome = deliver(message, now);
        messages.save(message);
        if (SENT.equals(outcome) || SKIPPED.equals(outcome)) {
            log.debug("Outbound message {} {}: channel={} provider={}", id, outcome, message.getChannel(),
                    message.getProvider());
        } else {
            log.warn("Outbound message {} {}: channel={} provider={} attempt={}/{} reason={}", id, outcome,
                    message.getChannel(), message.getProvider(), message.getAttempts(), message.getMaxAttempts(),
                    message.getLastError());
        }
        return new Outcome(message.getChannel(), outcome);
    }

    private String deliver(OutboundMessage message, Instant now) {
        String key = String.valueOf(message.getMessageId());
        switch (message.getChannel()) {
            case DeliveryChannel.EMAIL -> {
                String provider = emailSender.providerName();
                if (!OutboundMessageService.available(provider, emailSender.isConfigured())) {
                    return skip(message, provider, PROVIDER_DISABLED, now);
                }
                if (isBlank(message.getRecipientAddress())) {
                    return skip(message, provider, NO_ADDRESS, now);
                }
                EmailMessage email = new EmailMessage(message.getRecipientAddress(), message.getSubject(),
                        message.getBody(), message.getTemplateId(), key);
                return attempt(message, provider, now, () -> emailSender.send(email));
            }
            case DeliveryChannel.SMS -> {
                String provider = smsSender.providerName();
                if (!OutboundMessageService.available(provider, smsSender.isConfigured())) {
                    return skip(message, provider, PROVIDER_DISABLED, now);
                }
                if (isBlank(message.getRecipientAddress())) {
                    return skip(message, provider, NO_ADDRESS, now);
                }
                SmsMessage sms = new SmsMessage(message.getRecipientAddress(), message.getBody(),
                        message.getTemplateId(), key);
                return attempt(message, provider, now, () -> smsSender.send(sms));
            }
            default -> {
                String provider = pushSender.providerName();
                if (!OutboundMessageService.available(provider, pushSender.isConfigured())) {
                    return skip(message, provider, PROVIDER_DISABLED, now);
                }
                List<PushTarget> targets = isBlank(message.getRecipientSystemId()) ? List.of()
                        : pushDevices.findBySystemIdNewestFirst(message.getRecipientSystemId(),
                                PageRequest.of(0, PushDevice.MAX_DEVICES_PER_USER)).stream()
                        .map(device -> new PushTarget(device.getPlatform(), device.getToken()))
                        .toList();
                if (targets.isEmpty()) {
                    return skip(message, provider, NO_DEVICE, now);
                }
                Map<String, String> data;
                try {
                    data = message.getData() == null ? Map.of() : objectMapper.readValue(message.getData(), STRING_MAP);
                } catch (JsonProcessingException e) {
                    return failure(message, provider, "Invalid data payload", false, now);
                }
                PushMessage push = new PushMessage(message.getRecipientSystemId(), targets, message.getSubject(),
                        message.getBody(), data, key);
                return attempt(message, provider, now, () -> pushSender.send(push));
            }
        }
    }

    private String attempt(OutboundMessage message, String provider, Instant now, Supplier<SendResult> send) {
        SendResult result;
        try {
            result = send.get();
        } catch (DeliveryException e) {
            return failure(message, provider, e.getMessage(), e.isRetryable(), now);
        } catch (RuntimeException e) {
            // An adapter bug or an unexpected client error: keep the type only, the text may hold the address.
            return failure(message, provider, e.getClass().getSimpleName(), true, now);
        }
        message.setStatus(OutboundMessage.SENT);
        message.setAttempts(message.getAttempts() + 1);
        message.setSentAt(stamp(now));
        message.setProvider(clip(provider, OutboundMessage.MAX_PROVIDER_LENGTH));
        message.setProviderMessageId(result == null ? null
                : clip(result.providerMessageId(), OutboundMessage.MAX_PROVIDER_MESSAGE_ID_LENGTH));
        message.setLastError(null);
        return SENT;
    }

    private String failure(OutboundMessage message, String provider, String reason, boolean retryable, Instant now) {
        int attempts = message.getAttempts() + 1;
        message.setAttempts(attempts);
        message.setProvider(clip(provider, OutboundMessage.MAX_PROVIDER_LENGTH));
        message.setLastError(shortReason(reason));
        if (!retryable || attempts >= message.getMaxAttempts()) {
            message.setStatus(OutboundMessage.FAILED);
            return FAILED;
        }
        message.setNextAttemptAt(stamp(now.plusSeconds(OutboundMessage.backoffSeconds(attempts))));
        return RETRIED;
    }

    private String skip(OutboundMessage message, String provider, String reason, Instant now) {
        message.setStatus(OutboundMessage.SKIPPED);
        message.setSentAt(stamp(now));
        message.setProvider(clip(provider, OutboundMessage.MAX_PROVIDER_LENGTH));
        message.setLastError(reason);
        return SKIPPED;
    }

    private void count(Outcome outcome) {
        if (meterRegistry != null) {
            meterRegistry.counter(OUTCOME_METRIC, "channel", outcome.channel(), "outcome", outcome.outcome()).increment();
        }
    }

    private static Instant stamp(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    private static String shortReason(String reason) {
        String text = reason == null || reason.isBlank() ? "Delivery failed" : reason.replaceAll("\\s+", " ").trim();
        return clip(text, OutboundMessage.MAX_ERROR_LENGTH);
    }

    private static String clip(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
