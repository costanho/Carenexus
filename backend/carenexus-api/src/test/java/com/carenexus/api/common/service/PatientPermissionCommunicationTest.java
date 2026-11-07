package com.carenexus.api.common.service;

import com.carenexus.api.core.model.DependentAccess;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Communication permissions (MESSAGING, JOIN_CALLS, deprecated MESSAGE_CARE_TEAM) need the flag AND the patient's
 * approval stamp; the access level is ignored for them.
 */
public class PatientPermissionCommunicationTest {

    private static final String PATIENT = "PAT-00001";
    private static final String CAREGIVER = "CGV-00001";

    private static DependentAccess.DependentAccessBuilder grant(String level) {
        return DependentAccess.builder().patientSystemId(PATIENT).caregiverSystemId(CAREGIVER).accessLevel(level);
    }

    @Test
    public void onlyMessagingJoinCallsAndAlias_areCommunication() {
        Set<PatientPermission> communication = EnumSet.noneOf(PatientPermission.class);
        for (PatientPermission p : PatientPermission.values()) {
            if (p.isCommunication()) communication.add(p);
        }
        assertEquals(EnumSet.of(PatientPermission.MESSAGING, PatientPermission.JOIN_CALLS,
                PatientPermission.MESSAGE_CARE_TEAM), communication);
    }

    @Test
    public void fullAccess_withoutFlagsOrApproval_grantsNoCommunication() {
        DependentAccess access = grant("FULL_ACCESS").canMessageCareTeam(true).canJoinCalls(true).build();
        assertFalse(PatientPermission.MESSAGING.isGrantedBy(access));
        assertFalse(PatientPermission.MESSAGE_CARE_TEAM.isGrantedBy(access));
        assertFalse(PatientPermission.JOIN_CALLS.isGrantedBy(access));
        // data permissions still follow FULL_ACCESS
        assertTrue(PatientPermission.RECORDS.isGrantedBy(access));
        assertTrue(PatientPermission.MANAGE_APPOINTMENTS.isGrantedBy(access));
    }

    @Test
    public void flagWithPatientApproval_grants_evenWhenViewOnly() {
        DependentAccess access = grant("VIEW_ONLY")
                .canMessageCareTeam(true).messagingApprovedAt(Instant.now()).messagingApprovedBySystemId(PATIENT)
                .canJoinCalls(true).callsApprovedAt(Instant.now()).callsApprovedBySystemId(PATIENT)
                .build();
        assertTrue(PatientPermission.MESSAGING.isGrantedBy(access));
        assertTrue(PatientPermission.MESSAGE_CARE_TEAM.isGrantedBy(access));
        assertTrue(PatientPermission.JOIN_CALLS.isGrantedBy(access));
    }

    @Test
    public void messagingAndCalls_areIndependent() {
        DependentAccess access = grant("CUSTOM")
                .canMessageCareTeam(true).messagingApprovedAt(Instant.now()).messagingApprovedBySystemId(PATIENT)
                .canJoinCalls(true) // requested, not approved
                .build();
        assertTrue(PatientPermission.MESSAGING.isGrantedBy(access));
        assertFalse(PatientPermission.JOIN_CALLS.isGrantedBy(access));
    }

    @Test
    public void approvalBySomeoneOtherThanThePatient_doesNotGrant() {
        DependentAccess access = grant("CUSTOM")
                .canMessageCareTeam(true).messagingApprovedAt(Instant.now()).messagingApprovedBySystemId(CAREGIVER)
                .canJoinCalls(true).callsApprovedAt(Instant.now()).callsApprovedBySystemId("ADM-00001")
                .build();
        assertFalse(PatientPermission.MESSAGING.isGrantedBy(access));
        assertFalse(PatientPermission.JOIN_CALLS.isGrantedBy(access));
    }

    @Test
    public void approvalWithoutFlag_orFlagWithoutTimestamp_doesNotGrant() {
        DependentAccess noFlag = grant("CUSTOM")
                .canMessageCareTeam(false).messagingApprovedAt(Instant.now()).messagingApprovedBySystemId(PATIENT)
                .build();
        DependentAccess noTime = grant("CUSTOM")
                .canJoinCalls(true).callsApprovedBySystemId(PATIENT)
                .build();
        assertFalse(PatientPermission.MESSAGING.isGrantedBy(noFlag));
        assertFalse(PatientPermission.JOIN_CALLS.isGrantedBy(noTime));
        assertTrue(PatientPermission.JOIN_CALLS.isRequestedBy(noTime));
    }

    @Test
    public void nullAccess_grantsNothing() {
        assertFalse(PatientPermission.MESSAGING.isGrantedBy(null));
        assertFalse(PatientPermission.JOIN_CALLS.isRequestedBy(null));
    }
}
