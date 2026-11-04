package com.carenexus.api.common.event;

/**
 * Published by CaregiverService (inside the changing transaction) when a grant's MESSAGING permission becomes
 * effective again after it was not: a revoked grant re-granted, a lapsed grant extended, messaging re-approved.
 *
 * The messaging listener handles it SYNCHRONOUSLY in the same transaction and ends any conversation membership
 * that survived the gap, so the grant change cannot commit while a stale membership (and its old history
 * boundary) remains. This does not depend on the AFTER_COMMIT clean-up of {@link CaregiverAccessChangedEvent}
 * having succeeded earlier.
 */
public record CaregiverMessagingRegainedEvent(
        Integer accessId,
        String patientSystemId,
        String caregiverSystemId,
        boolean wasExpired) {   // true when the gap was an expiry (else revocation / withdrawn messaging)
}
