package com.carenexus.api.calls;

import com.carenexus.api.calls.config.LiveKitProperties;
import com.carenexus.api.calls.model.CallJob;
import com.carenexus.api.calls.model.CallParticipant;
import com.carenexus.api.calls.model.CallSession;
import com.carenexus.api.calls.provider.CallProvider;
import com.carenexus.api.calls.provider.CallProviderException;
import com.carenexus.api.calls.provider.DisabledCallProvider;
import com.carenexus.api.calls.repository.CallJobRepository;
import com.carenexus.api.calls.repository.CallParticipantRepository;
import com.carenexus.api.calls.repository.CallSessionRepository;
import com.carenexus.api.calls.service.CallJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Job worker: claim, success/failure bookkeeping, bounded retries, stale CREATE_ROOM cancellation.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class CallJobServiceTest {

    @Mock private CallJobRepository callJobRepository;
    @Mock private CallSessionRepository callSessionRepository;
    @Mock private CallParticipantRepository callParticipantRepository;
    @Mock private CallProvider callProvider;
    @Mock private PlatformTransactionManager transactionManager;

    private CallSession session;

    @BeforeEach
    void setUp() {
        session = CallSession.builder().callSessionId(10L).appointmentId(1).status(CallSession.PREPARING)
                .roomName("cn_room").joinClosesAt(Instant.now().plusSeconds(7200)).build();
        when(callSessionRepository.findById(10L)).thenReturn(Optional.of(session));
        when(callSessionRepository.findForUpdateById(10L)).thenReturn(Optional.of(session));
        when(callJobRepository.save(any(CallJob.class))).thenAnswer(inv -> inv.getArgument(0));
        when(callJobRepository.findOpen(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
    }

    private CallJobService service(CallProvider provider) {
        return new CallJobService(callJobRepository, callSessionRepository, callParticipantRepository, provider,
                new LiveKitProperties(), transactionManager);
    }

    private CallJob claim(String type, String identity, Long participantId) {
        CallJob job = CallJob.builder().callJobId(1L).jobType(type).status(CallJob.PENDING).callSessionId(10L)
                .callParticipantId(participantId).roomName("cn_room").providerIdentity(identity).attempts(0)
                .maxAttempts(CallJob.maxAttemptsFor(type)).nextAttemptAt(Instant.now()).build();
        when(callJobRepository.lockDueJobs(anyInt())).thenReturn(List.of(job));
        when(callJobRepository.findById(1L)).thenReturn(Optional.of(job));
        return job;
    }

    @Test
    public void createRoom_success_marksSessionReady() {
        CallJob job = claim(CallJob.CREATE_ROOM, null, null);

        assertEquals(1, service(callProvider).runOnce());

        verify(callProvider).createRoom(eq("cn_room"), intThat(t -> t >= 300 && t <= 86400), eq(6));
        assertEquals(CallJob.SUCCEEDED, job.getStatus());
        assertEquals(CallSession.READY, session.getStatus());
        assertNotNull(session.getRoomCreatedAt());
    }

    @Test
    public void createRoom_forSessionNoLongerPreparing_isCancelledWithoutProviderCall() {
        session.setStatus(CallSession.CANCELLED);
        CallJob job = claim(CallJob.CREATE_ROOM, null, null);

        service(callProvider).runOnce();

        assertEquals(CallJob.CANCELLED, job.getStatus());
        verifyNoInteractions(callProvider);
    }

    @Test
    public void failure_retriesWithBackoff() {
        CallJob job = claim(CallJob.REMOVE_PARTICIPANT, "p_abc", 7L);
        doThrow(new CallProviderException("LiveKit removeParticipant failed: HTTP 503", true))
                .when(callProvider).removeParticipant("cn_room", "p_abc");

        Instant before = Instant.now();
        service(callProvider).runOnce();

        assertEquals(CallJob.PENDING, job.getStatus());
        assertEquals(1, job.getAttempts());
        assertTrue(job.getNextAttemptAt().isAfter(before.plusSeconds(4)));
        assertEquals("LiveKit removeParticipant failed: HTTP 503", job.getLastError());
    }

    @Test
    public void removeParticipant_success_clearsRemovalPending() {
        claim(CallJob.REMOVE_PARTICIPANT, "p_abc", 7L);
        CallParticipant p = CallParticipant.builder().callParticipantId(7L).status(CallParticipant.REMOVED)
                .removalPending(true).build();
        when(callParticipantRepository.findById(7L)).thenReturn(Optional.of(p));

        service(callProvider).runOnce();

        assertFalse(p.getRemovalPending());
    }

    @Test
    public void removeParticipant_failing_keepsRemovalPending_untilAttemptsExhausted() {
        CallJob job = claim(CallJob.REMOVE_PARTICIPANT, "p_abc", 7L);
        job.setAttempts(29);
        CallParticipant p = CallParticipant.builder().callParticipantId(7L).status(CallParticipant.REMOVED)
                .removalPending(true).build();
        when(callParticipantRepository.findById(7L)).thenReturn(Optional.of(p));
        doThrow(new CallProviderException("LiveKit removeParticipant failed: HTTP 503", true))
                .when(callProvider).removeParticipant(any(), any());

        service(callProvider).runOnce();

        assertEquals(CallJob.FAILED, job.getStatus());
        assertTrue(p.getRemovalPending(), "removal is never reported done before the provider confirms");
    }

    @Test
    public void notConfigured_createRoomFailsAndSessionFails() {
        CallJob job = claim(CallJob.CREATE_ROOM, null, null);

        service(new DisabledCallProvider()).runOnce();

        assertEquals(CallJob.FAILED, job.getStatus());
        assertEquals("LiveKit is not configured", job.getLastError());
        assertEquals(CallSession.FAILED, session.getStatus());
    }
}
