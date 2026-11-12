package com.carenexus.api.account.sessions.listener;

import com.carenexus.api.account.sessions.repository.SessionDevice;
import com.carenexus.api.account.sessions.repository.SessionTokenRepository;
import com.carenexus.api.common.event.UserLoggedInEvent;
import com.carenexus.api.communications.notify.NotificationCategory;
import com.carenexus.api.communications.notify.NotificationCommand;
import com.carenexus.api.communications.notify.NotificationPriority;
import com.carenexus.api.communications.notify.NotificationPublisher;
import com.carenexus.api.communications.notify.NotificationTarget;
import com.carenexus.api.communications.notify.NotificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Raises a login alert (in-app {@code SYSTEM} notification, category {@code LOGIN_ALERT}) when an account is signed
 * in to from a device it has not used before.
 *
 * The device signature is {@code deviceType} plus {@code deviceInfo}, each trimmed and lower-cased (null as empty);
 * the IP address is not part of it. An alert is raised when
 * <ol>
 *   <li>the new sign-in has a non-empty signature (the client sent a device type or description),</li>
 *   <li>no earlier session of the user still stored in {@code refresh_tokens} (active or ended) has the same
 *       signature, and</li>
 *   <li>the user has at least one earlier stored session with a non-empty signature (a first sign-in, or the first
 *       sign-in from a client that sends device fields, raises nothing).</li>
 * </ol>
 * The publisher drops the alert when the user switched {@code loginAlerts} off; external channels (email, SMS,
 * push) follow from the notification fan-out. The text never contains the free-text device description or the IP
 * address, because both are client-supplied.
 *
 * Runs after the sign-in committed, in its own transaction; a failure is logged and never fails the sign-in.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginAlertListener {

    static final String TITLE = "New sign-in to your account";
    static final String MESSAGE_WITH_LABEL =
            "Your account was signed in to on a new device (%s). If this was not you, change your password.";
    static final String MESSAGE_WITHOUT_LABEL =
            "Your account was signed in to on a new device. If this was not you, change your password.";
    static final String RESOURCE_TYPE = "SESSION";
    static final String DEDUPE_PREFIX = "LOGIN_ALERT:";

    /** Device types named in the message (matched ignoring case, written as here). */
    private static final List<String> KNOWN_DEVICE_TYPES = List.of("Web", "iOS", "Android");

    /** Most stored sessions compared per sign-in (the newest first). */
    static final int MAX_SESSIONS_COMPARED = 1000;

    private final SessionTokenRepository sessionTokenRepository;
    private final NotificationPublisher notificationPublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUserLoggedIn(UserLoggedInEvent event) {
        try {
            alertIfNewDevice(event);
        } catch (RuntimeException e) {
            log.warn("Login alert for {} failed: {}", event.systemId(), e.getClass().getName());
        }
    }

    private void alertIfNewDevice(UserLoggedInEvent event) {
        if (event.systemId() == null) {
            return;
        }
        String signature = signature(event.deviceType(), event.deviceInfo());
        if (signature.isEmpty()) {
            return;
        }
        List<SessionDevice> sessions = sessionTokenRepository.findSessionDevices(event.systemId(),
                PageRequest.of(0, MAX_SESSIONS_COMPARED));

        // The new session: the most recently signed-in session from this device (the sign-in that just committed).
        Optional<SessionDevice> newSession = sessions.stream()
                .filter(session -> signature.equals(signature(session.deviceType(), session.deviceInfo())))
                .findFirst();
        if (newSession.isEmpty()) {
            return; // its rows are already gone; nothing to point at
        }
        int newFirstTokenId = newSession.get().firstTokenId();
        List<SessionDevice> earlier = sessions.stream()
                .filter(session -> session.firstTokenId() < newFirstTokenId)
                .toList();

        boolean knownDevice = earlier.stream()
                .anyMatch(session -> signature.equals(signature(session.deviceType(), session.deviceInfo())));
        boolean anyEarlierDevice = earlier.stream()
                .anyMatch(session -> !signature(session.deviceType(), session.deviceInfo()).isEmpty());
        if (knownDevice || !anyEarlierDevice) {
            return;
        }

        notificationPublisher.publish(NotificationCommand.builder()
                .recipientSystemId(event.systemId())
                .type(NotificationType.SYSTEM)
                .category(NotificationCategory.LOGIN_ALERT)
                .priority(NotificationPriority.NORMAL)
                .title(TITLE)
                .message(message(event.deviceType()))
                .target(NotificationTarget.ACCOUNT_SECURITY)
                .resourceType(RESOURCE_TYPE)
                .dedupeKey(DEDUPE_PREFIX + newSession.get().sessionId())
                .build());
    }

    /** Trimmed, lower-cased device type and description; empty when the client sent neither. */
    static String signature(String deviceType, String deviceInfo) {
        String type = normalize(deviceType);
        String info = normalize(deviceInfo);
        if (type.isEmpty() && info.isEmpty()) {
            return "";
        }
        // The separator cannot occur in either part after normalisation, so ("a", "b c") and ("a b", "c") differ.
        return type + "\u0000" + info;
    }

    static String message(String deviceType) {
        String type = deviceType == null ? "" : deviceType.trim();
        return KNOWN_DEVICE_TYPES.stream()
                .filter(known -> known.equalsIgnoreCase(type))
                .findFirst()
                .map(label -> String.format(MESSAGE_WITH_LABEL, label))
                .orElse(MESSAGE_WITHOUT_LABEL);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
