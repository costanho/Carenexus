package com.carenexus.api.common.service;

import com.carenexus.api.common.config.UserContext;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.core.model.DependentAccess;
import com.carenexus.api.core.service.RelationshipService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * requirePatientAccess / hasCaregiverPermission for MESSAGING and JOIN_CALLS.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class AuthorizationServiceCommunicationTest {

    private static final String PATIENT = "PAT-00001";
    private static final String OTHER_PATIENT = "PAT-00002";
    private static final String DOCTOR = "DOC-00001";
    private static final String CAREGIVER = "CGV-00001";
    private static final String ADMIN = "ADM-00001";

    @Mock
    private UserContext userContext;

    @Mock
    private AuditService auditService;

    @Mock
    private RelationshipService relationshipService;

    @InjectMocks
    private AuthorizationService authorizationService;

    private void as(String systemId, String role) {
        when(userContext.getCurrentUserSystemId()).thenReturn(systemId);
        when(userContext.getCurrentUserRole()).thenReturn(role);
    }

    private static DependentAccess approvedMessagingOnly() {
        return DependentAccess.builder()
                .patientSystemId(PATIENT).caregiverSystemId(CAREGIVER).accessLevel("VIEW_ONLY")
                .canMessageCareTeam(true).messagingApprovedAt(Instant.now()).messagingApprovedBySystemId(PATIENT)
                .canJoinCalls(true) // requested but not approved
                .build();
    }

    @Test
    public void patientSelf_andRelatedDoctor_andAdmin_areAllowed() {
        as(PATIENT, "PATIENT");
        assertDoesNotThrow(() -> authorizationService.requirePatientAccess(PATIENT, PatientPermission.MESSAGING));
        assertDoesNotThrow(() -> authorizationService.requirePatientAccess(PATIENT, PatientPermission.JOIN_CALLS));
        assertThrows(AuthorizationException.class,
                () -> authorizationService.requirePatientAccess(OTHER_PATIENT, PatientPermission.MESSAGING));

        as(DOCTOR, "DOCTOR");
        when(relationshipService.isDoctorAssignedToPatient(DOCTOR, PATIENT)).thenReturn(true);
        assertDoesNotThrow(() -> authorizationService.requirePatientAccess(PATIENT, PatientPermission.JOIN_CALLS));

        as(ADMIN, "ADMIN");
        assertDoesNotThrow(() -> authorizationService.requirePatientAccess(PATIENT, PatientPermission.MESSAGING));
    }

    @Test
    public void caregiver_needsApproval_perPermission_andDenialIsAudited() {
        as(CAREGIVER, "CAREGIVER");
        when(relationshipService.getActiveDependentAccess(CAREGIVER, PATIENT)).thenReturn(Optional.of(approvedMessagingOnly()));

        assertDoesNotThrow(() -> authorizationService.requirePatientAccess(PATIENT, PatientPermission.MESSAGING));
        AuthorizationException ex = assertThrows(AuthorizationException.class,
                () -> authorizationService.requirePatientAccess(PATIENT, PatientPermission.JOIN_CALLS));
        assertEquals("Your proxy access for this patient does not allow joining video calls", ex.getMessage());
        assertEquals("CALL", ex.getResourceType());
        verify(auditService).logAccessDenied(eq(CAREGIVER), eq("CALL"), isNull(), eq(PATIENT), isNull(), anyString());
    }

    @Test
    public void caregiver_fullAccessUnapproved_deniedMessaging_withMessage() {
        as(CAREGIVER, "CAREGIVER");
        DependentAccess full = DependentAccess.builder()
                .patientSystemId(PATIENT).caregiverSystemId(CAREGIVER).accessLevel("FULL_ACCESS")
                .canMessageCareTeam(true).canJoinCalls(true).build();
        when(relationshipService.getActiveDependentAccess(CAREGIVER, PATIENT)).thenReturn(Optional.of(full));

        AuthorizationException ex = assertThrows(AuthorizationException.class,
                () -> authorizationService.requirePatientAccess(PATIENT, PatientPermission.MESSAGING));
        assertEquals("Your proxy access for this patient does not allow messaging the care team", ex.getMessage());
        assertThrows(AuthorizationException.class,
                () -> authorizationService.requirePatientAccess(PATIENT, PatientPermission.MESSAGE_CARE_TEAM));
        assertFalse(authorizationService.canAccessPatient(PATIENT, PatientPermission.JOIN_CALLS));
    }

    @Test
    public void hasCaregiverPermission_isNonThrowing_andDoesNotAudit() {
        when(relationshipService.getActiveDependentAccess(CAREGIVER, PATIENT)).thenReturn(Optional.of(approvedMessagingOnly()));
        when(relationshipService.getActiveDependentAccess(CAREGIVER, OTHER_PATIENT)).thenReturn(Optional.empty());

        assertTrue(authorizationService.hasCaregiverPermission(CAREGIVER, PATIENT, PatientPermission.MESSAGING));
        assertFalse(authorizationService.hasCaregiverPermission(CAREGIVER, PATIENT, PatientPermission.JOIN_CALLS));
        assertFalse(authorizationService.hasCaregiverPermission(CAREGIVER, OTHER_PATIENT, PatientPermission.MESSAGING));
        assertFalse(authorizationService.hasCaregiverPermission(null, PATIENT, PatientPermission.MESSAGING));
        assertFalse(authorizationService.hasCaregiverPermission(CAREGIVER, PATIENT, null));
        verifyNoInteractions(auditService);
        verifyNoInteractions(userContext); // works without a request context
    }
}
