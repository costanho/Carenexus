package com.carenexus.api.calls;

import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.calls.config.CallsProperties;
import com.carenexus.api.calls.config.LiveKitProperties;
import com.carenexus.api.calls.dto.CallTokenResponse;
import com.carenexus.api.calls.model.CallParticipant;
import com.carenexus.api.calls.model.CallSession;
import com.carenexus.api.calls.provider.CallProvider;
import com.carenexus.api.calls.repository.CallJobRepository;
import com.carenexus.api.calls.repository.CallParticipantRepository;
import com.carenexus.api.calls.repository.CallSessionRepository;
import com.carenexus.api.calls.service.CallCommittedAuthorizationException;
import com.carenexus.api.calls.service.CallCommittedConflictException;
import com.carenexus.api.calls.service.CallJobService;
import com.carenexus.api.calls.service.CallService;
import com.carenexus.api.calls.service.CallSessionService;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.core.model.Appointment;
import com.carenexus.api.core.repository.AppointmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Token issuance and admission: only ADMITTED, unrevoked participants of a LIVE call with current consent get a
 * 5-minute token; admission re-checks consent.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class CallServiceTokenTest {

    private static final String PATIENT = "PAT-00001";
    private static final String DOCTOR = "DOC-00001";
    private static final String CAREGIVER = "CGV-00001";

    @Mock private AppointmentRepository appointmentRepository;
    @Mock private CallSessionRepository callSessionRepository;
    @Mock private CallParticipantRepository callParticipantRepository;
    @Mock private CallJobRepository callJobRepository;
    @Mock private UserRepository userRepository;
    @Mock private CallSessionService callSessionService;
    @Mock private CallJobService callJobService;
    @Mock private CallProvider callProvider;
    @Mock private AuthorizationService authorizationService;
    @Mock private AuditService auditService;

    private CallService service;
    private CallSession session;
    private LiveKitProperties liveKitProperties;

    @BeforeEach
    void setUp() {
        liveKitProperties = new LiveKitProperties();
        service = new CallService(appointmentRepository, callSessionRepository, callParticipantRepository,
                callJobRepository, userRepository, callSessionService, callJobService, callProvider,
                authorizationService, auditService, new CallsProperties(), liveKitProperties, new ObjectMapper());

        Appointment appointment = Appointment.builder().appointmentId(1).patientSystemId(PATIENT)
                .doctorSystemId(DOCTOR).type("VIDEO").status("SCHEDULED")
                .scheduledAt(LocalDateTime.now()).durationMinutes(30).build();
        Instant now = Instant.now();
        session = CallSession.builder().callSessionId(10L).appointmentId(1).status(CallSession.LIVE)
                .roomName("cn_room").joinOpensAt(now.minusSeconds(600)).joinClosesAt(now.plusSeconds(3600))
                .scheduledStartUtc(now).scheduledEndUtc(now.plusSeconds(1800)).build();

        when(appointmentRepository.findById(1)).thenReturn(Optional.of(appointment));
        when(callSessionRepository.findForUpdateByAppointmentId(1)).thenReturn(Optional.of(session));
        when(callSessionRepository.findByAppointmentId(1)).thenReturn(Optional.of(session));
        when(callParticipantRepository.save(any(CallParticipant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(callParticipantRepository.findByCallSessionIdOrderByCallParticipantIdAsc(10L)).thenReturn(List.of());
        when(userRepository.findBySystemId(anyString())).thenReturn(Optional.empty());
        when(callProvider.isConfigured()).thenReturn(true);
        when(callProvider.clientUrl()).thenReturn("ws://localhost:7880");
        when(callProvider.issueToken(any())).thenAnswer(inv -> {
            CallProvider.TokenRequest r = inv.getArgument(0);
            return new CallProvider.IssuedToken("jwt", r.expiresAt());
        });
    }

    private void callerIs(String systemId, String role) {
        when(authorizationService.currentSystemId()).thenReturn(systemId);
        when(authorizationService.currentRole()).thenReturn(role);
    }

    private CallParticipant row(String systemId, String role, String status) {
        return CallParticipant.builder().callParticipantId(77L).callSessionId(10L).systemId(systemId)
                .participantRole(role).status(status).providerIdentity("p_abc").displayName("Label")
                .requestedAt(Instant.now()).actingForPatient(false).removalPending(false).build();
    }

    private void openRowIs(CallParticipant row) {
        when(callParticipantRepository.findFirstByCallSessionIdAndSystemIdAndStatusIn(eq(10L), eq(row.getSystemId()), any()))
                .thenReturn(Optional.of(row));
    }

    @Test
    public void token_refusedWhileOnlyRequested() {
        callerIs(PATIENT, "PATIENT");
        openRowIs(row(PATIENT, "PATIENT", CallParticipant.REQUESTED));

        ConflictException e = assertThrows(ConflictException.class, () -> service.issueToken(1));
        assertEquals("You have not been admitted to this call yet", e.getMessage());
        verify(callProvider, never()).issueToken(any());
    }

    @Test
    public void token_refusedWithoutAnyRow() {
        callerIs(PATIENT, "PATIENT");

        ConflictException e = assertThrows(ConflictException.class, () -> service.issueToken(1));
        assertEquals("You have not been admitted to this call yet", e.getMessage());
        verify(callProvider, never()).issueToken(any());
    }

    @Test
    public void token_refusedAfterRemoval() {
        callerIs(CAREGIVER, "CAREGIVER");
        CallParticipant removed = row(CAREGIVER, "CAREGIVER", CallParticipant.REMOVED);
        removed.setRevokedAt(Instant.now());
        when(callParticipantRepository.findFirstByCallSessionIdAndSystemIdOrderByCallParticipantIdDesc(10L, CAREGIVER))
                .thenReturn(Optional.of(removed));

        ConflictException e = assertThrows(ConflictException.class, () -> service.issueToken(1));
        assertEquals("Your access to this call has been removed", e.getMessage());
        verify(callProvider, never()).issueToken(any());
    }

    @Test
    public void token_refusedWhenRevokedCutoffSet() {
        callerIs(PATIENT, "PATIENT");
        CallParticipant admitted = row(PATIENT, "PATIENT", CallParticipant.ADMITTED);
        admitted.setRevokedAt(Instant.now().minusSeconds(1));
        openRowIs(admitted);

        assertThrows(ConflictException.class, () -> service.issueToken(1));
        verify(callProvider, never()).issueToken(any());
    }

    @Test
    public void token_caregiverWhosePermissionWasRevoked_isRemovedAndRefused() {
        callerIs(CAREGIVER, "CAREGIVER");
        CallParticipant admitted = row(CAREGIVER, "CAREGIVER", CallParticipant.ADMITTED);
        openRowIs(admitted);
        when(authorizationService.hasCaregiverPermission(CAREGIVER, PATIENT, PatientPermission.JOIN_CALLS)).thenReturn(false);

        assertThrows(CallCommittedAuthorizationException.class, () -> service.issueToken(1));
        verify(callSessionService).removeParticipant(session, admitted, CallParticipant.REASON_ACCESS_REVOKED);
        verify(callProvider, never()).issueToken(any());
    }

    @Test
    public void token_refusedWhenCallNotLive() {
        callerIs(PATIENT, "PATIENT");
        session.setStatus(CallSession.READY);
        openRowIs(row(PATIENT, "PATIENT", CallParticipant.ADMITTED));

        ConflictException e = assertThrows(ConflictException.class, () -> service.issueToken(1));
        assertEquals("This call is not live", e.getMessage());
    }

    @Test
    public void token_refusedForAdmin() {
        callerIs("ADM-00001", "ADMIN");
        assertThrows(AuthorizationException.class, () -> service.issueToken(1));
        verify(callProvider, never()).issueToken(any());
    }

    @Test
    public void token_providerNotConfigured_isTemporarilyUnavailable() {
        callerIs(PATIENT, "PATIENT");
        openRowIs(row(PATIENT, "PATIENT", CallParticipant.ADMITTED));
        when(callProvider.isConfigured()).thenReturn(false);

        ConflictException e = assertThrows(ConflictException.class, () -> service.issueToken(1));
        assertEquals("Video calls are temporarily unavailable. Please try again later.", e.getMessage());
    }

    @Test
    public void token_admittedCaregiverWithConsent_getsFiveMinuteToken() {
        callerIs(CAREGIVER, "CAREGIVER");
        CallParticipant admitted = row(CAREGIVER, "CAREGIVER", CallParticipant.ADMITTED);
        admitted.setActingForPatient(true);
        openRowIs(admitted);
        when(authorizationService.hasCaregiverPermission(CAREGIVER, PATIENT, PatientPermission.JOIN_CALLS)).thenReturn(true);

        Instant before = Instant.now();
        CallTokenResponse token = service.issueToken(1);

        assertEquals("jwt", token.getToken());
        assertEquals("p_abc", token.getIdentity());
        assertEquals("ws://localhost:7880", token.getServerUrl());
        long ttl = Duration.between(before, token.getExpiresAt()).getSeconds();
        assertTrue(ttl >= 299 && ttl <= 301, "ttl " + ttl);
        assertNotNull(admitted.getLastTokenIssuedAt());

        ArgumentCaptor<CallProvider.TokenRequest> request = ArgumentCaptor.forClass(CallProvider.TokenRequest.class);
        verify(callProvider).issueToken(request.capture());
        assertEquals("cn_room", request.getValue().roomName());
        assertEquals("{\"role\":\"CAREGIVER\",\"actingForPatient\":true}", request.getValue().metadataJson());
        verify(auditService).logClinicalAccess("CREATE", "CALL_TOKEN", 1, PATIENT);
    }

    @Test
    public void admit_caregiverWithoutConsent_isDenied() {
        callerIs(DOCTOR, "DOCTOR");
        CallParticipant requested = row(CAREGIVER, "CAREGIVER", CallParticipant.REQUESTED);
        when(callParticipantRepository.findByCallParticipantIdAndCallSessionId(77L, 10L)).thenReturn(Optional.of(requested));
        when(authorizationService.hasCaregiverPermission(CAREGIVER, PATIENT, PatientPermission.JOIN_CALLS)).thenReturn(false);

        CallCommittedConflictException e = assertThrows(CallCommittedConflictException.class, () -> service.admit(1, 77L));
        assertEquals("This person no longer has permission to join this call", e.getMessage());
        assertEquals(CallParticipant.DENIED, requested.getStatus());
    }

    @Test
    public void admit_onlyByAppointmentDoctor() {
        callerIs("DOC-00009", "DOCTOR");
        AuthorizationException e = assertThrows(AuthorizationException.class, () -> service.admit(1, 77L));
        assertEquals("Only the appointment's doctor can manage this call", e.getMessage());
    }

    @Test
    public void joinRequest_byDoctor_isBadRequest() {
        callerIs(DOCTOR, "DOCTOR");
        RuntimeException e = assertThrows(RuntimeException.class, () -> service.requestJoin(1, false));
        assertEquals("Doctors join by starting the call", e.getMessage());
    }

    @Test
    public void joinRequest_caregiverWithoutJoinCalls_isForbidden() {
        callerIs(CAREGIVER, "CAREGIVER");
        when(authorizationService.hasCaregiverPermission(CAREGIVER, PATIENT, PatientPermission.JOIN_CALLS)).thenReturn(false);
        AuthorizationException e = assertThrows(AuthorizationException.class, () -> service.requestJoin(1, true));
        assertEquals("You are not allowed to access this call", e.getMessage());
    }

    @Test
    public void start_preparing_enqueuesCreateRoomAndReports409() {
        callerIs(DOCTOR, "DOCTOR");
        session.setStatus(CallSession.FAILED);

        CallCommittedConflictException e = assertThrows(CallCommittedConflictException.class, () -> service.start(1));
        assertEquals("The call room is being prepared. Try again in a few seconds.", e.getMessage());
        assertEquals(CallSession.PREPARING, session.getStatus());
        verify(callJobService).enqueueCreateRoom(eq(session), any());
    }

    @Test
    public void start_needsTimeConfirmation_isRefused() {
        callerIs(DOCTOR, "DOCTOR");
        session.setStatus(CallSession.NEEDS_TIME_CONFIRMATION);
        ConflictException e = assertThrows(ConflictException.class, () -> service.start(1));
        assertEquals("Confirm the appointment time before starting the call", e.getMessage());
    }
}
