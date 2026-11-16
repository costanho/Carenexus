package com.carenexus.api.communications.notify.event;

/**
 * Published by NotificationPublisher after an in-app notification row was stored, inside the caller's transaction.
 * It is published exactly once per stored row and never for a command that was suppressed (preference, duplicate
 * dedupe key, recipient is the actor, unknown recipient).
 *
 * The delivery fan-out (email, SMS, push) listens with a plain {@code @EventListener}, so its outbox rows commit or
 * roll back together with the notification. The thread-message and caregiver-approval notifications written by
 * NotificationService do not publish this event: they stay in-app only.
 *
 * @param notificationId    id of the stored row
 * @param recipientSystemId who receives it
 * @param category          NotificationCategory name
 * @param priority          NotificationPriority name
 * @param dedupeKey         the command's dedupe key; null for direct user actions that passed none
 */
public record NotificationStoredEvent(Integer notificationId, String recipientSystemId, String category,
                                      String priority, String dedupeKey) {
}
