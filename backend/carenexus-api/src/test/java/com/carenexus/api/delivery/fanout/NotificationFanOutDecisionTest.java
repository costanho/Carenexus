package com.carenexus.api.delivery.fanout;

import com.carenexus.api.communications.notify.event.NotificationStoredEvent;
import com.carenexus.api.delivery.OutboundMessageService;
import com.carenexus.api.delivery.OutboundRequest;
import com.carenexus.api.delivery.RecordingSenders;
import com.carenexus.api.delivery.RenderedTemplate;
import com.carenexus.api.delivery.TemplateRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The fan-out decisions without a database: what each channel's {@link OutboundRequest} carries, the channel matrix,
 * quiet hours and CRITICAL priority, and the two template files rendered with the production renderer.
 * {@link NotificationFanOutListenerTest} proves the same rules against PostgreSQL with real commits.
 */
class NotificationFanOutDecisionTest {

    private static final ZoneId HARARE = ZoneId.of("Africa/Harare");
    private static final Instant LATE_EVENING = Instant.parse("2030-11-10T20:30:00Z");
    private static final String ACTION_URL = "/dashboard/patient?section=my-appointments&id=412";

    private OutboundMessageService outbound;
    private NotificationFanOutRepository repository;
    private final AtomicInteger ids = new AtomicInteger(40);

    @BeforeEach
    void setUp() {
        outbound = mock(OutboundMessageService.class);
        when(outbound.queue(any())).thenAnswer(invocation -> ids.incrementAndGet());
        repository = mock(NotificationFanOutRepository.class);
        when(repository.findNotification(88, "PAT-00001")).thenReturn(Optional.of(notification(ACTION_URL)));
    }

    // ==================== fixtures ====================

    private static NotificationFanOutRepository.StoredNotification notification(String actionUrl) {
        return new NotificationFanOutRepository.StoredNotification() {
            public Integer getNotificationId() { return 88; }
            public String getType() { return "APPOINTMENT"; }
            public String getTitle() { return "Appointment booked"; }
            public String getMessage() { return "Your appointment was booked"; }
            public String getActionUrl() { return actionUrl; }
            public String getPriority() { return "NORMAL"; }
        };
    }

    private record Settings(String email, String phone, Boolean emailOn, Boolean smsOn, Boolean pushOn,
                            Boolean quiet, String start, String end, String timezone, boolean device)
            implements NotificationFanOutRepository.Recipient {
        public String getSystemId() { return "PAT-00001"; }
        public String getEmail() { return email; }
        public String getPhone() { return phone; }
        public Boolean getEmailNotifications() { return emailOn; }
        public Boolean getSmsNotifications() { return smsOn; }
        public Boolean getPushNotifications() { return pushOn; }
        public Boolean getQuietHoursEnabled() { return quiet; }
        public String getQuietHoursStart() { return start; }
        public String getQuietHoursEnd() { return end; }
        public String getTimezone() { return timezone; }
        public Boolean getHasPushDevice() { return device; }
    }

    /** No settings row: every column null; addresses and one device present. */
    private static Settings noSettingsRow() {
        return new Settings(" thomas@example.com ", "+263771234567", null, null, null, null, null, null, null, true);
    }

    private static Settings night(String timezone) {
        return new Settings("thomas@example.com", "+263771234567", true, true, true, true, "22:00:00", "07:00:00",
                timezone, true);
    }

    private NotificationFanOutListener listener(Instant now) {
        return listener(now, new RecordingSenders.Email(), new RecordingSenders.Sms(), new RecordingSenders.Push());
    }

    private NotificationFanOutListener listener(Instant now, RecordingSenders.Email email, RecordingSenders.Sms sms,
                                                RecordingSenders.Push push) {
        return new NotificationFanOutListener(outbound, repository, email, sms, push, Clock.fixed(now, HARARE),
                "http://localhost:8081//", "Africa/Harare");
    }

    private static NotificationStoredEvent event(String priority) {
        return new NotificationStoredEvent(88, "PAT-00001", "APPOINTMENT_CHANGE", priority, "APPT_BOOKED:412");
    }

    private List<OutboundRequest> queued(int expected) {
        ArgumentCaptor<OutboundRequest> captor = ArgumentCaptor.forClass(OutboundRequest.class);
        verify(outbound, times(expected)).queue(captor.capture());
        return captor.getAllValues();
    }

    // ==================== WHAT IS QUEUED ====================

    @Test
    void everyChannel_carriesTheContractFields() {
        when(repository.findRecipient("PAT-00001")).thenReturn(Optional.of(noSettingsRow()));

        List<Integer> result = listener(LATE_EVENING).fanOut(event("NORMAL"));

        assertEquals(List.of(41, 42, 43), result);
        List<OutboundRequest> requests = queued(3);
        Map<String, String> model = Map.of("title", "Appointment booked", "message", "Your appointment was booked",
                "link", "http://localhost:8081" + ACTION_URL);

        OutboundRequest email = requests.get(0);
        assertEquals("EMAIL", email.channel());
        assertEquals("thomas@example.com", email.toAddress());
        assertEquals("PAT-00001", email.recipientSystemId());
        assertEquals("notification-generic", email.templateId());
        assertEquals(model, email.model());
        assertNull(email.title());
        assertNull(email.body());
        assertNull(email.data());
        assertEquals("N:88:EMAIL", email.dedupeKey(), "not the notification's own dedupe key");
        assertEquals(88, email.notificationId());
        assertNull(email.notBefore(), "no settings row means no quiet hours");

        OutboundRequest sms = requests.get(1);
        assertEquals("SMS", sms.channel());
        assertEquals("+263771234567", sms.toAddress());
        assertEquals("notification-sms", sms.templateId());
        assertEquals(model, sms.model());
        assertEquals("N:88:SMS", sms.dedupeKey());

        OutboundRequest push = requests.get(2);
        assertEquals("PUSH", push.channel());
        assertNull(push.toAddress());
        assertNull(push.templateId());
        assertNull(push.model());
        assertEquals("Appointment booked", push.title());
        assertEquals("Your appointment was booked", push.body());
        assertEquals(Map.of("notificationId", "88", "type", "APPOINTMENT", "priority", "NORMAL",
                "actionUrl", ACTION_URL), push.data());
        assertEquals("N:88:PUSH", push.dedupeKey());
        assertEquals(88, push.notificationId());
    }

    @Test
    void withoutAnActionUrl_theLinkIsTheBaseUrl_andPushHasNoActionUrl() {
        when(repository.findNotification(88, "PAT-00001")).thenReturn(Optional.of(notification(null)));
        when(repository.findRecipient("PAT-00001")).thenReturn(Optional.of(noSettingsRow()));

        listener(LATE_EVENING).fanOut(event("LOW"));

        List<OutboundRequest> requests = queued(3);
        assertEquals("http://localhost:8081", requests.get(0).model().get("link"));
        assertEquals(Map.of("notificationId", "88", "type", "APPOINTMENT", "priority", "LOW"), requests.get(2).data());
    }

    @Test
    void anActionUrlThatIsNotRelative_fallsBackToTheBaseUrl() {
        when(repository.findNotification(88, "PAT-00001"))
                .thenReturn(Optional.of(notification("https://elsewhere.example/phish")));
        when(repository.findRecipient("PAT-00001")).thenReturn(Optional.of(noSettingsRow()));

        listener(LATE_EVENING).fanOut(event("NORMAL"));

        assertEquals("http://localhost:8081", queued(3).get(0).model().get("link"));
    }

    // ==================== CHANNEL MATRIX ====================

    @Test
    void switchesOff_noRowOnThatChannel_evenForCritical() {
        when(repository.findRecipient("PAT-00001")).thenReturn(Optional.of(new Settings("thomas@example.com",
                "+263771234567", false, true, false, false, null, null, null, true)));

        listener(LATE_EVENING).fanOut(event("CRITICAL"));

        List<OutboundRequest> requests = queued(1);
        assertEquals("SMS", requests.get(0).channel());
    }

    @Test
    void disabledOrUnconfiguredProviders_noRow() {
        when(repository.findRecipient("PAT-00001")).thenReturn(Optional.of(noSettingsRow()));

        listener(LATE_EVENING, new RecordingSenders.Email("Disabled", true), new RecordingSenders.Sms("twilio", false),
                new RecordingSenders.Push()).fanOut(event("NORMAL"));

        List<OutboundRequest> requests = queued(1);
        assertEquals("PUSH", requests.get(0).channel());
    }

    @Test
    void missingAddresses_noRow() {
        when(repository.findRecipient("PAT-00001")).thenReturn(Optional.of(new Settings("  ", null, true, true,
                true, false, null, null, null, false)));

        assertEquals(List.of(), listener(LATE_EVENING).fanOut(event("NORMAL")));
        verify(outbound, never()).queue(any());
    }

    @Test
    void unknownNotificationOrRecipient_isANoOp() {
        when(repository.findRecipient("PAT-00001")).thenReturn(Optional.empty());

        assertEquals(List.of(), listener(LATE_EVENING).fanOut(event("NORMAL")));
        assertEquals(List.of(), listener(LATE_EVENING).fanOut(
                new NotificationStoredEvent(99, "PAT-00001", "SYSTEM", "NORMAL", null)));
        assertEquals(List.of(), listener(LATE_EVENING).fanOut(
                new NotificationStoredEvent(null, "PAT-00001", "SYSTEM", "NORMAL", null)));
        verify(outbound, never()).queue(any());
    }

    // ==================== QUIET HOURS ====================

    @Test
    void insideTheWindow_everyChannelGetsTheSameNotBefore_inClinicWallClock() {
        when(repository.findRecipient("PAT-00001")).thenReturn(Optional.of(night(null)));

        listener(LATE_EVENING).fanOut(event("HIGH"));

        LocalDateTime sevenAm = LocalDateTime.of(2030, 11, 11, 7, 0);
        queued(3).forEach(request -> assertEquals(sevenAm, request.notBefore(), request.channel()));
    }

    @Test
    void theRecipientsZone_decides() {
        when(repository.findRecipient("PAT-00001")).thenReturn(Optional.of(night("America/New_York")));

        listener(LATE_EVENING).fanOut(event("NORMAL"));

        queued(3).forEach(request -> assertNull(request.notBefore(), "15:30 in New York is outside the window"));
    }

    @Test
    void critical_isNeverHeld() {
        when(repository.findRecipient("PAT-00001")).thenReturn(Optional.of(night(null)));

        listener(LATE_EVENING).fanOut(event("critical"));

        List<OutboundRequest> requests = queued(3);
        requests.forEach(request -> assertNull(request.notBefore()));
        assertEquals("CRITICAL", requests.get(2).data().get("priority"));
    }

    @Test
    void anUnreadableStoredTime_meansNoWindow() {
        when(repository.findRecipient("PAT-00001")).thenReturn(Optional.of(new Settings("thomas@example.com",
                "+263771234567", true, true, true, true, "late", "07:00:00", null, true)));

        listener(LATE_EVENING).fanOut(event("NORMAL"));

        queued(3).forEach(request -> assertNull(request.notBefore()));
    }

    // ==================== TEMPLATES ====================

    @Test
    void emailTemplate_rendersTheContractText() {
        RenderedTemplate rendered = new TemplateRenderer().render("notification-generic", Map.of(
                "title", "Appointment booked", "message", "Your appointment with Dr. Amara Osei was booked",
                "link", "http://localhost:8081" + ACTION_URL));

        assertEquals("New notification from CareNexus", rendered.subject());
        assertEquals("Appointment booked\n\nYour appointment with Dr. Amara Osei was booked\n\n"
                + "Open CareNexus to see the details:\nhttp://localhost:8081" + ACTION_URL + "\n\n"
                + "You receive this email because email notifications are switched on in your CareNexus settings. "
                + "For your privacy it contains no clinical details.\n", rendered.body());
    }

    @Test
    void smsTemplate_rendersTheContractText() {
        RenderedTemplate rendered = new TemplateRenderer().render("notification-sms", Map.of(
                "title", "Appointment booked", "message", "ignored by the SMS text",
                "link", "http://localhost:8081" + ACTION_URL));

        assertEquals("CareNexus notification", rendered.subject());
        assertEquals("CareNexus: Appointment booked. Sign in to view: http://localhost:8081" + ACTION_URL + "\n",
                rendered.body());
        assertTrue(!rendered.body().contains("ignored by the SMS text"), "the SMS carries the title only");
    }
}
