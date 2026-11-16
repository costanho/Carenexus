package com.carenexus.api.delivery;

import com.carenexus.api.common.ratelimit.MutableClock;
import com.carenexus.api.delivery.config.DeliveryProperties;
import com.carenexus.api.delivery.email.DisabledEmailSender;
import com.carenexus.api.delivery.email.EmailMessage;
import com.carenexus.api.delivery.job.OutboundMessageWorker;
import com.carenexus.api.delivery.model.OutboundMessage;
import com.carenexus.api.delivery.model.PushDevice;
import com.carenexus.api.delivery.push.DisabledPushSender;
import com.carenexus.api.delivery.push.PushMessage;
import com.carenexus.api.delivery.repository.OutboundMessageRepository;
import com.carenexus.api.delivery.repository.PushDeviceRepository;
import com.carenexus.api.delivery.sms.DisabledSmsSender;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OutboundMessageWorker against PostgreSQL, committed like production: local providers, retry with exponential
 * backoff, FAILED after the last attempt, SKIPPED rows, row claiming and the outcome counters. Failing providers
 * are scripted test senders handed to a worker the test builds itself.
 */
public class OutboundMessageWorkerTest extends CommittedIntegrationTest {

    private static final Map<String, String> MODEL = Map.of("name", "Thomas", "link", "http://localhost:8081/x");

    @Autowired
    private OutboundMessageService outbound;

    @Autowired
    private OutboundMessageRepository messages;

    @Autowired
    private PushDeviceRepository pushDevices;

    @Autowired
    private OutboundMessageWorker worker;

    @Autowired
    private DeliveryProperties properties;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private DataSource dataSource;

    private RecordingSenders.Email email;
    private RecordingSenders.Sms sms;
    private RecordingSenders.Push push;
    private MutableClock clock;
    private MeterRegistry registry;
    private OutboundMessageWorker scripted;

    @BeforeEach
    void buildScriptedWorker() {
        email = new RecordingSenders.Email();
        sms = new RecordingSenders.Sms();
        push = new RecordingSenders.Push();
        // Ahead of the application clock, so rows queued by the test are due.
        clock = new MutableClock(Instant.now().plusSeconds(120).truncatedTo(ChronoUnit.MICROS));
        registry = new SimpleMeterRegistry();
        scripted = TestWorkers.build(context, email, sms, push, properties, clock, registry);
    }

    private OutboundMessage row(Integer id) {
        return messages.findById(id).orElseThrow();
    }

    private Integer queueEmail() {
        return outbound.queueEmail("thomas@example.com", patient.getSystemId(), "delivery-test", MODEL, null);
    }

    private void registerDevice(String systemId, String platform, String token, Instant lastSeen) {
        pushDevices.saveAndFlush(PushDevice.builder().systemId(systemId).platform(platform).token(token)
                .lastSeenAt(lastSeen).createdAt(lastSeen).build());
    }

    private double counted(String channel, String outcome) {
        Counter counter = registry.find(OutboundMessageWorker.OUTCOME_METRIC)
                .tags("channel", channel, "outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    // ==================== LOCAL PROVIDERS (the application's worker bean) ====================

    @Test
    void localProviders_markEveryDueRowSentOnTheFirstSweep() {
        registerDevice(patient.getSystemId(), "ANDROID", "device-token-0001", Instant.now());
        Integer mail = queueEmail();
        Integer text = outbound.queueSms("+263771234567", patient.getSystemId(), "delivery-test", MODEL, null);
        Integer note = outbound.queuePush(patient.getSystemId(), "Title", "Text", Map.of("actionUrl", "/x"), null);
        String bodyBefore = row(mail).getBody();

        assertEquals(3, worker.sweepOnce());

        for (Integer id : List.of(mail, text, note)) {
            OutboundMessage message = row(id);
            assertEquals(OutboundMessage.SENT, message.getStatus(), message.getChannel());
            assertEquals("local", message.getProvider());
            assertEquals("local-" + id, message.getProviderMessageId());
            assertEquals(1, message.getAttempts());
            assertNotNull(message.getSentAt());
            assertNull(message.getLastError());
        }
        assertEquals(bodyBefore, row(mail).getBody(), "the rendered body stays in the row");
        assertEquals(0, worker.sweepOnce(), "sent rows are not picked up again");
    }

    @Test
    void scheduledRunsAreOffInTests_andAnEmptyOutboxIsANoOp() {
        assertEquals(0, worker.sweepOnce());
        assertEquals(0, scripted.sweepOnce());
    }

    // ==================== SENDING ====================

    @Test
    void theSenderReceivesTheStoredMessage_andTheRowKeepsTheProviderAnswer() {
        Integer id = queueEmail();
        Integer textId = outbound.queueSms("+263771234567", patient.getSystemId(), "delivery-test", MODEL, null);

        assertEquals(2, scripted.sweepOnce());

        EmailMessage sent = email.sent.get(0);
        assertEquals("thomas@example.com", sent.to());
        assertEquals("CareNexus delivery test for Thomas", sent.subject());
        assertEquals(row(id).getBody(), sent.textBody());
        assertEquals("delivery-test", sent.templateId());
        assertEquals(String.valueOf(id), sent.messageKey(), "the row id is the provider idempotency key");
        assertFalse(sent.toString().contains("thomas@example.com"), "toString masks the address");
        assertFalse(sent.toString().contains("Hello Thomas"), "toString never prints the text");

        OutboundMessage message = row(id);
        assertEquals(OutboundMessage.SENT, message.getStatus());
        assertEquals("test-mail", message.getProvider());
        assertEquals("mail-" + id, message.getProviderMessageId());
        assertEquals(clock.instant(), message.getSentAt());

        assertEquals("+263771234567", sms.sent.get(0).to());
        assertEquals(row(textId).getBody(), sms.sent.get(0).text());
        assertEquals("test-sms", row(textId).getProvider());
        assertEquals(1.0, counted("EMAIL", "sent"));
        assertEquals(1.0, counted("SMS", "sent"));
    }

    @Test
    void push_goesToEveryRegisteredDeviceOfTheRecipient_newestFirst() {
        Instant now = Instant.now();
        registerDevice(patient.getSystemId(), "IOS", "older-device-token", now.minusSeconds(60));
        registerDevice(patient.getSystemId(), "ANDROID", "newer-device-token", now);
        registerDevice(otherPatient.getSystemId(), "WEB", "someone-elses-token", now);
        Integer id = outbound.queuePush(patient.getSystemId(), "Appointment confirmed", "Open CareNexus.",
                Map.of("actionUrl", "/dashboard/patient?section=my-appointments&id=412"), "N:88:PUSH");

        assertEquals(1, scripted.sweepOnce());

        PushMessage sent = push.sent.get(0);
        assertEquals(patient.getSystemId(), sent.recipientSystemId());
        assertEquals(List.of("newer-device-token", "older-device-token"),
                sent.targets().stream().map(target -> target.token()).toList());
        assertEquals(List.of("ANDROID", "IOS"), sent.targets().stream().map(target -> target.platform()).toList());
        assertEquals("Appointment confirmed", sent.title());
        assertEquals("Open CareNexus.", sent.body());
        assertEquals(Map.of("actionUrl", "/dashboard/patient?section=my-appointments&id=412"), sent.data());
        assertEquals(String.valueOf(id), sent.messageKey());
        assertFalse(sent.toString().contains("device-token"), "toString never prints a token");
        assertEquals(OutboundMessage.SENT, row(id).getStatus());
        assertEquals("push-" + id, row(id).getProviderMessageId());
        assertEquals(1.0, counted("PUSH", "sent"));
    }

    // ==================== RETRY, BACKOFF, FAILED ====================

    @Test
    void aFailingProvider_isRetriedWithExponentialBackoff_andTheRowFailsAfterTheLastAttempt() {
        email.script.failAlways(new DeliveryException("Provider timeout", true));
        Integer id = queueEmail();
        assertEquals(5, row(id).getMaxAttempts());

        long[] expectedBackoffSeconds = {30, 60, 120, 240};
        for (int attempt = 1; attempt <= 4; attempt++) {
            assertEquals(1, scripted.sweepOnce(), "attempt " + attempt);

            OutboundMessage message = row(id);
            assertEquals(OutboundMessage.PENDING, message.getStatus(), "attempt " + attempt);
            assertEquals(attempt, message.getAttempts());
            assertEquals("Provider timeout", message.getLastError());
            assertEquals("test-mail", message.getProvider());
            assertNull(message.getSentAt());
            long backoff = expectedBackoffSeconds[attempt - 1];
            assertEquals(clock.instant().plusSeconds(backoff), message.getNextAttemptAt(), "backoff after attempt " + attempt);

            assertEquals(0, scripted.sweepOnce(), "not due right after a failure");
            clock.advance(Duration.ofSeconds(backoff - 1));
            assertEquals(0, scripted.sweepOnce(), "one second before the retry time");
            clock.advance(Duration.ofSeconds(1));
        }

        assertEquals(1, scripted.sweepOnce(), "fifth and last attempt");
        OutboundMessage failed = row(id);
        assertEquals(OutboundMessage.FAILED, failed.getStatus());
        assertEquals(5, failed.getAttempts());
        assertEquals("Provider timeout", failed.getLastError());
        assertNull(failed.getSentAt(), "sent_at is stamped for SENT and SKIPPED only");
        assertNull(failed.getProviderMessageId());
        assertEquals(5, email.attempts.size());
        assertTrue(email.sent.isEmpty());

        clock.advance(Duration.ofDays(1));
        assertEquals(0, scripted.sweepOnce(), "FAILED is terminal");
        assertEquals(5, email.attempts.size());
        assertEquals(4.0, counted("EMAIL", "retried"));
        assertEquals(1.0, counted("EMAIL", "failed"));
        assertEquals(0.0, counted("EMAIL", "sent"));
    }

    @Test
    void backoffDoublesFromThirtySecondsAndIsCappedAtOneHour() {
        assertEquals(30, OutboundMessage.backoffSeconds(1));
        assertEquals(60, OutboundMessage.backoffSeconds(2));
        assertEquals(120, OutboundMessage.backoffSeconds(3));
        assertEquals(240, OutboundMessage.backoffSeconds(4));
        assertEquals(1920, OutboundMessage.backoffSeconds(7));
        assertEquals(3600, OutboundMessage.backoffSeconds(8));
        assertEquals(3600, OutboundMessage.backoffSeconds(40));
        assertEquals(30, OutboundMessage.backoffSeconds(0));
    }

    @Test
    void aTransientFailure_isFollowedByASuccessfulRetry() {
        email.script.failNext(new DeliveryException("HTTP 503", true)).failNext(new DeliveryException("HTTP 503", true));
        Integer id = queueEmail();

        assertEquals(1, scripted.sweepOnce());
        clock.advance(Duration.ofSeconds(30));
        assertEquals(1, scripted.sweepOnce());
        clock.advance(Duration.ofSeconds(60));
        assertEquals(1, scripted.sweepOnce());

        OutboundMessage message = row(id);
        assertEquals(OutboundMessage.SENT, message.getStatus());
        assertEquals(3, message.getAttempts());
        assertNull(message.getLastError(), "the error of an earlier attempt is cleared");
        assertEquals(clock.instant(), message.getSentAt());
        assertEquals(1, email.sent.size());
        assertEquals(2.0, counted("EMAIL", "retried"));
        assertEquals(1.0, counted("EMAIL", "sent"));
    }

    @Test
    void aNonRetryableFailure_marksTheRowFailedAtOnce() {
        sms.script.failAlways(new DeliveryException("Rejected by provider", false));
        Integer id = outbound.queueSms("+263771234567", patient.getSystemId(), "delivery-test", MODEL, null);

        assertEquals(1, scripted.sweepOnce());

        OutboundMessage message = row(id);
        assertEquals(OutboundMessage.FAILED, message.getStatus());
        assertEquals(1, message.getAttempts());
        assertEquals("Rejected by provider", message.getLastError());
        assertEquals(1.0, counted("SMS", "failed"));
        clock.advance(Duration.ofHours(2));
        assertEquals(0, scripted.sweepOnce());
    }

    @Test
    void anUnexpectedSenderError_isRetried_andOnlyItsTypeIsRecorded() {
        email.script.failNext(new IllegalStateException("could not reach thomas@example.com: Hello Thomas"));
        Integer id = queueEmail();

        assertEquals(1, scripted.sweepOnce());

        OutboundMessage message = row(id);
        assertEquals(OutboundMessage.PENDING, message.getStatus());
        assertEquals(1, message.getAttempts());
        assertEquals("IllegalStateException", message.getLastError(),
                "an unexpected exception text may hold the address or the message: only the type is kept");
    }

    @Test
    void theReasonIsStoredAsOneShortLine() {
        email.script.failAlways(new DeliveryException("Provider   said:\n" + "x".repeat(400), false));
        Integer id = queueEmail();

        scripted.sweepOnce();

        String error = row(id).getLastError();
        assertEquals(300, error.length());
        assertTrue(error.startsWith("Provider said: xxx"));
    }

    @Test
    void oneFailingMessage_doesNotHoldBackTheOthers() {
        email.script.failNext(new DeliveryException("HTTP 500", true));
        Integer first = queueEmail();
        Integer second = queueEmail();

        assertEquals(2, scripted.sweepOnce());

        assertEquals(OutboundMessage.PENDING, row(first).getStatus());
        assertEquals(1, row(first).getAttempts());
        assertEquals(OutboundMessage.SENT, row(second).getStatus());
    }

    // ==================== SKIPPED ====================

    @Test
    void aDisabledProvider_marksRowsSkipped_withoutCallingTheSender() {
        RecordingSenders.Email unconfigured = new RecordingSenders.Email("ses", false);
        OutboundMessageWorker disabled = TestWorkers.build(context, new DisabledEmailSender(), new DisabledSmsSender(),
                new DisabledPushSender(), properties, clock, registry);
        OutboundMessageWorker withoutCredentials = TestWorkers.build(context, unconfigured, sms, push, properties,
                clock, registry);
        registerDevice(patient.getSystemId(), "ANDROID", "device-token-0001", Instant.now());
        Integer mail = queueEmail();
        Integer text = outbound.queueSms("+263771234567", patient.getSystemId(), "delivery-test", MODEL, null);
        Integer note = outbound.queuePush(patient.getSystemId(), "Title", "Text", null, null);

        assertEquals(3, disabled.sweepOnce());

        for (Integer id : List.of(mail, text, note)) {
            OutboundMessage message = row(id);
            assertEquals(OutboundMessage.SKIPPED, message.getStatus(), message.getChannel());
            assertEquals("Provider disabled", message.getLastError());
            assertEquals("disabled", message.getProvider());
            assertEquals(0, message.getAttempts(), "a skip is not an attempt");
            assertEquals(clock.instant(), message.getSentAt(), "sent_at is stamped for SKIPPED");
            assertNull(message.getProviderMessageId());
        }
        assertEquals(1.0, counted("EMAIL", "skipped"));
        assertEquals(1.0, counted("SMS", "skipped"));
        assertEquals(1.0, counted("PUSH", "skipped"));
        assertEquals(0, disabled.sweepOnce(), "SKIPPED is terminal");

        // A real provider id that reports itself not configured behaves the same.
        Integer later = queueEmail();
        assertEquals(1, withoutCredentials.sweepOnce());
        assertEquals(OutboundMessage.SKIPPED, row(later).getStatus());
        assertEquals("Provider disabled", row(later).getLastError());
        assertEquals("ses", row(later).getProvider());
        assertTrue(unconfigured.attempts.isEmpty());
    }

    @Test
    void aRowWithoutAnAddress_isSkipped() {
        Integer mail = outbound.queueEmail(null, patient.getSystemId(), "delivery-test", MODEL, null);
        Integer text = outbound.queueSms(" ", patient.getSystemId(), "delivery-test", MODEL, null);

        assertEquals(2, scripted.sweepOnce());

        for (Integer id : List.of(mail, text)) {
            OutboundMessage message = row(id);
            assertEquals(OutboundMessage.SKIPPED, message.getStatus());
            assertEquals("No recipient address", message.getLastError());
            assertEquals(0, message.getAttempts());
            assertNotNull(message.getSentAt());
        }
        assertTrue(email.attempts.isEmpty());
        assertTrue(sms.sent.isEmpty());
    }

    @Test
    void aPushForARecipientWithoutADevice_isSkipped() {
        registerDevice(otherPatient.getSystemId(), "WEB", "someone-elses-token", Instant.now());
        Integer id = outbound.queuePush(patient.getSystemId(), "Title", "Text", null, null);

        assertEquals(1, scripted.sweepOnce());

        OutboundMessage message = row(id);
        assertEquals(OutboundMessage.SKIPPED, message.getStatus());
        assertEquals("No registered device", message.getLastError());
        assertEquals(0, message.getAttempts());
        assertTrue(push.sent.isEmpty());
    }

    // ==================== CLAIMING ====================

    @Test
    void aRowHeldByAnotherWorker_isLeftAlone() throws Exception {
        Integer held = queueEmail();
        Integer free = queueEmail();

        // "Another worker": a second connection holding the row lock the claim query asks for.
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement lock = other.prepareStatement(
                    "SELECT message_id FROM outbound_messages WHERE message_id = ? FOR UPDATE")) {
                lock.setInt(1, held);
                lock.execute();
            }

            assertEquals(1, scripted.sweepOnce(), "only the free row is handled; the held one is skipped, not waited for");
            assertEquals(OutboundMessage.SENT, row(free).getStatus());

            other.rollback();
        }
        assertEquals(OutboundMessage.PENDING, row(held).getStatus());
        assertEquals(0, row(held).getAttempts());

        assertEquals(1, scripted.sweepOnce(), "picked up once the other worker let go");
        assertEquals(OutboundMessage.SENT, row(held).getStatus());
        assertEquals(2, email.sent.size(), "each message was sent exactly once");
    }

    @Test
    void aSweepHandlesAtMostOneBatch_oldestDueFirst() {
        DeliveryProperties smallBatch = new DeliveryProperties();
        smallBatch.setBatchSize(2);
        OutboundMessageWorker small = TestWorkers.build(context, email, sms, push, smallBatch, clock, registry);
        Integer first = queueEmail();
        Integer second = queueEmail();
        Integer third = queueEmail();

        assertEquals(2, small.sweepOnce());
        assertEquals(OutboundMessage.SENT, row(first).getStatus());
        assertEquals(OutboundMessage.SENT, row(second).getStatus());
        assertEquals(OutboundMessage.PENDING, row(third).getStatus());

        assertEquals(1, small.sweepOnce());
        assertEquals(OutboundMessage.SENT, row(third).getStatus());
    }

    @Test
    void maxAttemptsComesFromConfiguration_andIsStoredOnTheRow() {
        assertEquals(5, properties.getMaxAttempts(), "default");
        assertEquals(5, properties.safeMaxAttempts());
        assertEquals(200, properties.safeBatchSize());

        DeliveryProperties odd = new DeliveryProperties();
        odd.setMaxAttempts(0);
        odd.setBatchSize(5000);
        assertEquals(1, odd.safeMaxAttempts());
        assertEquals(200, odd.safeBatchSize(), "a pass never takes more than 200 rows");

        // A row queued with one attempt fails on its first failure.
        Integer id = queueEmail();
        jdbc.update("UPDATE outbound_messages SET max_attempts = 1 WHERE message_id = ?", id);
        email.script.failAlways(new DeliveryException("HTTP 500", true));
        assertEquals(1, scripted.sweepOnce());
        assertEquals(OutboundMessage.FAILED, row(id).getStatus());
    }
}
