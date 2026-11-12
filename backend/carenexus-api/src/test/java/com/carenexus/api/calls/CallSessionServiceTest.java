package com.carenexus.api.calls;

import com.carenexus.api.calls.config.CallsProperties;
import com.carenexus.api.calls.event.AppointmentCallRelevantChangeEvent.Kind;
import com.carenexus.api.calls.model.CallJob;
import com.carenexus.api.calls.model.CallParticipant;
import com.carenexus.api.calls.model.CallSession;
import com.carenexus.api.calls.repository.CallParticipantRepository;
import com.carenexus.api.calls.repository.CallSessionRepository;
import com.carenexus.api.calls.service.CallJobService;
import com.carenexus.api.calls.service.CallSessionService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.core.model.Appointment;
import com.carenexus.api.core.repository.AppointmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Call session lifecycle: join window, appointment sync (cancel, reschedule, type change), removal semantics.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class CallSessionServiceTest {

    @Mock private CallSessionRepository callSessionRepository;
    @Mock private CallParticipantRepository callParticipantRepository;
    @Mock private AppointmentRepository appointmentRepository;
    @Mock private CallJobService callJobService;
    @Mock private AuthorizationService authorizationService;

    private CallSessionService service;

    @BeforeEach
    void setUp() {
        service = new CallSessionService(callSessionRepository, callParticipantRepository, appointmentRepository,
                callJobService, authorizationService, new CallsProperties());
        ReflectionTestUtils.setField(service, "schedulingTimeZone", "Africa/Harare");
        when(callSessionRepository.saveAndFlush(any(CallSession.class))).thenAnswer(inv -> {
            CallSession s = inv.getArgument(0);
            if (s.getCallSessionId() == null) {
                s.setCallSessionId(10L);
            }
            return s;
        });
        when(callSessionRepository.save(any(CallSession.class))).thenAnswer(inv -> inv.getArgument(0));
        when(callParticipantRepository.save(any(CallParticipant.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ==================== JOIN WINDOW ====================

    @Test
    public void joinWindow_opens15MinutesBefore_closes60MinutesAfterEnd_inClinicZone() {
        Appointment appointment = appointment("VIDEO", "SCHEDULED", LocalDateTime.of(2026, 11, 20, 10, 30), 30);
        CallSession session = new CallSession();

        service.applySchedule(session, appointment);

        // 10:30 Africa/Harare (UTC+2) = 08:30Z
        assertEquals(Instant.parse("2026-11-20T08:30:00Z"), session.getScheduledStartUtc());
        assertEquals(Instant.parse("2026-11-20T09:00:00Z"), session.getScheduledEndUtc());
        assertEquals(Instant.parse("2026-11-20T08:15:00Z"), session.getJoinOpensAt());
        assertEquals(Instant.parse("2026-11-20T10:00:00Z"), session.getJoinClosesAt());

        assertFalse(CallSessionService.isWindowOpen(session, Instant.parse("2026-11-20T08:14:59Z")));
        assertTrue(CallSessionService.isWindowOpen(session, Instant.parse("2026-11-20T08:15:00Z")));
        assertTrue(CallSessionService.isWindowOpen(session, Instant.parse("2026-11-20T10:00:00Z")));
        assertFalse(CallSessionService.isWindowOpen(session, Instant.parse("2026-11-20T10:00:01Z")));
    }

    @Test
    public void windowClosed_whenNoSchedule() {
        assertFalse(CallSessionService.isWindowOpen(new CallSession(), Instant.now()));
    }

    @Test
    public void opaqueValues_haveNoIds() {
        String room = CallSessionService.newRoomName();
        String identity = CallSessionService.newProviderIdentity();
        assertTrue(room.matches("cn_[0-9a-f]{32}"), room);
        assertTrue(identity.matches("p_[0-9a-f]{32}"), identity);
        assertNotEquals(room, CallSessionService.newRoomName());
    }

    // ==================== APPOINTMENT SYNC ====================

    @Test
    public void createdVideo_createsPreparingSession_andEnqueuesCreateRoom() {
        Appointment appointment = appointment("VIDEO", "SCHEDULED", LocalDateTime.now().plusDays(2), 30);
        when(appointmentRepository.findById(1)).thenReturn(Optional.of(appointment));
        when(callSessionRepository.findForUpdateByAppointmentId(1)).thenReturn(Optional.empty());

        service.syncWithAppointment(1, Kind.CREATED_VIDEO);

        ArgumentCaptor<CallSession> saved = ArgumentCaptor.forClass(CallSession.class);
        verify(callSessionRepository).saveAndFlush(saved.capture());
        assertEquals(CallSession.PREPARING, saved.getValue().getStatus());
        assertEquals(1, saved.getValue().getAppointmentId());
        assertNotNull(saved.getValue().getJoinOpensAt());
        ArgumentCaptor<Instant> due = ArgumentCaptor.forClass(Instant.class);
        verify(callJobService).enqueueCreateRoom(eq(saved.getValue()), due.capture());
        // Room is created 5 minutes before the window opens.
        assertEquals(saved.getValue().getJoinOpensAt().minusSeconds(300), due.getValue());
    }

    @Test
    public void cancel_cancelsSession_removesParticipants_cancelsCreate_andEnqueuesDeleteRoom() {
        Appointment appointment = appointment("VIDEO", "CANCELLED", LocalDateTime.now().plusHours(1), 30);
        CallSession session = liveSession();
        CallParticipant admitted = participant(5L, CallParticipant.ADMITTED);
        CallParticipant requested = participant(6L, CallParticipant.REQUESTED);
        when(appointmentRepository.findById(1)).thenReturn(Optional.of(appointment));
        when(callSessionRepository.findForUpdateByAppointmentId(1)).thenReturn(Optional.of(session));
        when(callParticipantRepository.findByCallSessionIdAndStatusIn(eq(10L), any()))
                .thenReturn(List.of(admitted, requested));

        service.syncWithAppointment(1, Kind.CANCELLED);

        assertEquals(CallSession.CANCELLED, session.getStatus());
        assertEquals(CallSession.END_APPOINTMENT_CANCELLED, session.getEndReason());
        assertEquals(CallParticipant.REMOVED, admitted.getStatus());
        assertEquals(CallParticipant.REASON_APPOINTMENT_CANCELLED, admitted.getRemovalReason());
        assertNotNull(admitted.getRevokedAt());
        assertTrue(admitted.getRemovalPending());
        assertEquals(CallParticipant.REMOVED, requested.getStatus());
        assertFalse(requested.getRemovalPending());
        verify(callJobService).enqueueRemoveParticipant(session, admitted);
        verify(callJobService, never()).enqueueRemoveParticipant(session, requested);
        verify(callJobService).cancelPendingCreateRoom(10L);
        verify(callJobService).enqueueDeleteRoom(session, "cn_old");
        // Appointment status is never touched by the call module.
        verify(appointmentRepository, never()).save(any());
    }

    @Test
    public void cancel_preparingSessionWithoutRoom_doesNotDeleteRoom() {
        Appointment appointment = appointment("VIDEO", "CANCELLED", LocalDateTime.now().plusDays(1), 30);
        CallSession session = liveSession();
        session.setStatus(CallSession.PREPARING);
        session.setStartedAt(null);
        session.setRoomCreatedAt(null);
        when(appointmentRepository.findById(1)).thenReturn(Optional.of(appointment));
        when(callSessionRepository.findForUpdateByAppointmentId(1)).thenReturn(Optional.of(session));

        service.syncWithAppointment(1, Kind.CANCELLED);

        assertEquals(CallSession.CANCELLED, session.getStatus());
        verify(callJobService).cancelPendingCreateRoom(10L);
        verify(callJobService, never()).enqueueDeleteRoom(any(), any());
    }

    @Test
    public void reschedule_removesParticipants_newRoom_preparing() {
        Appointment appointment = appointment("VIDEO", "RESCHEDULED", LocalDateTime.now().plusDays(3), 30);
        CallSession session = liveSession();
        CallParticipant admitted = participant(5L, CallParticipant.ADMITTED);
        when(appointmentRepository.findById(1)).thenReturn(Optional.of(appointment));
        when(callSessionRepository.findForUpdateByAppointmentId(1)).thenReturn(Optional.of(session));
        when(callParticipantRepository.findByCallSessionIdAndStatusIn(eq(10L), any())).thenReturn(List.of(admitted));

        service.syncWithAppointment(1, Kind.RESCHEDULED);

        assertEquals(CallSession.PREPARING, session.getStatus());
        assertNotEquals("cn_old", session.getRoomName());
        assertNull(session.getStartedAt());
        assertEquals(CallParticipant.REASON_RESCHEDULED, admitted.getRemovalReason());
        verify(callJobService).enqueueDeleteRoom(session, "cn_old");
        verify(callJobService).cancelPendingCreateRoom(10L);
        verify(callJobService).enqueueCreateRoom(eq(session), any());
    }

    @Test
    public void reschedule_clearsNeedsTimeConfirmation() {
        Appointment appointment = appointment("VIDEO", "RESCHEDULED", LocalDateTime.now().plusDays(3), 30);
        CallSession session = CallSession.builder().callSessionId(10L).appointmentId(1)
                .status(CallSession.NEEDS_TIME_CONFIRMATION).roomName("cn_legacy").build();
        when(appointmentRepository.findById(1)).thenReturn(Optional.of(appointment));
        when(callSessionRepository.findForUpdateByAppointmentId(1)).thenReturn(Optional.of(session));

        service.syncWithAppointment(1, Kind.RESCHEDULED);

        assertEquals(CallSession.PREPARING, session.getStatus());
        assertNotNull(session.getJoinOpensAt());
        verify(callJobService, never()).enqueueDeleteRoom(any(), any());
    }

    @Test
    public void typeChangedFromVideo_cancelsSessionWithTypeChanged() {
        Appointment appointment = appointment("IN_PERSON", "SCHEDULED", LocalDateTime.now().plusDays(1), 30);
        CallSession session = liveSession();
        session.setStatus(CallSession.READY);
        session.setStartedAt(null);
        when(appointmentRepository.findById(1)).thenReturn(Optional.of(appointment));
        when(callSessionRepository.findForUpdateByAppointmentId(1)).thenReturn(Optional.of(session));

        service.syncWithAppointment(1, Kind.TYPE_CHANGED);

        assertEquals(CallSession.CANCELLED, session.getStatus());
        assertEquals(CallSession.END_TYPE_CHANGED, session.getEndReason());
        verify(callJobService).enqueueDeleteRoom(session, "cn_old");
    }

    @Test
    public void typeChangedToVideo_resetsCancelledSession() {
        Appointment appointment = appointment("VIDEO", "SCHEDULED", LocalDateTime.now().plusDays(1), 30);
        CallSession session = liveSession();
        session.setStatus(CallSession.CANCELLED);
        when(appointmentRepository.findById(1)).thenReturn(Optional.of(appointment));
        when(callSessionRepository.findForUpdateByAppointmentId(1)).thenReturn(Optional.of(session));

        service.syncWithAppointment(1, Kind.TYPE_CHANGED);

        assertEquals(CallSession.PREPARING, session.getStatus());
        assertNotEquals("cn_old", session.getRoomName());
        assertNull(session.getEndReason());
        verify(callJobService).enqueueCreateRoom(eq(session), any());
    }

    @Test
    public void legacyVideoAppointment_withoutSession_isCreatedAsNeedsTimeConfirmation() {
        Appointment appointment = appointment("VIDEO", "SCHEDULED", LocalDateTime.now().plusDays(1), 30);
        when(callSessionRepository.findByAppointmentId(1)).thenReturn(Optional.empty());

        CallSession session = service.getOrCreateForRead(appointment);

        assertEquals(CallSession.NEEDS_TIME_CONFIRMATION, session.getStatus());
        assertNull(session.getJoinOpensAt());
        verify(callJobService, never()).enqueueCreateRoom(any(), any());
    }

    @Test
    public void expiry_endsSessionAsExpired() {
        CallSession session = liveSession();
        session.setJoinClosesAt(Instant.now().minusSeconds(5));
        when(callSessionRepository.findForUpdateById(10L)).thenReturn(Optional.of(session));

        assertTrue(service.expireIfClosed(10L, Instant.now()));

        assertEquals(CallSession.ENDED, session.getStatus());
        assertEquals(CallSession.END_EXPIRED, session.getEndReason());
        verify(callJobService).enqueueDeleteRoom(session, "cn_old");
    }

    @Test
    public void accessRevoked_removesOpenRowsOfCaregiverInPatientCalls() {
        CallSession session = liveSession();
        CallParticipant admitted = participant(5L, CallParticipant.ADMITTED);
        when(callParticipantRepository.findIdsForUserInPatientCalls(eq("CGV-00001"), eq("PAT-00001"), any()))
                .thenReturn(List.<Object[]>of(new Object[]{5L, 10L}));
        when(callSessionRepository.findForUpdateById(10L)).thenReturn(Optional.of(session));
        when(callParticipantRepository.findById(5L)).thenReturn(Optional.of(admitted));

        int removed = service.removeUserFromOpenCalls("CGV-00001", "PAT-00001", CallParticipant.REASON_ACCESS_REVOKED);

        assertEquals(1, removed);
        assertEquals(CallParticipant.REMOVED, admitted.getStatus());
        assertEquals(CallParticipant.REASON_ACCESS_REVOKED, admitted.getRemovalReason());
        assertNotNull(admitted.getRevokedAt());
        assertTrue(admitted.getRemovalPending());
        verify(callJobService).enqueueRemoveParticipant(session, admitted);
    }

    @Test
    public void jobBackoff_isCappedExponential() {
        assertEquals(5, CallJob.backoffSeconds(1));
        assertEquals(10, CallJob.backoffSeconds(2));
        assertEquals(20, CallJob.backoffSeconds(3));
        assertEquals(300, CallJob.backoffSeconds(10));
        assertEquals(300, CallJob.backoffSeconds(30));
        assertEquals(8, CallJob.maxAttemptsFor(CallJob.CREATE_ROOM));
        assertEquals(10, CallJob.maxAttemptsFor(CallJob.DELETE_ROOM));
        assertEquals(30, CallJob.maxAttemptsFor(CallJob.REMOVE_PARTICIPANT));
    }

    // ==================== HELPERS ====================

    static Appointment appointment(String type, String status, LocalDateTime at, int duration) {
        return Appointment.builder()
                .appointmentId(1)
                .patientSystemId("PAT-00001")
                .doctorSystemId("DOC-00001")
                .scheduledAt(at)
                .durationMinutes(duration)
                .type(type)
                .status(status)
                .build();
    }

    static CallSession liveSession() {
        Instant now = Instant.now();
        return CallSession.builder()
                .callSessionId(10L)
                .appointmentId(1)
                .status(CallSession.LIVE)
                .roomName("cn_old")
                .scheduledStartUtc(now.minusSeconds(600))
                .scheduledEndUtc(now.plusSeconds(1200))
                .joinOpensAt(now.minusSeconds(1500))
                .joinClosesAt(now.plusSeconds(4800))
                .roomCreatedAt(now.minusSeconds(1800))
                .startedAt(now.minusSeconds(600))
                .build();
    }

    static CallParticipant participant(Long id, String status) {
        return CallParticipant.builder()
                .callParticipantId(id)
                .callSessionId(10L)
                .systemId("CGV-00001")
                .participantRole("CAREGIVER")
                .status(status)
                .providerIdentity("p_" + id)
                .displayName("Sarah Dube (Caregiver for Thomas Dube)")
                .requestedAt(Instant.now().minusSeconds(60))
                .admittedAt(CallParticipant.ADMITTED.equals(status) ? Instant.now().minusSeconds(30) : null)
                .removalPending(false)
                .actingForPatient(false)
                .build();
    }
}
