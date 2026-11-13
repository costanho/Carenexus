package com.carenexus.api.ai.alert;

import com.carenexus.api.ai.alert.model.AiAlertPriority;
import com.carenexus.api.ai.alert.model.AiAlertSourceType;
import com.carenexus.api.ai.alert.model.AiAlertType;
import com.carenexus.api.ai.alert.repository.AiAlertRepository;
import com.carenexus.api.common.event.AiAlertRaisedEvent;
import com.carenexus.api.communications.notify.NotificationCategory;
import com.carenexus.api.communications.notify.NotificationCommand;
import com.carenexus.api.communications.notify.NotificationPublisher;
import com.carenexus.api.communications.notify.NotificationTarget;
import com.carenexus.api.communications.notify.NotificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The one way to raise an AI alert (CONTRACTS.v2 6.1, signature frozen). Called by the alert generators and the
 * detection pipeline, never over HTTP, so it performs no caller authorization.
 * <p>
 * {@link #raise} joins the caller's transaction and:
 * <ol>
 *   <li>validates the command (a broken rule is a programming error: {@link IllegalArgumentException});</li>
 *   <li>inserts one {@code ai_alerts} row with {@code INSERT ... ON CONFLICT (dedupe_key) DO NOTHING}; a repeated
 *       dedupe key inserts nothing, publishes nothing and returns empty without aborting the caller's
 *       transaction;</li>
 *   <li>publishes {@link AiAlertRaisedEvent};</li>
 *   <li>sends one AI_ALERT notification (category CRITICAL, mapped priority, target AI_ALERTS, id = alert id,
 *       dedupe key {@code AI_ALERT:<alertId>}) to the addressed doctor, or, when the command names none, to every
 *       doctor related to the patient. The text is generic and never carries a clinical value. Patients,
 *       caregivers and administrators are never notified from here.</li>
 * </ol>
 * This is the only path by which doctors are notified about critical results and vitals.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class AiAlertService {

    static final String RESOURCE = "AI_ALERT";
    static final int MAX_TITLE_LENGTH = 160;
    static final int MAX_DEDUPE_KEY_LENGTH = 160;

    static final String NOTIFICATION_TITLE = "New clinical alert";
    static final String NOTIFICATION_TITLE_CRITICAL = "Critical clinical alert";
    static final String NOTIFICATION_MESSAGE =
            "An automated screening alert was raised for one of your patients. Open Clinical Alerts to review it.";

    private final AiAlertRepository aiAlertRepository;
    private final NotificationPublisher notificationPublisher;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /**
     * Stores an alert and notifies the addressed doctors.
     *
     * @return the new alert id, or empty when an alert with the command's dedupe key already exists
     * @throws IllegalArgumentException when the command breaks a rule of {@link AiAlertCommand}
     */
    public Optional<Integer> raise(AiAlertCommand command) {
        ValidCommand valid = validate(command);

        List<Integer> inserted = aiAlertRepository.insertIfAbsent(
                valid.patientSystemId(),
                valid.doctorSystemId(),
                valid.detectionId(),
                valid.type().name(),
                valid.title(),
                valid.message(),
                valid.priority().name(),
                command.actionRequired(),
                valid.suggestedAction(),
                valid.sourceType() != null ? valid.sourceType().name() : null,
                valid.sourceId(),
                valid.dedupeKey(),
                LocalDateTime.now(clock));
        if (inserted.isEmpty()) {
            log.debug("AI alert skipped: dedupe key already used (patient {})", valid.patientSystemId());
            return Optional.empty();
        }
        Integer alertId = inserted.get(0);
        log.info("AI alert {} raised: type {}, priority {}, patient {}", alertId, valid.type(), valid.priority(),
                valid.patientSystemId());

        eventPublisher.publishEvent(new AiAlertRaisedEvent(alertId, valid.patientSystemId(), valid.doctorSystemId(),
                valid.type().name(), valid.priority().name()));
        notifyDoctors(alertId, valid);
        return Optional.of(alertId);
    }

    private void notifyDoctors(Integer alertId, ValidCommand valid) {
        NotificationCommand.Builder notification = NotificationCommand.builder()
                .type(NotificationType.AI_ALERT)
                .category(NotificationCategory.CRITICAL)
                .priority(valid.priority().notificationPriority())
                .title(valid.priority() == AiAlertPriority.CRITICAL ? NOTIFICATION_TITLE_CRITICAL : NOTIFICATION_TITLE)
                .message(NOTIFICATION_MESSAGE)
                .target(NotificationTarget.AI_ALERTS)
                .param("id", String.valueOf(alertId))
                .resourceType(RESOURCE)
                .resourceId(alertId)
                .patientSystemId(valid.patientSystemId())
                .dedupeKey(RESOURCE + ":" + alertId);
        if (valid.doctorSystemId() != null) {
            notificationPublisher.publish(notification.recipientSystemId(valid.doctorSystemId()).build());
        } else {
            // Every doctor related to the patient (care team, appointments, consultations); possibly nobody.
            notificationPublisher.publishToCareTeam(valid.patientSystemId(), notification.build());
        }
    }

    // ==================== VALIDATION ====================

    private record ValidCommand(String patientSystemId, String doctorSystemId, AiAlertType type,
                                AiAlertPriority priority, String title, String message, String suggestedAction,
                                AiAlertSourceType sourceType, Integer sourceId, Integer detectionId,
                                String dedupeKey) {
    }

    private ValidCommand validate(AiAlertCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("AI alert command is required");
        }
        String patient = trimToNull(command.patientSystemId());
        if (patient == null) {
            throw new IllegalArgumentException("AI alert patientSystemId is required");
        }
        if (!aiAlertRepository.patientExists(patient)) {
            throw new IllegalArgumentException("AI alert patient does not exist: " + patient);
        }
        String doctor = trimToNull(command.doctorSystemId());
        if (doctor != null && !aiAlertRepository.doctorExists(doctor)) {
            throw new IllegalArgumentException("AI alert doctor does not exist: " + doctor);
        }
        AiAlertType type = AiAlertType.parse(command.alertType())
                .orElseThrow(() -> new IllegalArgumentException("Unknown AI alert type: " + command.alertType()));
        AiAlertPriority priority = AiAlertPriority.parse(command.priority())
                .orElseThrow(() -> new IllegalArgumentException("Unknown AI alert priority: " + command.priority()));
        String message = trimToNull(command.message());
        if (message == null) {
            throw new IllegalArgumentException("AI alert message is required");
        }

        String rawSourceType = trimToNull(command.sourceType());
        if ((rawSourceType == null) != (command.sourceId() == null)) {
            throw new IllegalArgumentException("AI alert sourceType and sourceId must be set together");
        }
        AiAlertSourceType sourceType = null;
        if (rawSourceType != null) {
            sourceType = AiAlertSourceType.parse(rawSourceType)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown AI alert source type: " + rawSourceType));
        }

        Integer detectionId = command.detectionId();
        if (type == AiAlertType.DISEASE_RISK && detectionId == null) {
            throw new IllegalArgumentException("A DISEASE_RISK alert needs its detectionId");
        }
        if (detectionId != null && !aiAlertRepository.detectionExists(detectionId)) {
            throw new IllegalArgumentException("AI alert detection does not exist: " + detectionId);
        }

        String dedupeKey = trimToNull(command.dedupeKey());
        if (dedupeKey != null && dedupeKey.length() > MAX_DEDUPE_KEY_LENGTH) {
            throw new IllegalArgumentException("AI alert dedupeKey is longer than " + MAX_DEDUPE_KEY_LENGTH + " characters");
        }

        String title = trimToNull(command.title());
        title = title == null ? type.defaultTitle() : cut(title, MAX_TITLE_LENGTH);

        return new ValidCommand(patient, doctor, type, priority, title, message, trimToNull(command.suggestedAction()),
                sourceType, command.sourceId(), detectionId, dedupeKey);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** First {@code max} characters (code points), so a cut never splits a surrogate pair. */
    private static String cut(String value, int max) {
        if (value.codePointCount(0, value.length()) <= max) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, max)).trim();
    }
}
