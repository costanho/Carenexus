package com.carenexus.api.ai.alert.model;

import com.carenexus.api.communications.notify.NotificationPriority;

import java.util.Locale;
import java.util.Optional;

/**
 * Alert priorities (database CHECK on {@code ai_alerts.priority}, V1) and the priority of the AI_ALERT notification
 * each one produces: LOW to LOW, MEDIUM to NORMAL, HIGH to HIGH, CRITICAL to CRITICAL.
 */
public enum AiAlertPriority {

    LOW(NotificationPriority.LOW),
    MEDIUM(NotificationPriority.NORMAL),
    HIGH(NotificationPriority.HIGH),
    CRITICAL(NotificationPriority.CRITICAL);

    private final NotificationPriority notificationPriority;

    AiAlertPriority(NotificationPriority notificationPriority) {
        this.notificationPriority = notificationPriority;
    }

    public NotificationPriority notificationPriority() {
        return notificationPriority;
    }

    /** The priority named by {@code value} (trimmed, any case), or empty for null, blank or an unknown name. */
    public static Optional<AiAlertPriority> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (AiAlertPriority priority : values()) {
            if (priority.name().equals(normalized)) {
                return Optional.of(priority);
            }
        }
        return Optional.empty();
    }
}
