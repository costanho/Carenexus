package com.carenexus.api.communications.notify;

/**
 * What a notification opens. {@link NotificationLinks} turns a target into the web section of the recipient's
 * dashboard; a target with no section for the recipient's role gives an informational notification (no link).
 */
public enum NotificationTarget {
    APPOINTMENTS,
    CONSULTATIONS,
    PRESCRIPTIONS,
    MEDICATION_SCHEDULE,
    LAB_RESULTS,
    IMAGING_RESULTS,
    MEDICAL_RECORDS,
    DOCUMENTS,
    REFERRALS,
    CARE_PLANS,
    HEALTH_TRACKING,
    AI_ALERTS,
    BILLING,
    CAREGIVER_ACCESS,
    CARE_TEAM,
    MESSAGES,
    ACCOUNT_SECURITY,
    CORRECTIONS,
    ADMIN_CLINICIAN_QUEUE,
    ADMIN_ACCOUNT_REQUESTS,
    ADMIN_BILLING
}
