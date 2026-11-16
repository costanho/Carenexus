package com.carenexus.api.core.service;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.auth.service.AuthService;
import com.carenexus.api.common.event.CaregiverAccessChangedEvent;
import com.carenexus.api.common.event.CaregiverApprovalPendingEvent;
import com.carenexus.api.common.event.CaregiverMessagingRegainedEvent;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.core.dto.request.CreateDependentRequest;
import com.carenexus.api.core.dto.request.GrantCaregiverAccessRequest;
import com.carenexus.api.core.dto.request.UpdateDependentAccessRequest;
import com.carenexus.api.core.dto.response.CaregiverDependentResponse;
import com.carenexus.api.core.model.Caregiver;
import com.carenexus.api.core.model.DependentAccess;
import com.carenexus.api.core.model.Patient;
import com.carenexus.api.core.repository.CaregiverRepository;
import com.carenexus.api.core.repository.DependentAccessRepository;
import com.carenexus.api.core.repository.PatientRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Communication consent rules in CaregiverService: caregivers can never turn messaging / joining calls on, only the
 * patient approves, admin changes never approve, turning a flag off clears its approval, and every change publishes
 * CaregiverAccessChangedEvent with the effective values.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class CaregiverServiceConsentTest {

    private static final String PATIENT = "PAT-00001";
    private static final String CAREGIVER = "CGV-00001";
    private static final String ADMIN = "ADM-00001";
    private static final Integer ACCESS_ID = 5;

    @Mock private DependentAccessRepository dependentAccessRepository;
    @Mock private UserRepository userRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private CaregiverRepository caregiverRepository;
    @Mock private AppointmentService appointmentService;
    @Mock private ConsultationService consultationService;
    @Mock private AuthService authService;
    @Mock private AuditService auditService;
    @Mock private ObjectMapper objectMapper;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private CaregiverService caregiverService;

    @BeforeEach
    void setUp() {
        when(dependentAccessRepository.save(any(DependentAccess.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static DependentAccess activeGrant() {
        return DependentAccess.builder()
                .accessId(ACCESS_ID).patientSystemId(PATIENT).caregiverSystemId(CAREGIVER)
                .relationship("daughter").accessLevel("FULL_ACCESS")
                .canViewRecords(true).canViewAppointments(true)
                .authorizedDate(LocalDateTime.now().minusDays(10))
                .isActive(true)
                .build();
    }

    private void stored(DependentAccess access) {
        when(dependentAccessRepository.findById(access.getAccessId())).thenReturn(Optional.of(access));
    }

    private CaregiverAccessChangedEvent lastEvent() {
        List<CaregiverAccessChangedEvent> events = events(CaregiverAccessChangedEvent.class);
        assertFalse(events.isEmpty(), "a CaregiverAccessChangedEvent was published");
        return events.get(events.size() - 1);
    }

    /** Every published event of one type, in order (a change may also publish regained / approval events). */
    private <T> List<T> events(Class<T> type) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeast(0)).publishEvent(captor.capture());
        return captor.getAllValues().stream().filter(type::isInstance).map(type::cast).toList();
    }

    // ==================== CAREGIVER: NARROW ONLY ====================

    @Test
    public void caregiver_cannotTurnOnMessaging_orCalls_evenWithFullAccess() {
        DependentAccess access = activeGrant();
        stored(access);

        for (UpdateDependentAccessRequest request : List.of(
                UpdateDependentAccessRequest.builder().canMessageCareTeam(true).build(),
                UpdateDependentAccessRequest.builder().canJoinCalls(true).build())) {
            AuthorizationException ex = assertThrows(AuthorizationException.class,
                    () -> caregiverService.updateDependentAccess(CAREGIVER, ACCESS_ID, request));
            assertEquals(CaregiverService.ONLY_REDUCE_MESSAGE, ex.getMessage());
        }
        assertFalse(access.getCanMessageCareTeam());
        assertFalse(access.getCanJoinCalls());
        verify(dependentAccessRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    public void caregiver_requestedButUnapprovedFlag_resentTrue_staysUnapproved() {
        DependentAccess access = activeGrant();
        access.setCanMessageCareTeam(true); // requested at creation, never approved
        stored(access);

        CaregiverDependentResponse response = caregiverService.updateDependentAccess(CAREGIVER, ACCESS_ID,
                UpdateDependentAccessRequest.builder().canMessageCareTeam(true).build());

        assertNull(access.getMessagingApprovedAt());
        assertFalse(response.isMessagingAllowed());
        assertTrue(response.isPendingApproval());
    }

    @Test
    public void caregiver_turningOffApprovedMessaging_clearsApproval_andPublishes() {
        DependentAccess access = activeGrant();
        access.setCanMessageCareTeam(true);
        access.setMessagingApprovedAt(Instant.now());
        access.setMessagingApprovedBySystemId(PATIENT);
        access.setCanJoinCalls(true);
        access.setCallsApprovedAt(Instant.now());
        access.setCallsApprovedBySystemId(PATIENT);
        stored(access);

        caregiverService.updateDependentAccess(CAREGIVER, ACCESS_ID,
                UpdateDependentAccessRequest.builder().canMessageCareTeam(false).build());

        assertFalse(access.getCanMessageCareTeam());
        assertNull(access.getMessagingApprovedAt());
        assertNull(access.getMessagingApprovedBySystemId());
        assertNotNull(access.getCallsApprovedAt()); // untouched
        CaregiverAccessChangedEvent event = lastEvent();
        assertEquals(new CaregiverAccessChangedEvent(ACCESS_ID, PATIENT, CAREGIVER, false, false, true), event);
    }

    @Test
    public void caregiver_otherCaregiversGrant_forbidden() {
        stored(activeGrant());
        assertThrows(AuthorizationException.class, () -> caregiverService.updateDependentAccess("CGV-00002", ACCESS_ID,
                UpdateDependentAccessRequest.builder().canViewRecords(false).build()));
    }

    @Test
    public void createDependent_storesCommunicationAsRequests_andFullAccessDoesNotImplyThem() {
        when(caregiverRepository.findBySystemId(CAREGIVER)).thenReturn(Optional.of(new Caregiver()));
        User created = new User();
        created.setSystemId(PATIENT);
        when(authService.createActiveUserAccount(any(), any(), any(), any(), any(), eq("PATIENT"))).thenReturn(created);

        CreateDependentRequest full = CreateDependentRequest.builder()
                .firstName("Thomas").lastName("Dube").email("t@example.com").password("Secret123")
                .relationship("father").accessLevel("FULL_ACCESS").build();
        caregiverService.createDependent(CAREGIVER, full);

        ArgumentCaptor<DependentAccess> saved = ArgumentCaptor.forClass(DependentAccess.class);
        verify(dependentAccessRepository).save(saved.capture());
        DependentAccess grant = saved.getValue();
        assertTrue(grant.getCanViewRecords());
        assertFalse(grant.getCanMessageCareTeam());
        assertFalse(grant.getCanJoinCalls());
        assertFalse(PatientPermission.MESSAGING.isGrantedBy(grant));

        clearInvocations(dependentAccessRepository, eventPublisher);
        CreateDependentRequest requesting = CreateDependentRequest.builder()
                .firstName("Thomas").lastName("Dube").email("t2@example.com").password("Secret123")
                .relationship("father").canMessageCareTeam(true).canJoinCalls(true).build();
        caregiverService.createDependent(CAREGIVER, requesting);
        verify(dependentAccessRepository).save(saved.capture());
        grant = saved.getValue();
        assertTrue(grant.getCanMessageCareTeam());
        assertTrue(grant.getCanJoinCalls());
        assertNull(grant.getMessagingApprovedAt());
        assertNull(grant.getCallsApprovedAt());
        CaregiverAccessChangedEvent event = lastEvent();
        assertFalse(event.messagingAllowed());
        assertFalse(event.joinCallsAllowed());
    }

    // ==================== PATIENT: APPROVES ====================

    @Test
    public void patientPatch_approvesRequestedFlag_andStampsPatient() {
        DependentAccess access = activeGrant();
        access.setCanJoinCalls(true); // requested, unapproved
        stored(access);

        CaregiverDependentResponse response = caregiverService.updateCaregiverAccessAsPatient(PATIENT, false, ACCESS_ID,
                UpdateDependentAccessRequest.builder().canJoinCalls(true).canMessageCareTeam(true).build());

        assertNotNull(access.getCallsApprovedAt());
        assertEquals(PATIENT, access.getCallsApprovedBySystemId());
        assertEquals(PATIENT, access.getMessagingApprovedBySystemId());
        assertTrue(response.isMessagingAllowed());
        assertTrue(response.isJoinCallsAllowed());
        assertFalse(response.isPendingApproval());
        assertEquals("ACTIVE", response.getAccessStatus());
        assertEquals(new CaregiverAccessChangedEvent(ACCESS_ID, PATIENT, CAREGIVER, false, true, true), lastEvent());
        verify(auditService).logClinicalAccess("UPDATE", "DEPENDENT_ACCESS", ACCESS_ID, PATIENT);
    }

    @Test
    public void patientPatch_keepsExistingApprovalTime() {
        DependentAccess access = activeGrant();
        Instant approved = Instant.parse("2026-10-01T08:00:00Z");
        access.setCanMessageCareTeam(true);
        access.setMessagingApprovedAt(approved);
        access.setMessagingApprovedBySystemId(PATIENT);
        stored(access);

        caregiverService.updateCaregiverAccessAsPatient(PATIENT, false, ACCESS_ID,
                UpdateDependentAccessRequest.builder().canMessageCareTeam(true).build());

        assertEquals(approved, access.getMessagingApprovedAt());
    }

    @Test
    public void adminPatch_neverApproves_butCanTurnOff() {
        DependentAccess access = activeGrant();
        access.setCanJoinCalls(true);
        access.setCallsApprovedAt(Instant.now());
        access.setCallsApprovedBySystemId(PATIENT);
        stored(access);

        CaregiverDependentResponse response = caregiverService.updateCaregiverAccessAsPatient(ADMIN, true, ACCESS_ID,
                UpdateDependentAccessRequest.builder().canMessageCareTeam(true).canJoinCalls(false).build());

        assertTrue(access.getCanMessageCareTeam());
        assertNull(access.getMessagingApprovedAt());
        assertFalse(access.getCanJoinCalls());
        assertNull(access.getCallsApprovedAt());
        assertFalse(response.isMessagingAllowed());
        assertTrue(response.isPendingApproval());
    }

    @Test
    public void patientPatch_otherPatientsGrant_forbidden_andRevokedGrant_conflict() {
        stored(activeGrant());
        assertThrows(AuthorizationException.class, () -> caregiverService.updateCaregiverAccessAsPatient(
                "PAT-00002", false, ACCESS_ID, UpdateDependentAccessRequest.builder().canJoinCalls(true).build()));

        DependentAccess revoked = activeGrant();
        revoked.setIsActive(false);
        revoked.setRevokedAt(LocalDateTime.now());
        stored(revoked);
        ConflictException ex = assertThrows(ConflictException.class, () -> caregiverService.updateCaregiverAccessAsPatient(
                PATIENT, false, ACCESS_ID, UpdateDependentAccessRequest.builder().canJoinCalls(true).build()));
        assertEquals("This access has been revoked", ex.getMessage());
    }

    @Test
    public void patientPatch_unknownAccess_404() {
        when(dependentAccessRepository.findById(99)).thenReturn(Optional.empty());
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class, () -> caregiverService
                .updateCaregiverAccessAsPatient(PATIENT, false, 99, new UpdateDependentAccessRequest()));
        assertEquals("Access record not found", ex.getMessage());
    }

    @Test
    public void expiredGrant_reportsExpired_andNothingAllowed() {
        DependentAccess access = activeGrant();
        access.setExpiresAt(LocalDateTime.now().minusDays(1));
        access.setCanMessageCareTeam(true);
        access.setMessagingApprovedAt(Instant.now());
        access.setMessagingApprovedBySystemId(PATIENT);
        when(dependentAccessRepository.findUnrevokedForPatient(PATIENT)).thenReturn(List.of(access));

        CaregiverDependentResponse response = caregiverService.getCaregiverAccessForPatient(PATIENT).get(0);
        assertEquals("EXPIRED", response.getAccessStatus());
        assertFalse(response.isMessagingAllowed());
        assertFalse(response.isPendingApproval());
    }

    // ==================== REVOKE ====================

    @Test
    public void revoke_clearsApprovals_audits_andPublishesRevokedEvent_evenWhenRepeated() {
        DependentAccess access = activeGrant();
        access.setCanMessageCareTeam(true);
        access.setMessagingApprovedAt(Instant.now());
        access.setMessagingApprovedBySystemId(PATIENT);
        stored(access);

        caregiverService.revokeCaregiverAccessAsPatient(PATIENT, false, ACCESS_ID);

        assertFalse(access.getIsActive());
        assertNotNull(access.getRevokedAt());
        assertNull(access.getMessagingApprovedAt());
        verify(auditService).logClinicalAccess("DELETE", "DEPENDENT_ACCESS", ACCESS_ID, PATIENT);
        assertEquals(new CaregiverAccessChangedEvent(ACCESS_ID, PATIENT, CAREGIVER, true, false, false), lastEvent());

        // idempotent: no second save/audit, but the event is published again for cleanup
        clearInvocations(dependentAccessRepository, auditService, eventPublisher);
        caregiverService.revokeCaregiverAccessAsPatient(PATIENT, false, ACCESS_ID);
        verify(dependentAccessRepository, never()).save(any());
        verifyNoInteractions(auditService);
        assertTrue(lastEvent().revoked());
    }

    @Test
    public void adminRevoke_anyGrant_patientRevoke_onlyOwn() {
        stored(activeGrant());
        assertThrows(AuthorizationException.class,
                () -> caregiverService.revokeCaregiverAccessAsPatient("PAT-00002", false, ACCESS_ID));
        assertDoesNotThrow(() -> caregiverService.revokeCaregiverAccessAsPatient(ADMIN, true, ACCESS_ID));
    }

    // ==================== PATIENT GRANT ====================

    private User caregiverUser() {
        User user = new User();
        user.setSystemId(CAREGIVER);
        user.setRole("CAREGIVER");
        user.setIsActive(true);
        user.setEmail("sarah.dube@carenexus.com");
        return user;
    }

    private void caregiverExists() {
        when(patientRepository.findBySystemId(PATIENT)).thenReturn(Optional.of(new Patient()));
        when(userRepository.findFirstByEmailIgnoreCaseOrderByUserIdAsc("sarah.dube@carenexus.com"))
                .thenReturn(Optional.of(caregiverUser()));
        when(caregiverRepository.findBySystemId(CAREGIVER)).thenReturn(Optional.of(new Caregiver()));
    }

    @Test
    public void grant_fullAccess_setsDataFlags_andApprovesOnlyRequestedCommunication() {
        caregiverExists();
        when(dependentAccessRepository.findByCaregiverSystemIdAndPatientSystemId(CAREGIVER, PATIENT)).thenReturn(Optional.empty());

        CaregiverDependentResponse response = caregiverService.grantCaregiverAccessAsPatient(PATIENT,
                GrantCaregiverAccessRequest.builder().caregiverEmail("  Sarah.Dube@carenexus.com ").relationship("daughter")
                        .accessLevel("FULL_ACCESS").canMessageCareTeam(true).build());

        ArgumentCaptor<DependentAccess> saved = ArgumentCaptor.forClass(DependentAccess.class);
        verify(dependentAccessRepository).save(saved.capture());
        DependentAccess grant = saved.getValue();
        assertTrue(grant.getCanManageCarePlan());
        assertTrue(grant.getCanMessageCareTeam());
        assertEquals(PATIENT, grant.getMessagingApprovedBySystemId());
        assertFalse(grant.getCanJoinCalls());
        assertNull(grant.getCallsApprovedAt());
        assertEquals(PATIENT, grant.getAuthorizedBySystemId());
        assertTrue(response.isMessagingAllowed());
        assertFalse(response.isJoinCallsAllowed());
        assertEquals(new CaregiverAccessChangedEvent(null, PATIENT, CAREGIVER, false, true, false), lastEvent());
    }

    @Test
    public void grant_unknownOrNonCaregiverEmail_404() {
        when(patientRepository.findBySystemId(PATIENT)).thenReturn(Optional.of(new Patient()));
        User doctor = caregiverUser();
        doctor.setRole("DOCTOR");
        when(userRepository.findFirstByEmailIgnoreCaseOrderByUserIdAsc("doc@carenexus.com")).thenReturn(Optional.of(doctor));

        for (String email : List.of("nobody@carenexus.com", "doc@carenexus.com")) {
            ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class, () ->
                    caregiverService.grantCaregiverAccessAsPatient(PATIENT,
                            GrantCaregiverAccessRequest.builder().caregiverEmail(email).relationship("son").build()));
            assertEquals(CaregiverService.NO_CAREGIVER_MESSAGE, ex.getMessage());
        }
        verifyNoInteractions(eventPublisher);
    }

    @Test
    public void grant_existingUnrevoked_409() {
        caregiverExists();
        when(dependentAccessRepository.findByCaregiverSystemIdAndPatientSystemId(CAREGIVER, PATIENT))
                .thenReturn(Optional.of(activeGrant()));

        ConflictException ex = assertThrows(ConflictException.class, () -> caregiverService.grantCaregiverAccessAsPatient(
                PATIENT, GrantCaregiverAccessRequest.builder().caregiverEmail("sarah.dube@carenexus.com")
                        .relationship("daughter").build()));
        assertEquals(CaregiverService.ALREADY_GRANTED_MESSAGE, ex.getMessage());
    }

    @Test
    public void grant_reusesRevokedRow_withFreshValues_andNoOldApproval() {
        caregiverExists();
        DependentAccess revoked = activeGrant();
        revoked.setIsActive(false);
        revoked.setRevokedAt(LocalDateTime.now().minusDays(1));
        revoked.setCanMessageCareTeam(true);
        revoked.setCanJoinCalls(true);
        when(dependentAccessRepository.findByCaregiverSystemIdAndPatientSystemId(CAREGIVER, PATIENT))
                .thenReturn(Optional.of(revoked));

        caregiverService.grantCaregiverAccessAsPatient(PATIENT, GrantCaregiverAccessRequest.builder()
                .caregiverEmail("sarah.dube@carenexus.com").relationship("daughter").canJoinCalls(true).build());

        assertTrue(revoked.getIsActive());
        assertNull(revoked.getRevokedAt());
        assertEquals("VIEW_ONLY", revoked.getAccessLevel());
        assertFalse(revoked.getCanViewRecords());
        assertFalse(revoked.getCanMessageCareTeam());
        assertTrue(revoked.getCanJoinCalls());
        assertEquals(PATIENT, revoked.getCallsApprovedBySystemId());
        assertEquals(new CaregiverAccessChangedEvent(ACCESS_ID, PATIENT, CAREGIVER, false, false, true), lastEvent());
    }

    @Test
    public void grant_validation() {
        assertThrows(RuntimeException.class, () -> caregiverService.grantCaregiverAccessAsPatient(PATIENT,
                GrantCaregiverAccessRequest.builder().relationship("son").build()));
        assertThrows(RuntimeException.class, () -> caregiverService.grantCaregiverAccessAsPatient(PATIENT,
                GrantCaregiverAccessRequest.builder().caregiverEmail("a@b.co").build()));
        RuntimeException past = assertThrows(RuntimeException.class, () -> caregiverService.grantCaregiverAccessAsPatient(
                PATIENT, GrantCaregiverAccessRequest.builder().caregiverEmail("a@b.co").relationship("son")
                        .expiresAt("2020-01-01").build()));
        assertEquals("Access expiry must be in the future", past.getMessage());
        verify(dependentAccessRepository, never()).save(any());
    }

    // ==================== ACCESS RETURNS / APPROVAL REQUESTS ====================

    private static DependentAccess messagingGrant() {
        DependentAccess access = activeGrant();
        access.setCanMessageCareTeam(true);
        access.setMessagingApprovedAt(Instant.now().minusSeconds(3600));
        access.setMessagingApprovedBySystemId(PATIENT);
        return access;
    }

    @Test
    public void patientExtendsExpiredGrant_publishesMessagingRegained_withExpiredGap() {
        DependentAccess access = messagingGrant();
        access.setExpiresAt(LocalDateTime.now().minusDays(1)); // lapsed
        stored(access);

        caregiverService.updateCaregiverAccessAsPatient(PATIENT, false, ACCESS_ID,
                UpdateDependentAccessRequest.builder().expiresAt(LocalDateTime.now().plusDays(30).toString()).build());

        // The messaging listener ends surviving memberships in this transaction: history restarts after the gap.
        assertEquals(List.of(new CaregiverMessagingRegainedEvent(ACCESS_ID, PATIENT, CAREGIVER, true)),
                events(CaregiverMessagingRegainedEvent.class));
        assertTrue(lastEvent().messagingAllowed());
    }

    @Test
    public void patientReapprovesMessaging_publishesMessagingRegained_withoutExpiry() {
        DependentAccess access = activeGrant();
        access.setCanMessageCareTeam(true); // requested, not approved
        stored(access);

        caregiverService.updateCaregiverAccessAsPatient(PATIENT, false, ACCESS_ID,
                UpdateDependentAccessRequest.builder().canMessageCareTeam(true).build());

        assertEquals(List.of(new CaregiverMessagingRegainedEvent(ACCESS_ID, PATIENT, CAREGIVER, false)),
                events(CaregiverMessagingRegainedEvent.class));
    }

    @Test
    public void extendingAnActiveGrant_doesNotPublishMessagingRegained() {
        DependentAccess access = messagingGrant();
        access.setExpiresAt(LocalDateTime.now().plusDays(2));
        stored(access);

        caregiverService.updateCaregiverAccessAsPatient(PATIENT, false, ACCESS_ID,
                UpdateDependentAccessRequest.builder().expiresAt(LocalDateTime.now().plusDays(30).toString()).build());

        assertTrue(events(CaregiverMessagingRegainedEvent.class).isEmpty());
    }

    @Test
    public void adminRequestsMessaging_patientIsAskedToApprove_thenApprovalClearsIt() {
        DependentAccess access = activeGrant();
        stored(access);

        // Admin changes never approve: the grant now waits for the patient
        caregiverService.updateCaregiverAccessAsPatient("ADM-00001", true, ACCESS_ID,
                UpdateDependentAccessRequest.builder().canJoinCalls(true).build());
        assertEquals(List.of(new CaregiverApprovalPendingEvent(ACCESS_ID, PATIENT, CAREGIVER, true, false, true)),
                events(CaregiverApprovalPendingEvent.class));

        // A second unrelated change while still pending publishes nothing new (transitions only)
        caregiverService.updateCaregiverAccessAsPatient("ADM-00001", true, ACCESS_ID,
                UpdateDependentAccessRequest.builder().canViewImaging(true).build());
        assertEquals(1, events(CaregiverApprovalPendingEvent.class).size());

        caregiverService.updateCaregiverAccessAsPatient(PATIENT, false, ACCESS_ID,
                UpdateDependentAccessRequest.builder().canJoinCalls(true).build());
        List<CaregiverApprovalPendingEvent> approvals = events(CaregiverApprovalPendingEvent.class);
        assertEquals(2, approvals.size());
        assertFalse(approvals.get(1).pending());
    }
}
