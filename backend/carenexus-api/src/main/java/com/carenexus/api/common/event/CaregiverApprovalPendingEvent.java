package com.carenexus.api.common.event;

/**
 * Published by CaregiverService (inside the changing transaction) when a grant starts or stops waiting for the
 * patient's approval of a communication permission (can_message_care_team / can_join_calls requested but not
 * approved). Only transitions are published.
 *
 * pending = true: the patient gets an in-app notification pointing at their caregiver-access screen.
 * pending = false (approved, request withdrawn, grant revoked): that notification is marked read.
 */
public record CaregiverApprovalPendingEvent(
        Integer accessId,
        String patientSystemId,
        String caregiverSystemId,
        boolean pending,
        boolean messagingRequested,
        boolean callsRequested) {
}
