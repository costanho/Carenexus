package com.carenexus.api.common.event;

/**
 * Published (inside the changing transaction) after every successful change to a dependent_access grant:
 * patient grant / change / revoke, admin change / revoke, caregiver narrowing, dependent creation.
 * The flags are the EFFECTIVE values computed after the save.
 *
 * Consumers (messaging, calls) remove the caregiver from conversations / calls when access is gone.
 */
public record CaregiverAccessChangedEvent(
        Integer accessId,
        String patientSystemId,
        String caregiverSystemId,
        boolean revoked,            // true when the grant was revoked (or is no longer active)
        boolean messagingAllowed,   // effective PatientPermission.MESSAGING after the change
        boolean joinCallsAllowed) { // effective PatientPermission.JOIN_CALLS after the change
}
