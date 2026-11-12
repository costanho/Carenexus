package com.carenexus.api.communications.notify;

import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.communications.notify.event.NotificationStoredEvent;
import com.carenexus.api.communications.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link NotificationPublisher} backed by the {@code notifications} table.
 *
 * Decision order of {@link #publish} (nothing is stored and empty is returned at the first match):
 * <ol>
 *   <li>the recipient has no account (logged at WARN, never a foreign-key failure);</li>
 *   <li>the recipient is the actor and the category is not SYSTEM;</li>
 *   <li>the recipient switched the category off ({@link NotificationPreferences});</li>
 *   <li>the dedupe key was already used for this recipient (INSERT ... ON CONFLICT DO NOTHING).</li>
 * </ol>
 * Otherwise the row is stored with the link for the recipient's role and a {@link NotificationStoredEvent} is
 * published. Only ids are logged, never titles or texts.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class DefaultNotificationPublisher implements NotificationPublisher {

    static final int MAX_TITLE_LENGTH = 80;
    static final int MAX_MESSAGE_LENGTH = 500;
    static final int MAX_RESOURCE_TYPE_LENGTH = 40;
    static final int MAX_DEDUPE_KEY_LENGTH = 160;
    static final int MAX_ACTION_URL_LENGTH = 500;

    private final NotificationRepository notificationRepository;
    private final NotificationRecipientRepository recipientRepository;
    private final NotificationPreferences preferences;
    private final AuthorizationService authorizationService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public Optional<Integer> publish(NotificationCommand command) {
        validate(command);
        if (isBlank(command.recipientSystemId())) {
            throw new IllegalArgumentException("Notification recipientSystemId is required");
        }
        String recipient = command.recipientSystemId().trim();
        return store(command, recipient, roles(List.of(recipient)).get(recipient));
    }

    @Override
    public void publishToPatientCircle(String patientSystemId, PatientPermission caregiverPermission,
                                       NotificationCommand template) {
        String patient = requirePatient(patientSystemId);
        Set<String> recipients = new LinkedHashSet<>();
        recipients.add(patient);
        if (caregiverPermission != null) {
            // Active, unexpired, unrevoked grants that give the permission (communication ones also need approval)
            recipients.addAll(authorizationService.caregiversWithPermission(patient, caregiverPermission));
        }
        fanOut(patient, recipients, template);
    }

    @Override
    public void publishToCareTeam(String patientSystemId, NotificationCommand template) {
        String patient = requirePatient(patientSystemId);
        fanOut(patient, new LinkedHashSet<>(authorizationService.relatedDoctors(patient)), template);
    }

    private void fanOut(String patientSystemId, Set<String> recipients, NotificationCommand template) {
        // The template's own recipient is ignored: every recipient gets a copy addressed to them
        validate(template);
        recipients.remove(null);
        if (recipients.isEmpty()) {
            return;
        }
        Map<String, String> roles = roles(recipients);
        for (String recipient : recipients) {
            NotificationCommand command = template.toBuilder()
                    .recipientSystemId(recipient)
                    .patientSystemId(patientSystemId)
                    .build();
            store(command, recipient, roles.get(recipient));
        }
    }

    /** The decision order for one validated command; {@code role} is null when the recipient has no account. */
    private Optional<Integer> store(NotificationCommand command, String recipient, String role) {
        if (role == null) {
            log.warn("Notification skipped: recipient {} has no account (category {})", recipient, command.category());
            return Optional.empty();
        }
        String actor = trimToNull(command.actorSystemId());
        if (recipient.equals(actor) && command.category() != NotificationCategory.SYSTEM) {
            return Optional.empty();
        }
        if (!preferences.allows(recipient, command.category(), command.priority())) {
            return Optional.empty();
        }

        String resourceType = normalizeResourceType(command.resourceType());
        String patient = trimToNull(command.patientSystemId());
        String dedupeKey = trimToNull(command.dedupeKey());
        String actionUrl = NotificationLinks.url(role, command.target(), command.params(), patient, resourceType,
                command.resourceId());
        if (actionUrl != null && actionUrl.length() > MAX_ACTION_URL_LENGTH) {
            throw new IllegalArgumentException("Notification link is longer than " + MAX_ACTION_URL_LENGTH + " characters");
        }

        List<Integer> stored = notificationRepository.insertIfAbsent(
                recipient,
                command.type().name(),
                cut(command.title().trim(), MAX_TITLE_LENGTH),
                cut(command.message().trim(), MAX_MESSAGE_LENGTH),
                actionUrl,
                command.type().icon(),
                command.priority().name(),
                command.expiresAt(),
                LocalDateTime.now(),
                patient,
                actor,
                command.category().name(),
                command.target() != null ? command.target().name() : null,
                resourceType,
                command.resourceId(),
                dedupeKey);
        if (stored.isEmpty()) {
            log.debug("Notification skipped: dedupe key already used for recipient {}", recipient);
            return Optional.empty();
        }
        Integer notificationId = stored.get(0);
        eventPublisher.publishEvent(new NotificationStoredEvent(notificationId, recipient, command.category().name(),
                command.priority().name(), dedupeKey));
        return Optional.of(notificationId);
    }

    private Map<String, String> roles(Collection<String> systemIds) {
        return recipientRepository.findRecipients(systemIds).stream()
                .filter(r -> r.getSystemId() != null && r.getRole() != null)
                .collect(Collectors.toMap(NotificationRecipientRepository.Recipient::getSystemId,
                        NotificationRecipientRepository.Recipient::getRole, (a, b) -> a));
    }

    // ==================== VALIDATION ====================

    /**
     * A missing required field or an over-long key is a programming error of the caller. The recipient is checked
     * by {@link #publish} only: the fan-out methods replace it.
     */
    private static void validate(NotificationCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Notification command is required");
        }
        if (command.type() == null) {
            throw new IllegalArgumentException("Notification type is required");
        }
        if (command.category() == null) {
            throw new IllegalArgumentException("Notification category is required");
        }
        if (isBlank(command.title())) {
            throw new IllegalArgumentException("Notification title is required");
        }
        if (isBlank(command.message())) {
            throw new IllegalArgumentException("Notification message is required");
        }
        if (command.dedupeKey() != null && command.dedupeKey().trim().length() > MAX_DEDUPE_KEY_LENGTH) {
            throw new IllegalArgumentException("Notification dedupeKey is longer than " + MAX_DEDUPE_KEY_LENGTH + " characters");
        }
        if (command.resourceType() != null && command.resourceType().trim().length() > MAX_RESOURCE_TYPE_LENGTH) {
            throw new IllegalArgumentException("Notification resourceType is longer than " + MAX_RESOURCE_TYPE_LENGTH + " characters");
        }
    }

    private static String requirePatient(String patientSystemId) {
        if (isBlank(patientSystemId)) {
            throw new IllegalArgumentException("patientSystemId is required");
        }
        return patientSystemId.trim();
    }

    private static String normalizeResourceType(String resourceType) {
        String value = trimToNull(resourceType);
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** First {@code max} characters (code points), so a cut never splits a surrogate pair. */
    private static String cut(String value, int max) {
        if (value.codePointCount(0, value.length()) <= max) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, max));
    }
}
