package com.carenexus.api.lifecycle.service;

/**
 * The steps of an account clean-up run, in the order they are reported in the audit summary
 * ({@code failed=APPOINTMENTS:1,REMINDERS:1}).
 */
public enum DeactivationStep {
    /** Future SCHEDULED / RESCHEDULED appointments cancelled through AppointmentService.cancelBySystem. */
    APPOINTMENTS,
    /** Caregiver grants not revoked yet, revoked through CaregiverService.revokeCaregiverAccessAsPatient. */
    GRANTS,
    /** Active reminders addressed to a closed patient switched off. */
    REMINDERS,
    /** Pending caregiver access requests cancelled. */
    ACCESS_REQUESTS,
    /** Pending referrals addressed to a doctor as specialist, counted only. */
    REFERRALS
}
