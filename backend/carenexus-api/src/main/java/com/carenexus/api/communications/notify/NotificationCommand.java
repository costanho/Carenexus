package com.carenexus.api.communications.notify;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One in-app notification to create, handed to {@link NotificationPublisher}.
 *
 * <pre>
 * publisher.publish(NotificationCommand.builder()
 *         .recipientSystemId(doctorSystemId)
 *         .type(NotificationType.APPOINTMENT)
 *         .category(NotificationCategory.APPOINTMENT_CHANGE)
 *         .title("New appointment")
 *         .message("Thomas Dube booked an appointment")
 *         .target(NotificationTarget.APPOINTMENTS)
 *         .resourceType("APPOINTMENT").resourceId(appointmentId)
 *         .patientSystemId(patientSystemId).actorSystemId(actorSystemId)
 *         .dedupeKey("APPT_BOOKED:" + appointmentId)
 *         .build());
 * </pre>
 *
 * Required: {@code recipientSystemId} (for {@code publish}; the fan-out methods replace it), {@code type},
 * {@code category}, {@code title}, {@code message}. A missing required field is a programming error and makes the
 * publisher throw {@link IllegalArgumentException}.
 *
 * @param recipientSystemId who receives it
 * @param type              stored type; clients key icon and colour on it
 * @param category          decides which {@code user_settings} toggle can switch it off
 * @param priority          default NORMAL; CRITICAL is always delivered
 * @param title             short and generic; cut to 80 characters
 * @param message           one generic sentence: who and what kind of thing, never a result value, diagnosis,
 *                          medication dose or message body; cut to 500 characters
 * @param target            what the notification opens; null = informational (no link)
 * @param params            link parameters: {@code id}, {@code tab} and pass-through keys such as {@code thread},
 *                          {@code appointmentId}, {@code consultationId}, {@code date}, {@code mode}
 * @param resourceType      upper snake case kind of the referenced row (max 40), e.g. APPOINTMENT
 * @param resourceId        id of the referenced row; also the link's {@code id} unless {@code params.id} is set
 * @param patientSystemId   the patient the event is about (set by the fan-out methods); null for account events
 * @param actorSystemId     who caused it; the actor is never notified about their own action (except category
 *                          SYSTEM); null for jobs
 * @param dedupeKey         {@code "<EVENT>:<resourceId>[:<discriminator>]"} (max 160), unique per recipient;
 *                          required for events and jobs, optional for direct user actions
 * @param expiresAt         clinic wall-clock time after which the notification is hidden; null = never
 */
public record NotificationCommand(String recipientSystemId, NotificationType type, NotificationCategory category,
                                  NotificationPriority priority, String title, String message,
                                  NotificationTarget target, Map<String, String> params, String resourceType,
                                  Integer resourceId, String patientSystemId, String actorSystemId, String dedupeKey,
                                  LocalDateTime expiresAt) {

    public NotificationCommand {
        priority = priority == null ? NotificationPriority.NORMAL : priority;
        params = copyOf(params);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A builder pre-filled with this command's values, for per-recipient copies of a template. */
    public Builder toBuilder() {
        return new Builder()
                .recipientSystemId(recipientSystemId)
                .type(type)
                .category(category)
                .priority(priority)
                .title(title)
                .message(message)
                .target(target)
                .params(params)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .patientSystemId(patientSystemId)
                .actorSystemId(actorSystemId)
                .dedupeKey(dedupeKey)
                .expiresAt(expiresAt);
    }

    /** Unmodifiable copy without null keys or values, keeping the insertion order. */
    private static Map<String, String> copyOf(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && value != null) {
                copy.put(key, value);
            }
        });
        return Collections.unmodifiableMap(copy);
    }

    public static final class Builder {

        private String recipientSystemId;
        private NotificationType type;
        private NotificationCategory category;
        private NotificationPriority priority = NotificationPriority.NORMAL;
        private String title;
        private String message;
        private NotificationTarget target;
        private final Map<String, String> params = new LinkedHashMap<>();
        private String resourceType;
        private Integer resourceId;
        private String patientSystemId;
        private String actorSystemId;
        private String dedupeKey;
        private LocalDateTime expiresAt;

        private Builder() {
        }

        public Builder recipientSystemId(String recipientSystemId) {
            this.recipientSystemId = recipientSystemId;
            return this;
        }

        public Builder type(NotificationType type) {
            this.type = type;
            return this;
        }

        public Builder category(NotificationCategory category) {
            this.category = category;
            return this;
        }

        public Builder priority(NotificationPriority priority) {
            this.priority = priority;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder target(NotificationTarget target) {
            this.target = target;
            return this;
        }

        /** Replaces all link parameters. */
        public Builder params(Map<String, String> params) {
            this.params.clear();
            if (params != null) {
                params.forEach(this::param);
            }
            return this;
        }

        /** Adds one link parameter; a null key or value is ignored. */
        public Builder param(String key, String value) {
            if (key != null && value != null) {
                this.params.put(key, value);
            }
            return this;
        }

        public Builder resourceType(String resourceType) {
            this.resourceType = resourceType;
            return this;
        }

        public Builder resourceId(Integer resourceId) {
            this.resourceId = resourceId;
            return this;
        }

        public Builder patientSystemId(String patientSystemId) {
            this.patientSystemId = patientSystemId;
            return this;
        }

        public Builder actorSystemId(String actorSystemId) {
            this.actorSystemId = actorSystemId;
            return this;
        }

        public Builder dedupeKey(String dedupeKey) {
            this.dedupeKey = dedupeKey;
            return this;
        }

        public Builder expiresAt(LocalDateTime expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        public NotificationCommand build() {
            return new NotificationCommand(recipientSystemId, type, category, priority, title, message, target,
                    params, resourceType, resourceId, patientSystemId, actorSystemId, dedupeKey, expiresAt);
        }
    }
}
