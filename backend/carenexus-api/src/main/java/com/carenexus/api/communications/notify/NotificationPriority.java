package com.carenexus.api.communications.notify;

/**
 * Notification priority stored in {@code notifications.priority} (the names are the values of the V1 CHECK
 * constraint). {@link #CRITICAL} is always delivered, whatever the recipient's preferences say.
 */
public enum NotificationPriority {
    LOW, NORMAL, HIGH, CRITICAL
}
