package com.carenexus.api.delivery.fanout;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.communications.notify.NotificationCategory;
import com.carenexus.api.communications.notify.NotificationCommand;
import com.carenexus.api.communications.notify.NotificationPriority;
import com.carenexus.api.communications.notify.NotificationPublisher;
import com.carenexus.api.communications.notify.NotificationTarget;
import com.carenexus.api.communications.notify.NotificationType;
import com.carenexus.api.communications.notify.event.NotificationStoredEvent;
import com.carenexus.api.delivery.OutboundMessageService;
import com.carenexus.api.delivery.RecordingSenders;
import com.carenexus.api.delivery.email.EmailSender;
import com.carenexus.api.delivery.model.OutboundMessage;
import com.carenexus.api.delivery.push.PushSender;
import com.carenexus.api.delivery.repository.OutboundMessageRepository;
import com.carenexus.api.delivery.sms.SmsSender;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The notification fan-out against PostgreSQL with real commits: what is queued per channel and its content, the
 * channel matrix (switch off, provider disabled or not configured, no address), dedupe on a repeated event and per
 * recipient, quiet hours in the recipient's time zone, CRITICAL priority, and that a rolled-back publisher sends
 * nothing.
 *
 * Tests of the decision rules call their own listener instance (fixed clock, chosen senders) on notification rows
 * inserted directly, so the application's listener does not run for them. Fixed instants lie in 2030 so a held
 * message's send time is always in the future and is stored exactly.
 */
class NotificationFanOutListenerTest extends CommittedIntegrationTest {

    private static final ZoneId HARARE = ZoneId.of("Africa/Harare");
    private static final String BASE = "http://localhost:8081";
    /** 22:30 in Harare, 15:30 in New York. */
    private static final Instant LATE_EVENING = Instant.parse("2030-11-10T20:30:00Z");
    /** 07:00 Harare the next morning. */
    private static final Instant NEXT_MORNING = Instant.parse("2030-11-11T05:00:00Z");

    @Autowired
    private NotificationPublisher publisher;

    @Autowired
    private NotificationFanOutListener applicationListener;

    @Autowired
    private OutboundMessageService outboundMessageService;

    @Autowired
    private NotificationFanOutRepository fanOutRepository;

    @Autowired
    private OutboundMessageRepository outboundMessages;

    @Autowired
    private EmailSender emailSender;

    @Autowired
    private SmsSender smsSender;

    @Autowired
    private PushSender pushSender;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUpTransaction() {
        tx = new TransactionTemplate(transactionManager);
    }

    // ==================== helpers ====================

    /** A listener with the application's senders and a clock fixed at {@code now}. */
    private NotificationFanOutListener listenerAt(Instant now) {
        return listener(Clock.fixed(now, HARARE), emailSender, smsSender, pushSender);
    }

    private NotificationFanOutListener listener(Clock clock, EmailSender email, SmsSender sms, PushSender push) {
        return new NotificationFanOutListener(outboundMessageService, fanOutRepository, email, sms, push, clock,
                BASE + "/", "Africa/Harare");
    }

    /** Runs the listener in a committed transaction, as the publisher would. */
    private List<Integer> fanOut(NotificationFanOutListener listener, NotificationStoredEvent event) {
        return tx.execute(status -> listener.fanOut(event));
    }

    /** Inserts a notification row directly (no event is published) and returns the matching event. */
    private NotificationStoredEvent storedNotification(User recipient, String priority, String actionUrl) {
        Integer id = jdbc.queryForObject("""
                        INSERT INTO notifications (system_id, type, title, message, action_url, priority, category,
                                                   is_read, created_at)
                        VALUES (?, 'APPOINTMENT', 'Appointment booked', 'Your appointment was booked', ?, ?,
                                'APPOINTMENT_CHANGE', FALSE, now())
                        RETURNING notification_id
                        """, Integer.class, recipient.getSystemId(), actionUrl, priority);
        return new NotificationStoredEvent(id, recipient.getSystemId(), "APPOINTMENT_CHANGE", priority, null);
    }

    private NotificationStoredEvent storedNotification(User recipient, String priority) {
        return storedNotification(recipient, priority, "/dashboard/patient?section=my-appointments&id=412");
    }

    private void settings(User user, boolean email, boolean sms, boolean push, boolean quiet, LocalTime start,
                          LocalTime end, String timezone) {
        jdbc.update("""
                        INSERT INTO user_settings (system_id, email_notifications, sms_notifications,
                                                   push_notifications, quiet_hours_enabled, quiet_hours_start,
                                                   quiet_hours_end, timezone)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, user.getSystemId(), email, sms, push, quiet,
                start == null ? null : java.sql.Time.valueOf(start),
                end == null ? null : java.sql.Time.valueOf(end), timezone);
    }

    /** Quiet hours 22:00 to 07:00 in {@code timezone}, every channel on. */
    private void nightQuietHours(User user, String timezone) {
        settings(user, true, true, true, true, LocalTime.of(22, 0), LocalTime.of(7, 0), timezone);
    }

    private void registerDevice(User user, String token) {
        jdbc.update("INSERT INTO push_devices (system_id, platform, token) VALUES (?, 'ANDROID', ?)",
                user.getSystemId(), token);
    }

    private List<OutboundMessage> messagesFor(Integer notificationId) {
        return outboundMessages.findAll().stream()
                .filter(m -> notificationId.equals(m.getNotificationId()))
                .sorted(Comparator.comparing(OutboundMessage::getMessageId))
                .toList();
    }

    private Map<String, OutboundMessage> byChannel(Integer notificationId) {
        return messagesFor(notificationId).stream()
                .collect(Collectors.toMap(OutboundMessage::getChannel, m -> m));
    }

    private int outboxRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbound_messages", Integer.class);
    }

    private Map<String, String> data(OutboundMessage message) throws Exception {
        return objectMapper.readValue(message.getData(), new TypeReference<Map<String, String>>() { });
    }

    private void assertDueAtOnce(OutboundMessage message) {
        assertEquals(message.getCreatedAt(), message.getNextAttemptAt(), message.getChannel() + " is due at once");
    }

    // ==================== THROUGH THE PUBLISHER ====================

    @Test
    void publishedNotification_queuesOneMessagePerChannel_withTheContractContent() throws Exception {
        registerDevice(patient, "device-token-patient-0001");

        Integer notificationId = tx.execute(status -> publisher.publish(NotificationCommand.builder()
                .recipientSystemId(patient.getSystemId())
                .type(NotificationType.APPOINTMENT)
                .category(NotificationCategory.APPOINTMENT_CHANGE)
                .title("Appointment booked")
                .message("Your appointment with Dr. Amara Osei was booked")
                .target(NotificationTarget.APPOINTMENTS)
                .resourceType("APPOINTMENT")
                .resourceId(412)
                .patientSystemId(patient.getSystemId())
                .actorSystemId(doctor.getSystemId())
                .dedupeKey("APPT_BOOKED:412")
                .build())).orElseThrow();
        String actionUrl = jdbc.queryForObject("SELECT action_url FROM notifications WHERE notification_id = ?",
                String.class, notificationId);
        assertTrue(actionUrl.startsWith("/dashboard/patient?"), actionUrl);
        String link = BASE + actionUrl;

        List<OutboundMessage> rows = messagesFor(notificationId);
        assertEquals(List.of("EMAIL", "SMS", "PUSH"), rows.stream().map(OutboundMessage::getChannel).toList(),
                "one row per channel, in the order EMAIL, SMS, PUSH");
        Map<String, OutboundMessage> byChannel = byChannel(notificationId);

        OutboundMessage email = byChannel.get("EMAIL");
        assertEquals(patient.getEmail(), email.getRecipientAddress());
        assertEquals(patient.getSystemId(), email.getRecipientSystemId());
        assertEquals("notification-generic", email.getTemplateId());
        assertEquals("New notification from CareNexus", email.getSubject());
        assertEquals("Appointment booked\n\nYour appointment with Dr. Amara Osei was booked\n\n"
                + "Open CareNexus to see the details:\n" + link + "\n\n"
                + "You receive this email because email notifications are switched on in your CareNexus settings. "
                + "For your privacy it contains no clinical details.\n", email.getBody());
        assertNull(email.getData());
        assertEquals("N:" + notificationId + ":EMAIL", email.getDedupeKey());
        assertEquals(OutboundMessage.PENDING, email.getStatus());
        assertEquals(0, email.getAttempts());
        assertDueAtOnce(email);

        OutboundMessage sms = byChannel.get("SMS");
        assertEquals(patient.getPhone(), sms.getRecipientAddress());
        assertEquals("notification-sms", sms.getTemplateId());
        assertNull(sms.getSubject(), "the Subject line of an SMS template is ignored");
        assertEquals("CareNexus: Appointment booked. Sign in to view: " + link, sms.getBody().trim());
        assertEquals("N:" + notificationId + ":SMS", sms.getDedupeKey());
        assertDueAtOnce(sms);

        OutboundMessage push = byChannel.get("PUSH");
        assertNull(push.getRecipientAddress(), "devices are resolved by the worker");
        assertEquals(patient.getSystemId(), push.getRecipientSystemId());
        assertNull(push.getTemplateId());
        assertEquals("Appointment booked", push.getSubject());
        assertEquals("Your appointment with Dr. Amara Osei was booked", push.getBody());
        assertEquals(Map.of("notificationId", String.valueOf(notificationId), "type", "APPOINTMENT",
                "priority", "NORMAL", "actionUrl", actionUrl), data(push));
        assertEquals("N:" + notificationId + ":PUSH", push.getDedupeKey());
        assertDueAtOnce(push);

        for (OutboundMessage message : rows) {
            assertEquals(notificationId, message.getNotificationId());
        }
    }

    @Test
    void repeatedEvent_queuesNothingNew_andReturnsTheExistingRows() {
        registerDevice(patient, "device-token-patient-0002");
        NotificationStoredEvent event = storedNotification(patient, "NORMAL");

        List<Integer> first = fanOut(applicationListener, event);
        List<Integer> second = fanOut(applicationListener, event);

        assertEquals(3, first.size());
        assertEquals(first, second, "the existing row ids are returned");
        assertEquals(3, outboxRows(), "no row is added for the repeated event");
    }

    @Test
    void theSameNotificationDedupeKeyForTwoRecipients_reachesBoth() {
        // The notification dedupe key is per recipient; the outbox key uses the notification id, so the second
        // recipient is not dropped.
        tx.executeWithoutResult(status -> {
            for (User recipient : List.of(patient, caregiver)) {
                publisher.publish(NotificationCommand.builder()
                        .recipientSystemId(recipient.getSystemId())
                        .type(NotificationType.LAB_RESULT)
                        .category(NotificationCategory.LAB_RESULT)
                        .title("New lab result")
                        .message("A lab result is available")
                        .actorSystemId(doctor.getSystemId())
                        .dedupeKey("LAB_RESULT:77")
                        .build());
            }
        });

        List<String> emailRecipients = jdbc.queryForList(
                "SELECT recipient_system_id FROM outbound_messages WHERE channel = 'EMAIL' ORDER BY message_id",
                String.class);
        assertEquals(List.of(patient.getSystemId(), caregiver.getSystemId()), emailRecipients);
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbound_messages WHERE channel = 'SMS'", Integer.class));
    }

    @Test
    void rolledBackPublisher_sendsNothing() {
        registerDevice(patient, "device-token-patient-0003");

        tx.executeWithoutResult(status -> {
            Optional<Integer> id = publisher.publish(NotificationCommand.builder()
                    .recipientSystemId(patient.getSystemId())
                    .type(NotificationType.SYSTEM)
                    .category(NotificationCategory.SYSTEM)
                    .title("Account update")
                    .message("Something changed on your account")
                    .build());
            assertTrue(id.isPresent());
            assertEquals(3, outboxRows(), "queued inside the publisher's transaction");
            status.setRollbackOnly();
        });

        assertEquals(0, outboxRows(), "the outbox rows roll back with the notification");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class));
    }

    @Test
    void anOutboxFailure_rollsBackThePublisher() {
        // The listener swallows nothing: an address the outbox rejects fails the publishing transaction.
        jdbc.update("UPDATE users SET phone = ? WHERE system_id = ?", "+2637\u00071234567", patient.getSystemId());

        assertThrows(IllegalArgumentException.class, () -> tx.executeWithoutResult(status ->
                publisher.publish(NotificationCommand.builder()
                        .recipientSystemId(patient.getSystemId())
                        .type(NotificationType.SYSTEM)
                        .category(NotificationCategory.SYSTEM)
                        .title("Account update")
                        .message("Something changed on your account")
                        .build())));

        assertEquals(0, outboxRows());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class));
    }

    // ==================== CHANNEL MATRIX ====================

    @Test
    void noSettingsRow_meansEveryChannelOn() {
        registerDevice(patient, "device-token-patient-0004");
        NotificationStoredEvent event = storedNotification(patient, "NORMAL");

        fanOut(listenerAt(LATE_EVENING), event);

        assertEquals(List.of("EMAIL", "SMS", "PUSH"), messagesFor(event.notificationId()).stream()
                .map(OutboundMessage::getChannel).toList());
        // No settings row also means no quiet hours, even late in the evening.
        byChannel(event.notificationId()).values().forEach(this::assertDueAtOnce);
    }

    @Test
    void aSwitchedOffChannel_getsNoRow_forEveryPriority() {
        registerDevice(patient, "device-token-patient-0005");
        settings(patient, true, false, true, false, null, null, null);

        NotificationStoredEvent normal = storedNotification(patient, "NORMAL");
        NotificationStoredEvent critical = storedNotification(patient, "CRITICAL");
        fanOut(listenerAt(LATE_EVENING), normal);
        fanOut(listenerAt(LATE_EVENING), critical);

        assertEquals(List.of("EMAIL", "PUSH"), messagesFor(normal.notificationId()).stream()
                .map(OutboundMessage::getChannel).toList());
        assertEquals(List.of("EMAIL", "PUSH"), messagesFor(critical.notificationId()).stream()
                .map(OutboundMessage::getChannel).toList(), "channel switches apply to CRITICAL too");
    }

    @Test
    void emailAndPushSwitchedOff_onlySms() {
        registerDevice(patient, "device-token-patient-0006");
        settings(patient, false, true, false, false, null, null, null);
        NotificationStoredEvent event = storedNotification(patient, "HIGH");

        fanOut(listenerAt(LATE_EVENING), event);

        assertEquals(List.of("SMS"), messagesFor(event.notificationId()).stream()
                .map(OutboundMessage::getChannel).toList());
    }

    @Test
    void aDisabledOrUnconfiguredProvider_getsNoRow() {
        registerDevice(patient, "device-token-patient-0007");
        Clock clock = Clock.fixed(LATE_EVENING, HARARE);

        NotificationStoredEvent first = storedNotification(patient, "NORMAL");
        fanOut(listener(clock, new RecordingSenders.Email("disabled", true), smsSender, pushSender), first);
        assertEquals(List.of("SMS", "PUSH"), messagesFor(first.notificationId()).stream()
                .map(OutboundMessage::getChannel).toList());

        NotificationStoredEvent second = storedNotification(patient, "NORMAL");
        fanOut(listener(clock, emailSender, new RecordingSenders.Sms("DISABLED", false), pushSender), second);
        assertEquals(List.of("EMAIL", "PUSH"), messagesFor(second.notificationId()).stream()
                .map(OutboundMessage::getChannel).toList());

        NotificationStoredEvent third = storedNotification(patient, "NORMAL");
        fanOut(listener(clock, emailSender, smsSender, new RecordingSenders.Push("disabled", true)), third);
        assertEquals(List.of("EMAIL", "SMS"), messagesFor(third.notificationId()).stream()
                .map(OutboundMessage::getChannel).toList());

        // A real provider id that reports missing credentials is not available either.
        NotificationStoredEvent fourth = storedNotification(patient, "CRITICAL");
        fanOut(listener(clock, new RecordingSenders.Email("ses", false), smsSender, pushSender), fourth);
        assertEquals(List.of("SMS", "PUSH"), messagesFor(fourth.notificationId()).stream()
                .map(OutboundMessage::getChannel).toList());

        assertFalse(outboundMessages.findAll().stream().anyMatch(m -> OutboundMessage.SKIPPED.equals(m.getStatus())),
                "the fan-out never stores SKIPPED rows");
    }

    @Test
    void noAddress_getsNoRow() {
        // No device registered, no phone, a blank email.
        jdbc.update("UPDATE users SET phone = NULL, email = '   ' WHERE system_id = ?", patient.getSystemId());
        NotificationStoredEvent event = storedNotification(patient, "NORMAL");

        assertEquals(List.of(), fanOut(listenerAt(LATE_EVENING), event));
        assertEquals(0, outboxRows());

        // With a device only push is queued.
        registerDevice(patient, "device-token-patient-0008");
        fanOut(listenerAt(LATE_EVENING), event);
        assertEquals(List.of("PUSH"), messagesFor(event.notificationId()).stream()
                .map(OutboundMessage::getChannel).toList());
    }

    @Test
    void anotherUsersDevice_isNotThisRecipientsAddress() {
        registerDevice(otherPatient, "device-token-other-0001");
        NotificationStoredEvent event = storedNotification(patient, "NORMAL");

        fanOut(listenerAt(LATE_EVENING), event);

        assertFalse(byChannel(event.notificationId()).containsKey("PUSH"));
    }

    @Test
    void unknownNotificationOrRecipient_isALoggedNoOp() {
        NotificationStoredEvent event = storedNotification(patient, "NORMAL");

        assertEquals(List.of(), fanOut(listenerAt(LATE_EVENING),
                new NotificationStoredEvent(999_999, patient.getSystemId(), "SYSTEM", "NORMAL", null)));
        assertEquals(List.of(), fanOut(listenerAt(LATE_EVENING),
                new NotificationStoredEvent(event.notificationId(), otherPatient.getSystemId(), "SYSTEM", "NORMAL",
                        null)), "a notification is only sent to its own recipient");
        assertEquals(List.of(), fanOut(listenerAt(LATE_EVENING),
                new NotificationStoredEvent(event.notificationId(), "PAT-99999", "SYSTEM", "NORMAL", null)));
        assertEquals(0, outboxRows());
    }

    @Test
    void withoutAnActionUrl_theLinkIsTheWebBaseUrl_andPushDataHasNoActionUrl() throws Exception {
        registerDevice(patient, "device-token-patient-0009");
        NotificationStoredEvent event = storedNotification(patient, "LOW", null);

        fanOut(listenerAt(LATE_EVENING), event);

        Map<String, OutboundMessage> rows = byChannel(event.notificationId());
        assertTrue(rows.get("EMAIL").getBody().contains("\n" + BASE + "\n"), rows.get("EMAIL").getBody());
        assertEquals("CareNexus: Appointment booked. Sign in to view: " + BASE, rows.get("SMS").getBody().trim());
        assertEquals(Map.of("notificationId", String.valueOf(event.notificationId()), "type", "APPOINTMENT",
                "priority", "LOW"), data(rows.get("PUSH")));
    }

    // ==================== QUIET HOURS ====================

    @Test
    void insideTheQuietWindow_everyChannelIsHeldUntilItsEnd() {
        registerDevice(patient, "device-token-patient-0010");
        nightQuietHours(patient, null);
        NotificationStoredEvent event = storedNotification(patient, "NORMAL");

        fanOut(listenerAt(LATE_EVENING), event);

        List<OutboundMessage> rows = messagesFor(event.notificationId());
        assertEquals(3, rows.size());
        for (OutboundMessage message : rows) {
            assertEquals(NEXT_MORNING, message.getNextAttemptAt(), message.getChannel() + " held until 07:00");
            assertEquals(OutboundMessage.PENDING, message.getStatus());
            assertEquals(0, message.getAttempts());
        }
    }

    @Test
    void aWindowCrossingMidnight_holdsBeforeItsEnd_andReleasesAtItsEnd() {
        nightQuietHours(patient, "Africa/Harare");

        // 06:00 Harare: held until 07:00 the same day.
        NotificationStoredEvent early = storedNotification(patient, "HIGH");
        fanOut(listenerAt(Instant.parse("2030-11-11T04:00:00Z")), early);
        messagesFor(early.notificationId()).forEach(m -> assertEquals(NEXT_MORNING, m.getNextAttemptAt()));

        // 07:00 Harare: the end is exclusive, due at once.
        NotificationStoredEvent atEnd = storedNotification(patient, "NORMAL");
        fanOut(listenerAt(NEXT_MORNING), atEnd);
        assertFalse(messagesFor(atEnd.notificationId()).isEmpty());
        messagesFor(atEnd.notificationId()).forEach(this::assertDueAtOnce);
    }

    @Test
    void aSameDayWindow_holdsUntilItsEnd() {
        settings(patient, true, true, true, true, LocalTime.of(13, 0), LocalTime.of(15, 0), null);
        NotificationStoredEvent event = storedNotification(patient, "NORMAL");

        // 14:00 Harare -> 15:00 Harare
        fanOut(listenerAt(Instant.parse("2030-11-10T12:00:00Z")), event);

        messagesFor(event.notificationId()).forEach(m ->
                assertEquals(Instant.parse("2030-11-10T13:00:00Z"), m.getNextAttemptAt()));
    }

    @Test
    void theRecipientsTimeZoneDecides_theSameInstantHeldForOneUserAndNotForAnother() {
        // 20:30Z: 22:30 in Harare (inside 22:00-07:00), 15:30 in New York (outside).
        nightQuietHours(patient, null);
        nightQuietHours(otherPatient, "America/New_York");
        NotificationStoredEvent clinicZoneUser = storedNotification(patient, "NORMAL");
        NotificationStoredEvent newYorkUser = storedNotification(otherPatient, "NORMAL");

        fanOut(listenerAt(LATE_EVENING), clinicZoneUser);
        fanOut(listenerAt(LATE_EVENING), newYorkUser);

        messagesFor(clinicZoneUser.notificationId()).forEach(m -> assertEquals(NEXT_MORNING, m.getNextAttemptAt()));
        assertFalse(messagesFor(newYorkUser.notificationId()).isEmpty());
        messagesFor(newYorkUser.notificationId()).forEach(this::assertDueAtOnce);
    }

    @Test
    void aWindowInTheRecipientsZone_holdsWhenTheClinicZoneWouldNot() {
        // 14:00Z: 16:00 in Harare (outside), 23:00 in Tokyo (inside): held until 07:00 Tokyo = 22:00Z.
        nightQuietHours(patient, "Asia/Tokyo");
        nightQuietHours(otherPatient, null);
        Instant afternoonInHarare = Instant.parse("2030-11-10T14:00:00Z");
        NotificationStoredEvent tokyoUser = storedNotification(patient, "NORMAL");
        NotificationStoredEvent clinicZoneUser = storedNotification(otherPatient, "NORMAL");

        fanOut(listenerAt(afternoonInHarare), tokyoUser);
        fanOut(listenerAt(afternoonInHarare), clinicZoneUser);

        messagesFor(tokyoUser.notificationId()).forEach(m ->
                assertEquals(Instant.parse("2030-11-10T22:00:00Z"), m.getNextAttemptAt()));
        messagesFor(clinicZoneUser.notificationId()).forEach(this::assertDueAtOnce);
    }

    @Test
    void anInvalidTimeZone_usesTheClinicZone() {
        nightQuietHours(patient, "Not/AZone");
        NotificationStoredEvent event = storedNotification(patient, "NORMAL");

        fanOut(listenerAt(LATE_EVENING), event);

        assertFalse(messagesFor(event.notificationId()).isEmpty());
        messagesFor(event.notificationId()).forEach(m -> assertEquals(NEXT_MORNING, m.getNextAttemptAt()));
    }

    @Test
    void criticalPriority_isNeverHeld() {
        registerDevice(patient, "device-token-patient-0011");
        nightQuietHours(patient, null);
        NotificationStoredEvent event = storedNotification(patient, "CRITICAL");

        fanOut(listenerAt(LATE_EVENING), event);

        List<OutboundMessage> rows = messagesFor(event.notificationId());
        assertEquals(3, rows.size());
        rows.forEach(this::assertDueAtOnce);
    }

    @Test
    void quietHoursOffOrEqualBounds_holdNothing() {
        settings(patient, true, true, true, false, LocalTime.of(22, 0), LocalTime.of(7, 0), null);
        settings(otherPatient, true, true, true, true, LocalTime.of(22, 0), LocalTime.of(22, 0), null);
        NotificationStoredEvent off = storedNotification(patient, "NORMAL");
        NotificationStoredEvent equal = storedNotification(otherPatient, "NORMAL");

        fanOut(listenerAt(LATE_EVENING), off);
        fanOut(listenerAt(LATE_EVENING), equal);

        assertFalse(messagesFor(off.notificationId()).isEmpty());
        assertFalse(messagesFor(equal.notificationId()).isEmpty());
        messagesFor(off.notificationId()).forEach(this::assertDueAtOnce);
        messagesFor(equal.notificationId()).forEach(this::assertDueAtOnce);
    }

    @Test
    void priorityDecidesDeferral_notCategory() {
        // A SYSTEM or CRITICAL category with a lower priority is still held.
        nightQuietHours(patient, null);
        Integer id = jdbc.queryForObject("""
                INSERT INTO notifications (system_id, type, title, message, priority, category, is_read, created_at)
                VALUES (?, 'SYSTEM', 'New sign-in', 'A new sign-in to your account', 'HIGH', 'LOGIN_ALERT', FALSE, now())
                RETURNING notification_id
                """, Integer.class, patient.getSystemId());

        fanOut(listenerAt(LATE_EVENING), new NotificationStoredEvent(id, patient.getSystemId(), "CRITICAL", "HIGH",
                null));

        assertFalse(messagesFor(id).isEmpty());
        messagesFor(id).forEach(m -> assertEquals(NEXT_MORNING, m.getNextAttemptAt()));
    }
}
