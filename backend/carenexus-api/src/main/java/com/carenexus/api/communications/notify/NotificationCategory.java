package com.carenexus.api.communications.notify;

/**
 * What a notification is about, from the recipient's point of view. Each category except {@link #SYSTEM} maps to
 * one {@code user_settings} toggle that can switch it off ({@link NotificationPreferences} holds the mapping).
 *
 * The category is independent of the {@link NotificationType}: the publisher does not enforce a pairing. The
 * conventional pairs are APPOINTMENT_* / CONSULTATION / VIDEO_CALL with APPOINTMENT, MEDICATION_REMINDER and
 * PRESCRIPTION with MEDICATION, LAB_RESULT and IMAGING_RESULT with LAB_RESULT, BILLING with PAYMENT, REFERRAL with
 * REFERRAL, MESSAGE with MESSAGE, everything else with SYSTEM (CRITICAL uses the type of its domain).
 */
public enum NotificationCategory {

    /** 24 hour appointment reminder: appointment_reminders. */
    APPOINTMENT_REMINDER,
    /** 1 hour appointment reminder: appointment_reminder_1h. */
    APPOINTMENT_REMINDER_1H,
    /** Booked, rescheduled, cancelled: appointment_change_alerts. */
    APPOINTMENT_CHANGE,
    /** A dose is due: medication_reminders. */
    MEDICATION_REMINDER,
    /** Prescription issued, changed, expiring: prescription_reminders. */
    PRESCRIPTION,
    /** lab_result_alerts. */
    LAB_RESULT,
    /** imaging_result_alerts. */
    IMAGING_RESULT,
    /** consultation_notifications. */
    CONSULTATION,
    /** A reading outside its range: vitals_alerts. */
    VITALS,
    /** referral_alerts. */
    REFERRAL,
    /** care_plan_update_alerts (off by default since V11). */
    CARE_PLAN,
    /** Caregiver access granted, changed, revoked, expired: access_change_alerts. */
    ACCESS_CHANGE,
    /** Sign-in from a new device: login_alerts. */
    LOGIN_ALERT,
    /** Invoices, payments, insurance claims: billing_notifications. */
    BILLING,
    /** video_call_alerts. */
    VIDEO_CALL,
    /** message_notifications. */
    MESSAGE,
    /**
     * Clinical alerts: critical_alerts. The toggle only applies to priorities LOW to HIGH; a notification with
     * priority CRITICAL is always delivered.
     */
    CRITICAL,
    /** Never suppressible: account security, verification decisions, access requests. */
    SYSTEM
}
