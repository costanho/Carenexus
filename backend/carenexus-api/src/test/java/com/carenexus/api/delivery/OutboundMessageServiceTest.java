package com.carenexus.api.delivery;

import com.carenexus.api.common.ratelimit.MutableClock;
import com.carenexus.api.delivery.job.OutboundMessageWorker;
import com.carenexus.api.delivery.model.OutboundMessage;
import com.carenexus.api.delivery.repository.OutboundMessageRepository;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OutboundMessageService against PostgreSQL, committed like production: what a queue call stores, that a rolled-back
 * caller sends nothing, dedupe keys, notBefore and the fail-fast rules.
 */
public class OutboundMessageServiceTest extends CommittedIntegrationTest {

    private static final String LINK = "http://localhost:8081/auth/reset-password?token=Zq3vJ0aP7c1mT5uK";

    @Autowired
    private OutboundMessageService outbound;

    @Autowired
    private OutboundMessageRepository messages;

    @Autowired
    private OutboundMessageWorker worker;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    @Autowired
    private ApplicationContext context;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
    }

    private static Map<String, String> model() {
        return Map.of("name", "Thomas", "link", LINK);
    }

    private OutboundMessage row(Integer id) {
        return messages.findById(id).orElseThrow();
    }

    private int rows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbound_messages", Integer.class);
    }

    // ==================== WHAT IS STORED ====================

    @Test
    void queueEmail_storesAPendingRowRenderedFromTheTemplate() {
        Instant before = clock.instant().truncatedTo(ChronoUnit.MICROS);

        Integer id = outbound.queueEmail("  thomas@example.com ", patient.getSystemId(), "delivery-test", model(),
                "TEST:1");

        OutboundMessage message = row(id);
        assertEquals("EMAIL", message.getChannel());
        assertEquals(patient.getSystemId(), message.getRecipientSystemId());
        assertEquals("thomas@example.com", message.getRecipientAddress(), "the address is trimmed");
        assertEquals("delivery-test", message.getTemplateId());
        assertEquals("CareNexus delivery test for Thomas", message.getSubject());
        assertTrue(message.getBody().startsWith("Hello Thomas,\n"), message.getBody());
        assertTrue(message.getBody().contains(LINK), "the body is final as soon as the row exists");
        assertNull(message.getData());
        assertNull(message.getNotificationId());
        assertEquals("TEST:1", message.getDedupeKey());
        assertEquals(OutboundMessage.PENDING, message.getStatus());
        assertEquals(0, message.getAttempts());
        assertEquals(5, message.getMaxAttempts());
        assertNull(message.getLastError());
        assertNull(message.getProvider());
        assertNull(message.getProviderMessageId());
        assertNull(message.getSentAt());
        assertFalse(message.getCreatedAt().isBefore(before));
        assertEquals(message.getCreatedAt(), message.getNextAttemptAt(), "due at once");
    }

    @Test
    void queueSms_ignoresTheSubjectLine() {
        Integer id = outbound.queueSms("+263771234567", patient.getSystemId(), "delivery-test", model(), null);

        OutboundMessage message = row(id);
        assertEquals("SMS", message.getChannel());
        assertEquals("+263771234567", message.getRecipientAddress());
        assertEquals("delivery-test", message.getTemplateId());
        assertNull(message.getSubject());
        assertTrue(message.getBody().startsWith("Hello Thomas,"));
        assertNull(message.getDedupeKey());
    }

    @Test
    void queuePush_storesTitleTextAndDataWithoutAnAddress() throws Exception {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("actionUrl", "/dashboard/patient?section=my-appointments&id=412");
        data.put("type", "APPOINTMENT");
        data.put("ignored", null);

        Integer id = outbound.queuePush(patient.getSystemId(), "Appointment confirmed",
                "Your appointment was booked. Open CareNexus for the details.", data, "TEST:PUSH:1");

        OutboundMessage message = row(id);
        assertEquals("PUSH", message.getChannel());
        assertEquals(patient.getSystemId(), message.getRecipientSystemId());
        assertNull(message.getRecipientAddress(), "devices are resolved when the message is sent");
        assertNull(message.getTemplateId());
        assertEquals("Appointment confirmed", message.getSubject());
        assertEquals("Your appointment was booked. Open CareNexus for the details.", message.getBody());
        assertEquals(Map.of("actionUrl", "/dashboard/patient?section=my-appointments&id=412", "type", "APPOINTMENT"),
                objectMapper.readValue(message.getData(), Map.class));
    }

    @Test
    void queue_generalForm_carriesNotificationIdAndNormalisesTheChannel() {
        Integer id = outbound.queue(new OutboundRequest(" email ", "thomas@example.com", patient.getSystemId(),
                "delivery-test", model(), null, null, Map.of("dropped", "for email"), "N:88:EMAIL", 88, null));

        OutboundMessage message = row(id);
        assertEquals("EMAIL", message.getChannel());
        assertEquals(88, message.getNotificationId());
        assertNull(message.getData(), "data is the push payload only");
    }

    @Test
    void anAddressWithoutAnAccount_andAnEmailWithoutATemplate_areAccepted() {
        Integer id = outbound.queue(new OutboundRequest("EMAIL", "new.address@example.com", null, null, null,
                "Confirm your new email address", "Open the link to confirm.", null, null, null, null));

        OutboundMessage message = row(id);
        assertNull(message.getRecipientSystemId());
        assertNull(message.getTemplateId());
        assertEquals("Confirm your new email address", message.getSubject());
        assertEquals("Open the link to confirm.", message.getBody());
    }

    @Test
    void aMissingAddressIsStored_soTheWorkerCanSkipIt() {
        Integer id = outbound.queueEmail("  ", patient.getSystemId(), "delivery-test", model(), null);

        assertNull(row(id).getRecipientAddress());
        assertEquals(OutboundMessage.PENDING, row(id).getStatus());
    }

    // ==================== TRANSACTIONS ====================

    @Test
    void aQueueCallInARolledBackTransaction_sendsNothing() {
        AtomicReference<Integer> queued = new AtomicReference<>();

        tx.executeWithoutResult(status -> {
            queued.set(outbound.queueEmail("thomas@example.com", patient.getSystemId(), "delivery-test", model(),
                    "ROLLED_BACK:1"));
            assertNotNull(queued.get());
            assertEquals(1, rows(), "visible inside the caller's transaction");
            status.setRollbackOnly();
        });

        assertEquals(0, rows(), "the row disappeared with the caller's transaction");
        assertTrue(messages.findById(queued.get()).isEmpty());
        assertEquals(0, worker.sweepOnce(), "nothing to send");

        // The key is free again: the same change, retried and committed, queues its message.
        Integer second = outbound.queueEmail("thomas@example.com", patient.getSystemId(), "delivery-test", model(),
                "ROLLED_BACK:1");
        assertEquals(1, rows());
        assertEquals(1, worker.sweepOnce());
        assertEquals(OutboundMessage.SENT, row(second).getStatus());
    }

    @Test
    void theRowCommitsWithTheCallersTransaction() {
        Integer id = tx.execute(status -> {
            jdbc.update("UPDATE users SET first_name = 'Changed' WHERE system_id = ?", patient.getSystemId());
            return outbound.queueEmail("thomas@example.com", patient.getSystemId(), "delivery-test", model(), null);
        });

        assertEquals(OutboundMessage.PENDING, row(id).getStatus());
        assertEquals("Changed", jdbc.queryForObject("SELECT first_name FROM users WHERE system_id = ?", String.class,
                patient.getSystemId()));
    }

    // ==================== DEDUPE ====================

    @Test
    void aRepeatedDedupeKey_returnsTheSameId_andStoresNothingNew() {
        Integer first = outbound.queueEmail("thomas@example.com", patient.getSystemId(), "delivery-test", model(),
                "PASSWORD_RESET:17");
        Integer second = outbound.queueEmail("someone.else@example.com", doctor.getSystemId(), "delivery-test",
                Map.of("name", "Amara"), "PASSWORD_RESET:17");
        Integer third = outbound.queuePush(patient.getSystemId(), "Title", "Text", null, "PASSWORD_RESET:17");

        assertEquals(first, second);
        assertEquals(first, third, "the key is unique across the whole outbox, whatever the channel or recipient");
        assertEquals(1, rows());
        OutboundMessage message = row(first);
        assertEquals("thomas@example.com", message.getRecipientAddress(), "the existing row is left unchanged");
        assertTrue(message.getBody().startsWith("Hello Thomas,"));
    }

    @Test
    void aRepeatedDedupeKeyInsideOneTransaction_doesNotAbortIt() {
        Integer[] ids = new Integer[2];

        tx.executeWithoutResult(status -> {
            ids[0] = outbound.queueSms("+263771234567", patient.getSystemId(), "delivery-test", model(), "N:5:SMS");
            ids[1] = outbound.queueSms("+263771234567", patient.getSystemId(), "delivery-test", model(), "N:5:SMS");
            // The transaction is still usable after the duplicate.
            jdbc.update("UPDATE users SET first_name = 'StillOpen' WHERE system_id = ?", patient.getSystemId());
        });

        assertEquals(ids[0], ids[1]);
        assertEquals(1, rows());
        assertEquals("StillOpen", jdbc.queryForObject("SELECT first_name FROM users WHERE system_id = ?",
                String.class, patient.getSystemId()));
    }

    @Test
    void aRepeatAfterTheMessageWasSent_stillReturnsTheSameId() {
        Integer first = outbound.queueEmail("thomas@example.com", patient.getSystemId(), "delivery-test", model(), "K:1");
        assertEquals(1, worker.sweepOnce());
        assertEquals(OutboundMessage.SENT, row(first).getStatus());

        assertEquals(first, outbound.queueEmail("thomas@example.com", patient.getSystemId(), "delivery-test", model(), "K:1"));
        assertEquals(1, rows());
        assertEquals(0, worker.sweepOnce(), "a sent message is never sent again");
    }

    @Test
    void concurrentCallersWithTheSameDedupeKey_shareOneRow() throws Exception {
        int callers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    // Each caller in its own transaction, as separate requests would be.
                    return tx.execute(status -> outbound.queueEmail("thomas@example.com", patient.getSystemId(),
                            "delivery-test", model(), "CONCURRENT:1"));
                }));
            }
            start.countDown();

            Set<Integer> ids = new HashSet<>();
            for (Future<Integer> result : results) {
                ids.add(result.get(30, TimeUnit.SECONDS));
            }
            assertEquals(1, ids.size(), "every caller got the id of the one stored row");
            assertEquals(1, rows());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void messagesWithoutADedupeKey_neverConflict() {
        Integer first = outbound.queueEmail("thomas@example.com", patient.getSystemId(), "delivery-test", model(), null);
        Integer second = outbound.queueEmail("thomas@example.com", patient.getSystemId(), "delivery-test", model(), " ");

        assertNotEquals(first, second);
        assertEquals(2, rows());
        assertNull(row(second).getDedupeKey(), "a blank key is stored as no key");
    }

    // ==================== NOT BEFORE ====================

    @Test
    void notBefore_delaysTheFirstAttempt() {
        // Clinic wall-clock time two hours from now, to the second.
        LocalDateTime notBefore = LocalDateTime.now(clock).plusHours(2).truncatedTo(ChronoUnit.SECONDS);
        Integer id = outbound.queue(new OutboundRequest("EMAIL", "thomas@example.com", patient.getSystemId(),
                "delivery-test", model(), null, null, null, "QUIET:1", 7, notBefore));

        OutboundMessage message = row(id);
        assertEquals(notBefore.atZone(clock.getZone()).toInstant(), message.getNextAttemptAt(),
                "the clinic wall-clock time is stored as an instant");
        assertTrue(message.getNextAttemptAt().isAfter(message.getCreatedAt()));

        assertEquals(0, worker.sweepOnce(), "not due yet");
        message = row(id);
        assertEquals(OutboundMessage.PENDING, message.getStatus());
        assertEquals(0, message.getAttempts());

        // The same worker with a clock just short of, then just past, the requested time.
        MutableClock later = new MutableClock(message.getNextAttemptAt().minusSeconds(1));
        OutboundMessageWorker laterWorker = TestWorkers.withClock(context, later);
        assertEquals(0, laterWorker.sweepOnce(), "one second early");
        later.advance(Duration.ofSeconds(1));
        assertEquals(1, laterWorker.sweepOnce());
        assertEquals(OutboundMessage.SENT, row(id).getStatus());
    }

    @Test
    void notBeforeInThePast_isDueNow() {
        Integer id = outbound.queue(new OutboundRequest("SMS", "+263771234567", patient.getSystemId(),
                "delivery-test", model(), null, null, null, null, null, LocalDateTime.now(clock).minusDays(1)));

        OutboundMessage message = row(id);
        assertEquals(message.getCreatedAt(), message.getNextAttemptAt());
        assertEquals(1, worker.sweepOnce());
    }

    // ==================== FAIL FAST ====================

    @Test
    void anUnknownTemplate_failsTheCaller_andStoresNothing() {
        assertThrows(IllegalStateException.class, () -> outbound.queueEmail("thomas@example.com",
                patient.getSystemId(), "delivery-no-such-template", model(), "UNKNOWN:1"));
        assertEquals(0, rows());

        // Inside a caller's transaction the failure rolls the caller back as well.
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE users SET first_name = 'NotKept' WHERE system_id = ?", patient.getSystemId());
            outbound.queueSms("+263771234567", patient.getSystemId(), "delivery-no-such-template", model(), null);
        }));
        assertNotEquals("NotKept", jdbc.queryForObject("SELECT first_name FROM users WHERE system_id = ?",
                String.class, patient.getSystemId()));
    }

    @Test
    void malformedRequests_areRefused() {
        assertThrows(IllegalArgumentException.class, () -> outbound.queue(null));
        assertThrows(IllegalArgumentException.class, () -> outbound.queue(new OutboundRequest("FAX",
                "thomas@example.com", null, "delivery-test", model(), null, null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> outbound.queuePush(null, "Title", "Text", null, null),
                "a push needs the recipient");
        assertThrows(IllegalArgumentException.class, () -> outbound.queuePush(patient.getSystemId(), "Title", " ", null, null),
                "a push needs a text");
        assertThrows(IllegalArgumentException.class, () -> outbound.queue(new OutboundRequest("EMAIL",
                "thomas@example.com", null, null, null, null, "Text without a subject", null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> outbound.queueEmail("thomas@example.com\nBcc: x@example.com",
                null, "delivery-test", model(), null), "no control characters in an address");
        assertThrows(IllegalArgumentException.class, () -> outbound.queueEmail("thomas@example.com", null,
                "delivery-test", model(), "k".repeat(201)), "dedupe key longer than the column");
        assertEquals(0, rows());
    }

    @Test
    void localProviders_makeEveryChannelAvailable() {
        assertTrue(outbound.isChannelAvailable("EMAIL"));
        assertTrue(outbound.isChannelAvailable(" sms "));
        assertTrue(outbound.isChannelAvailable("push"));
        assertFalse(outbound.isChannelAvailable("FAX"));
        assertFalse(outbound.isChannelAvailable(null));

        assertTrue(OutboundMessageService.available("local", true));
        assertFalse(OutboundMessageService.available("disabled", true));
        assertFalse(OutboundMessageService.available(" Disabled ", true));
        assertFalse(OutboundMessageService.available("ses", false), "a provider without credentials cannot send");
        assertFalse(OutboundMessageService.available(null, true));
    }

    @Test
    void outboundRequest_toStringCarriesNoAddressOrText() {
        OutboundRequest request = new OutboundRequest("EMAIL", "thomas@example.com", "PAT-00001", "delivery-test",
                Map.of("link", LINK), "Secret title", "Secret body", Map.of("k", "secret-data"), "KEY", 1, null);

        String text = request.toString();
        assertFalse(text.contains("thomas@example.com"));
        assertFalse(text.contains("Secret"));
        assertFalse(text.contains("secret-data"));
        assertFalse(text.contains("Zq3vJ0aP7c1mT5uK"));
        assertTrue(text.contains("delivery-test"));
    }
}
