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
import org.springframework.security.authentication.InsufficientAuthenticationException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AuthorizationService.requirePatientAccess and the role/self helpers (no database needed).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class AuthorizationServicePatientAccessTest {

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

    // ==================== PATIENT ====================

    @Test
    public void patient_canAccessOwnData_forAnyPermission() {
        as(PATIENT, "PATIENT");
        for (PatientPermission p : PatientPermission.values()) {
            assertDoesNotThrow(() -> authorizationService.requirePatientAccess(PATIENT, p));
        }
        verifyNoInteractions(auditService);
    }

    @Test
    public void patient_cannotAccessOtherPatient_andDenialIsAudited() {
        as(PATIENT, "PATIENT");

        AuthorizationException ex = assertThrows(AuthorizationException.class,
                () -> authorizationService.requirePatientAccess(OTHER_PATIENT, PatientPermission.RECORDS, 7));

        assertEquals("MEDICAL_RECORD", ex.getResourceType());
        assertEquals(7, ex.getResourceId());
        verify(auditService).logAccessDenied(eq(PATIENT), eq("MEDICAL_RECORD"), eq(7), eq(OTHER_PATIENT), isNull(), anyString());
    }

    // ==================== DOCTOR ====================

    @Test
    public void doctor_withCareRelationship_allowedReadAndWrite() {
        as(DOCTOR, "DOCTOR");
        when(relationshipService.isDoctorAssignedToPatient(DOCTOR, PATIENT)).thenReturn(true);

        assertDoesNotThrow(() -> authorizationService.requirePatientAccess(PATIENT, PatientPermission.LAB_RESULTS));
        assertDoesNotThrow(() -> authorizationService.requirePatientAccess(PATIENT, PatientPermission.MANAGE_CARE_PLAN));
    }

    @Test
    public void doctor_withoutRelationship_denied() {
        as(DOCTOR, "DOCTOR");
        when(relationshipService.isDoctorAssignedToPatient(DOCTOR, OTHER_PATIENT)).thenReturn(false);

        assertThrows(AuthorizationException.class,
                () -> authorizationService.requirePatientAccess(OTHER_PATIENT, PatientPermission.RECORDS));
        verify(auditService).logAccessDenied(eq(DOCTOR), anyString(), isNull(), eq(OTHER_PATIENT), isNull(), anyString());
    }

    // ==================== CAREGIVER ====================

    @Test
    public void caregiver_withoutActiveAccess_denied() {
        as(CAREGIVER, "CAREGIVER");
        when(relationshipService.getActiveDependentAccess(CAREGIVER, PATIENT)).thenReturn(Optional.empty());

        AuthorizationException ex = assertThrows(AuthorizationException.class,
                () -> authorizationService.requirePatientAccess(PATIENT, PatientPermission.PROFILE));
        assertEquals("You don't have active proxy access to this patient", ex.getMessage());
    }

    @Test
    public void caregiver_fullAccess_allowsEveryDataPermission_butNoCommunication() {
        as(CAREGIVER, "CAREGIVER");
        DependentAccess access = DependentAccess.builder().patientSystemId(PATIENT).accessLevel("FULL_ACCESS").build(); // all flags false
        when(relationshipService.getActiveDependentAccess(CAREGIVER, PATIENT)).thenReturn(Optional.of(access));

        for (PatientPermission p : PatientPermission.values()) {
            if (p.isCommunication()) {
                assertThrows(AuthorizationException.class,
                        () -> authorizationService.requirePatientAccess(PATIENT, p), p.name());
            } else {
                assertDoesNotThrow(() -> authorizationService.requirePatientAccess(PATIENT, p), p.name());
            }
        }
    }

    @Test
    public void caregiver_viewOnlyWithoutFlags_canSeeProfileButNotRecords() {
        as(CAREGIVER, "CAREGIVER");
        DependentAccess access = DependentAccess.builder().accessLevel("VIEW_ONLY").build();
        when(relationshipService.getActiveDependentAccess(CAREGIVER, PATIENT)).thenReturn(Optional.of(access));

        assertDoesNotThrow(() -> authorizationService.requirePatientAccess(PATIENT, PatientPermission.PROFILE));
        assertThrows(AuthorizationException.class,
                () -> authorizationService.requirePatientAccess(PATIENT, PatientPermission.RECORDS));
        assertThrows(AuthorizationException.class,
                () -> authorizationService.requirePatientAccess(PATIENT, PatientPermission.LAB_RESULTS));
    }

    @Test
    public void caregiver_perResourceFlags_areEnforced() {
        as(CAREGIVER, "CAREGIVER");
        DependentAccess access = DependentAccess.builder()
                .accessLevel("CUSTOM")
                .canViewRecords(true)
                .canViewMedications(true)
                .canViewLabResults(false)
                .canViewImaging(false)
                .canViewAppointments(true)
                .canScheduleAppointments(true)
                .build();
        when(relationshipService.getActiveDependentAccess(CAREGIVER, PATIENT)).thenReturn(Optional.of(access));

        assertDoesNotThrow(() -> authorizationService.requirePatientAccess(PATIENT, PatientPermission.RECORDS));
        assertDoesNotThrow(() -> authorizationService.requirePatientAccess(PATIENT, PatientPermission.MEDICATIONS));
        assertDoesNotThrow(() -> authorizationService.requirePatientAccess(PATIENT, PatientPermission.APPOINTMENTS));
        assertDoesNotThrow(() -> authorizationService.requirePatientAccess(PATIENT, PatientPermission.MANAGE_APPOINTMENTS));
        assertThrows(AuthorizationException.class,
                () -> authorizationService.requirePatientAccess(PATIENT, PatientPermission.LAB_RESULTS));
        assertThrows(AuthorizationException.class,
                () -> authorizationService.requirePatientAccess(PATIENT, PatientPermission.IMAGING));
        assertThrows(AuthorizationException.class,
                () -> authorizationService.requirePatientAccess(PATIENT, PatientPermission.MANAGE_CARE_PLAN));
    }

    @Test
    public void caregiver_viewOnly_cannotWriteEvenWithFlag() {
        as(CAREGIVER, "CAREGIVER");
        DependentAccess access = DependentAccess.builder()
                .accessLevel("VIEW_ONLY")
                .canViewAppointments(true)
                .canScheduleAppointments(true)
                .build();
        when(relationshipService.getActiveDependentAccess(CAREGIVER, PATIENT)).thenReturn(Optional.of(access));

        assertDoesNotThrow(() -> authorizationService.requirePatientAccess(PATIENT, PatientPermission.APPOINTMENTS));
        assertThrows(AuthorizationException.class,
                () -> authorizationService.requirePatientAccess(PATIENT, PatientPermission.MANAGE_APPOINTMENTS));
    }

    @Test
    public void canAccessPatient_isNonThrowingAndDoesNotAudit() {
        as(CAREGIVER, "CAREGIVER");
        DependentAccess access = DependentAccess.builder().accessLevel("CUSTOM").canViewRecords(false).build();
        when(relationshipService.getActiveDependentAccess(CAREGIVER, PATIENT)).thenReturn(Optional.of(access));

        assertTrue(authorizationService.canAccessPatient(PATIENT, PatientPermission.PROFILE));
        assertFalse(authorizationService.canAccessPatient(PATIENT, PatientPermission.RECORDS));
        verifyNoInteractions(auditService);
    }

    // ==================== ADMIN / OTHER ====================

    @Test
    public void admin_allowed() {
        as(ADMIN, "ADMIN");
        assertDoesNotThrow(() -> authorizationService.requirePatientAccess(PATIENT, PatientPermission.RECORDS));
        verifyNoInteractions(relationshipService);
    }

    @Test
    public void unknownRole_denied() {
        as("X-1", "FACILITY_ADMIN");
        assertThrows(AuthorizationException.class,
                () -> authorizationService.requirePatientAccess(PATIENT, PatientPermission.RECORDS));
    }

    @Test
    public void unauthenticated_is401() {
        as(null, null);
        assertThrows(InsufficientAuthenticationException.class,
                () -> authorizationService.requirePatientAccess(PATIENT, PatientPermission.RECORDS));
    }

    @Test
    public void auditFailure_doesNotMaskDenial() {
        as(PATIENT, "PATIENT");
        doThrow(new RuntimeException("db down")).when(auditService)
                .logAccessDenied(any(), any(), any(), any(), any(), any());

        assertThrows(AuthorizationException.class,
                () -> authorizationService.requirePatientAccess(OTHER_PATIENT, PatientPermission.RECORDS));
    }

    // ==================== SELF / ROLE HELPERS ====================

    @Test
    public void requireSelfOrAdmin() {
        as(PATIENT, "PATIENT");
        assertDoesNotThrow(() -> authorizationService.requireSelfOrAdmin(PATIENT));
        assertThrows(AuthorizationException.class, () -> authorizationService.requireSelfOrAdmin(OTHER_PATIENT));

        as(ADMIN, "ADMIN");
        assertDoesNotThrow(() -> authorizationService.requireSelfOrAdmin(OTHER_PATIENT));
    }

    @Test
    public void requireDoctorSelf() {
        as(DOCTOR, "DOCTOR");
        assertDoesNotThrow(() -> authorizationService.requireDoctorSelf(DOCTOR));
        assertThrows(AuthorizationException.class, () -> authorizationService.requireDoctorSelf("DOC-00002"));

        // a patient whose systemId happens to be passed is still not a doctor
        as(PATIENT, "PATIENT");
        assertThrows(AuthorizationException.class, () -> authorizationService.requireDoctorSelf(PATIENT));

        as(ADMIN, "ADMIN");
        assertDoesNotThrow(() -> authorizationService.requireDoctorSelf(DOCTOR));
    }

    @Test
    public void requireRole_varargs() {
        as(DOCTOR, "DOCTOR");
        assertDoesNotThrow(() -> authorizationService.requireRole("DOCTOR"));
        assertDoesNotThrow(() -> authorizationService.requireRole("ADMIN", "DOCTOR"));
        assertThrows(AuthorizationException.class, () -> authorizationService.requireRole("PATIENT", "CAREGIVER"));
    }

    @Test
    public void legacyAuthorizePatientDataAccess_mapsResourceType() {
        assertEquals(PatientPermission.LAB_RESULTS, AuthorizationService.permissionForResourceType("LAB_RESULTS"));
        assertEquals(PatientPermission.IMAGING, AuthorizationService.permissionForResourceType("IMAGING_RESULT"));
        assertEquals(PatientPermission.MEDICATIONS, AuthorizationService.permissionForResourceType("PRESCRIPTION"));
        assertEquals(PatientPermission.RECORDS, AuthorizationService.permissionForResourceType("HEALTH_METRICS"));
        assertEquals(PatientPermission.RECORDS, AuthorizationService.permissionForResourceType(null));
    }
}
