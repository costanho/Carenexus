package com.carenexus.api.common.event;

/**
 * Published by CaregiverService and CaregiverAccessExpirySweeper for the consent ledger: one event per recorded
 * change of a caregiver grant. {@code kind} is one of the constants below; {@code actorSystemId} is null when the
 * system recorded it (expiry). {@code guardianAttested} is true when the caregiver attested to being the legal
 * guardian (dependents without their own consent).
 */
public record CaregiverGrantRecordedEvent(
        Integer accessId, String patientSystemId, String caregiverSystemId, String actorSystemId, String kind,
        boolean guardianAttested) {

    public static final String GRANTED = "GRANTED";
    public static final String PERMISSIONS_CHANGED = "PERMISSIONS_CHANGED";
    public static final String COMMUNICATION_APPROVED = "COMMUNICATION_APPROVED";
    public static final String EXTENDED = "EXTENDED";
    public static final String NARROWED = "NARROWED";
    public static final String REVOKED = "REVOKED";
    public static final String EXPIRED = "EXPIRED";
}
