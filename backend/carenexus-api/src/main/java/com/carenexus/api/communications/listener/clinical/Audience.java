package com.carenexus.api.communications.listener.clinical;

/**
 * Who a clinical notification is addressed to, as far as its wording is concerned. Titles are the same for every
 * audience; only the message differs.
 */
public enum Audience {
    /** The patient the event is about. */
    PATIENT,
    /** A caregiver holding the permission the notification needs on an active grant. */
    CAREGIVER,
    /** The doctor of the appointment (appointment notifications only). */
    DOCTOR
}
