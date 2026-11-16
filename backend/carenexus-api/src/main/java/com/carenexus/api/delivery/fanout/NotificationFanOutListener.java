package com.carenexus.api.delivery.fanout;

import com.carenexus.api.communications.notify.NotificationPriority;
import com.carenexus.api.communications.notify.event.NotificationStoredEvent;
import com.carenexus.api.delivery.DeliveryChannel;
import com.carenexus.api.delivery.OutboundMessageService;
import com.carenexus.api.delivery.OutboundRequest;
import com.carenexus.api.delivery.email.EmailSender;
import com.carenexus.api.delivery.push.PushSender;
import com.carenexus.api.delivery.sms.SmsSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Sends every stored in-app notification on to email, SMS and push through the outbox.
 *
 * <p>A plain {@link EventListener} (not transactional-after-commit, not async): it runs inside the publisher's
 * transaction, so the outbox rows commit or roll back together with the notification, and a rolled-back change
 * sends nothing. It opens no transaction of its own and swallows no exception from the outbox.</p>
 *
 * <p>For EMAIL, SMS and PUSH, in that order, one outbox row is queued when all of these hold:</p>
 * <ol>
 *   <li>the channel is available on this installation (provider not {@code disabled} and configured, the rule of
 *       {@code GET /api/notifications/channels});</li>
 *   <li>the recipient's channel switch ({@code user_settings.email_notifications}, {@code sms_notifications},
 *       {@code push_notifications}) is on; without a settings row all three are on. The switches apply to every
 *       priority, CRITICAL included;</li>
 *   <li>the address exists: {@code users.email}, {@code users.phone}, or at least one registered push device.</li>
 * </ol>
 *
 * <p>Quiet hours ({@link QuietHours}) hold LOW, NORMAL and HIGH priority messages until the end of the recipient's
 * quiet window; CRITICAL priority is never held. The outbox dedupe key is {@code N:<notificationId>:<CHANNEL>}, so a
 * repeated event queues nothing new. Only ids are logged, never addresses or texts.</p>
 */
@Slf4j
@Component
public class NotificationFanOutListener {

    static final String EMAIL_TEMPLATE = "notification-generic";
    static final String SMS_TEMPLATE = "notification-sms";
    static final String DEDUPE_PREFIX = "N:";

    private final OutboundMessageService outbound;
    private final NotificationFanOutRepository repository;
    private final EmailSender emailSender;
    private final SmsSender smsSender;
    private final PushSender pushSender;
    private final Clock clock;
    private final String webBaseUrl;
    private final ZoneId clinicZone;

    @Autowired
    public NotificationFanOutListener(OutboundMessageService outbound,
                                      NotificationFanOutRepository repository,
                                      EmailSender emailSender,
                                      SmsSender smsSender,
                                      PushSender pushSender,
                                      Clock clock,
                                      @Value("${carenexus.web.base-url:http://localhost:8081}") String webBaseUrl,
                                      @Value("${carenexus.scheduling.time-zone:Africa/Harare}") String clinicZone) {
        this.outbound = outbound;
        this.repository = repository;
        this.emailSender = emailSender;
        this.smsSender = smsSender;
        this.pushSender = pushSender;
        this.clock = clock;
        this.webBaseUrl = stripTrailingSlashes(webBaseUrl);
        this.clinicZone = ZoneId.of(clinicZone.trim());
    }

    /** Runs synchronously in the publisher's transaction (see the class comment). */
    @EventListener
    public void onNotificationStored(NotificationStoredEvent event) {
        fanOut(event);
    }

    /**
     * Queues the external messages for one stored notification. Kept apart from the listener method because an
     * {@code @EventListener} that returns a value would have it published as a new event.
     *
     * @return the outbox row ids, one per channel that was queued (existing ids when the event is repeated)
     */
    public List<Integer> fanOut(NotificationStoredEvent event) {
        if (event == null || event.notificationId() == null || isBlank(event.recipientSystemId())) {
            log.warn("Notification fan-out skipped: the stored event has no notification id or recipient");
            return List.of();
        }
        String recipientId = event.recipientSystemId().trim();
        Optional<NotificationFanOutRepository.StoredNotification> stored =
                repository.findNotification(event.notificationId(), recipientId);
        if (stored.isEmpty()) {
            log.warn("Notification fan-out skipped: notification {} for {} not found", event.notificationId(),
                    recipientId);
            return List.of();
        }
        Optional<NotificationFanOutRepository.Recipient> found = repository.findRecipient(recipientId);
        if (found.isEmpty()) {
            log.warn("Notification fan-out skipped: recipient {} of notification {} has no account", recipientId,
                    event.notificationId());
            return List.of();
        }
        NotificationFanOutRepository.StoredNotification notification = stored.get();
        NotificationFanOutRepository.Recipient recipient = found.get();

        String priority = priority(event, notification);
        LocalDateTime notBefore = NotificationPriority.CRITICAL.name().equals(priority)
                ? null
                : quietHours(recipient).deferUntil(clock.instant(), clinicZone);

        List<Integer> queued = new ArrayList<>(3);
        String email = trimToNull(recipient.getEmail());
        if (OutboundMessageService.available(emailSender.providerName(), emailSender.isConfigured())
                && flag(recipient.getEmailNotifications()) && email != null) {
            queued.add(outbound.queue(new OutboundRequest(DeliveryChannel.EMAIL, email, recipientId, EMAIL_TEMPLATE,
                    model(notification), null, null, null, dedupeKey(event, DeliveryChannel.EMAIL),
                    event.notificationId(), notBefore)));
        }
        String phone = trimToNull(recipient.getPhone());
        if (OutboundMessageService.available(smsSender.providerName(), smsSender.isConfigured())
                && flag(recipient.getSmsNotifications()) && phone != null) {
            queued.add(outbound.queue(new OutboundRequest(DeliveryChannel.SMS, phone, recipientId, SMS_TEMPLATE,
                    model(notification), null, null, null, dedupeKey(event, DeliveryChannel.SMS),
                    event.notificationId(), notBefore)));
        }
        if (OutboundMessageService.available(pushSender.providerName(), pushSender.isConfigured())
                && flag(recipient.getPushNotifications()) && Boolean.TRUE.equals(recipient.getHasPushDevice())) {
            queued.add(outbound.queue(new OutboundRequest(DeliveryChannel.PUSH, null, recipientId, null, null,
                    nullToEmpty(notification.getTitle()), nullToEmpty(notification.getMessage()),
                    pushData(event, notification, priority), dedupeKey(event, DeliveryChannel.PUSH),
                    event.notificationId(), notBefore)));
        }
        log.debug("Notification {} fanned out for {}: {} outbound message(s), deferred={}", event.notificationId(),
                recipientId, queued.size(), notBefore != null);
        return queued;
    }

    // ==================== CONTENT ====================

    /** Template model of the email and the SMS: always the three keys, never null. */
    Map<String, String> model(NotificationFanOutRepository.StoredNotification notification) {
        Map<String, String> model = new LinkedHashMap<>();
        model.put("title", nullToEmpty(notification.getTitle()));
        model.put("message", nullToEmpty(notification.getMessage()));
        model.put("link", link(notification.getActionUrl()));
        return model;
    }

    /** The web link: base URL plus the relative action URL, or the base URL alone. */
    String link(String actionUrl) {
        if (actionUrl != null && actionUrl.startsWith("/")) {
            return webBaseUrl + actionUrl;
        }
        return webBaseUrl;
    }

    private static Map<String, String> pushData(NotificationStoredEvent event,
                                                NotificationFanOutRepository.StoredNotification notification,
                                                String priority) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("notificationId", String.valueOf(event.notificationId()));
        data.put("type", nullToEmpty(notification.getType()));
        data.put("priority", priority);
        if (notification.getActionUrl() != null) {
            data.put("actionUrl", notification.getActionUrl());
        }
        return data;
    }

    // ==================== RULES ====================

    /** The event's priority; the stored row's when the event carries none. */
    private static String priority(NotificationStoredEvent event,
                                   NotificationFanOutRepository.StoredNotification notification) {
        String value = trimToNull(event.priority());
        if (value == null) {
            value = trimToNull(notification.getPriority());
        }
        return value == null ? NotificationPriority.NORMAL.name() : value.toUpperCase(Locale.ROOT);
    }

    static String dedupeKey(NotificationStoredEvent event, String channel) {
        return DEDUPE_PREFIX + event.notificationId() + ":" + channel;
    }

    private static QuietHours quietHours(NotificationFanOutRepository.Recipient recipient) {
        if (!Boolean.TRUE.equals(recipient.getQuietHoursEnabled())) {
            return QuietHours.NONE;
        }
        return new QuietHours(true, time(recipient.getQuietHoursStart()), time(recipient.getQuietHoursEnd()),
                recipient.getTimezone());
    }

    /** {@code HH:mm[:ss[.fraction]]} as PostgreSQL prints a TIME, or null when absent or unreadable. */
    private static LocalTime time(String value) {
        String text = trimToNull(value);
        if (text == null) {
            return null;
        }
        try {
            return LocalTime.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** A channel switch; no settings row means the column default (on). */
    private static boolean flag(Boolean value) {
        return value == null || value;
    }

    private static String stripTrailingSlashes(String value) {
        String base = value == null ? "" : value.trim();
        int end = base.length();
        while (end > 0 && base.charAt(end - 1) == '/') {
            end--;
        }
        return base.substring(0, end);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
