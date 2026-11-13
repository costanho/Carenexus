package com.carenexus.api.communications.notify;

/**
 * Notification type stored in {@code notifications.type} (the names are the values of the V1 CHECK constraint).
 * Clients key the icon and colour of a notification on the type.
 */
public enum NotificationType {

    APPOINTMENT("calendar-outline"),
    MEDICATION("medkit-outline"),
    LAB_RESULT("flask-outline"),
    MESSAGE("chatbubble-outline"),
    AI_ALERT("warning-outline"),
    SYSTEM("information-circle-outline"),
    PAYMENT("card-outline"),
    REFERRAL("swap-horizontal-outline");

    private final String icon;

    NotificationType(String icon) {
        this.icon = icon;
    }

    /** Icon name stored in {@code notifications.icon} for rows of this type. */
    public String icon() {
        return icon;
    }
}
