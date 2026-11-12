package com.carenexus.api.communications.notify;

import com.carenexus.api.common.service.PatientPermission;

import java.util.Optional;

/**
 * The one way to create an in-app notification (thread messages and caregiver approval requests keep their own
 * writers in NotificationService; nothing else inserts into {@code notifications}).
 *
 * Rules applied here, so callers do not re-check them:
 * <ul>
 *   <li>preference: the recipient's {@code user_settings} toggle for the command's category
 *       ({@link NotificationPreferences}); priority CRITICAL and category SYSTEM are always delivered;</li>
 *   <li>the actor is never notified about their own action, unless the category is SYSTEM;</li>
 *   <li>a repeated {@code dedupeKey} for the same recipient stores nothing and never fails the caller's
 *       transaction;</li>
 *   <li>the link is computed from the command's target and params ({@link NotificationLinks}) for the recipient's
 *       role;</li>
 *   <li>after the row is stored a {@code NotificationStoredEvent} is published; the delivery fan-out (email, SMS,
 *       push) listens to it. The publisher never calls the delivery module.</li>
 * </ul>
 * Every method joins the caller's transaction: a rollback of the origin removes the notification too.
 */
public interface NotificationPublisher {

    /**
     * Stores one notification.
     *
     * @return the notification id, or empty when nothing was stored: the recipient has no account, the recipient
     *         is the actor (and the category is not SYSTEM), the recipient switched the category off, or the
     *         dedupe key was already used for this recipient
     * @throws IllegalArgumentException when a required field is missing (programming error)
     */
    Optional<Integer> publish(NotificationCommand command);

    /**
     * Notifies the patient and every caregiver who holds {@code caregiverPermission} on an active, unexpired grant.
     * Each recipient gets their own command built from the template (recipient replaced, {@code patientSystemId}
     * set, link for their role) and goes through {@link #publish}. A null permission notifies the patient only.
     */
    void publishToPatientCircle(String patientSystemId, PatientPermission caregiverPermission,
                                NotificationCommand template);

    /**
     * Notifies the patient's related doctors (care team, appointments, consultations). Each doctor gets their own
     * command built from the template and goes through {@link #publish}.
     */
    void publishToCareTeam(String patientSystemId, NotificationCommand template);
}
