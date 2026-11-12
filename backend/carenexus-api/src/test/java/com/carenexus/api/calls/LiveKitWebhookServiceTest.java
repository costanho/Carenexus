package com.carenexus.api.calls;

import com.carenexus.api.calls.config.LiveKitProperties;
import com.carenexus.api.calls.controller.LiveKitWebhookController;
import com.carenexus.api.calls.model.CallParticipant;
import com.carenexus.api.calls.model.CallSession;
import com.carenexus.api.calls.model.ProviderEvent;
import com.carenexus.api.calls.provider.CallProvider;
import com.carenexus.api.calls.provider.InvalidWebhookSignatureException;
import com.carenexus.api.calls.repository.CallParticipantRepository;
import com.carenexus.api.calls.repository.CallSessionRepository;
import com.carenexus.api.calls.repository.ProviderEventRepository;
import com.carenexus.api.calls.service.CallJobService;
import com.carenexus.api.calls.service.LiveKitWebhookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Webhook: signature rejection, idempotency by event id, out-of-order tolerance, removal of unexpected presence.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class LiveKitWebhookServiceTest {

    @Mock private ProviderEventRepository providerEventRepository;
    @Mock private CallSessionRepository callSessionRepository;
    @Mock private CallParticipantRepository callParticipantRepository;
    @Mock private CallJobService callJobService;
    @Mock private CallProvider callProvider;

    private LiveKitWebhookService service;
    private CallSession session;
    private CallParticipant participant;

    @BeforeEach
    void setUp() {
        service = new LiveKitWebhookService(providerEventRepository, callSessionRepository, callParticipantRepository,
                callJobService);
        session = CallSession.builder().callSessionId(10L).appointmentId(1).status(CallSession.LIVE)
                .roomName("cn_room").build();
        participant = CallParticipant.builder().callParticipantId(7L).callSessionId(10L).systemId("PAT-00001")
                .participantRole("PATIENT").status(CallParticipant.ADMITTED).providerIdentity("p_abc")
                .displayName("Thomas Dube (Patient)").actingForPatient(false).removalPending(false).build();
        when(callSessionRepository.findByRoomName("cn_room")).thenReturn(Optional.of(session));
        when(callSessionRepository.findForUpdateById(10L)).thenReturn(Optional.of(session));
        when(callParticipantRepository.findByProviderIdentity("p_abc")).thenReturn(Optional.of(participant));
        when(callParticipantRepository.save(any(CallParticipant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(providerEventRepository.insertIfAbsent(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(1);
        when(providerEventRepository.findByProviderAndEventId(anyString(), anyString()))
                .thenReturn(Optional.of(new ProviderEvent()));
    }

    private static CallProvider.WebhookEventData event(String id, String type, long epochSeconds, String identity) {
        return new CallProvider.WebhookEventData(id, type, epochSeconds, "cn_room", identity);
    }

    @Test
    public void duplicateEvent_isNotProcessedTwice() {
        when(providerEventRepository.insertIfAbsent(eq("LIVEKIT"), eq("EV_1"), anyString(), any(), any(), any()))
                .thenReturn(1).thenReturn(0);

        assertEquals(ProviderEvent.OUTCOME_APPLIED, service.process(event("EV_1", "participant_joined", 1000, "p_abc"), "{}"));
        Instant joined = participant.getProviderJoinedAt();
        assertNull(service.process(event("EV_1", "participant_joined", 2000, "p_abc"), "{}"));

        assertEquals(joined, participant.getProviderJoinedAt());
        verify(callParticipantRepository, times(1)).save(participant);
    }

    @Test
    public void blankEventId_usesBodyHash() {
        service.process(event("", "room_started", 1000, null), "{\"event\":\"room_started\"}");
        verify(providerEventRepository).insertIfAbsent(eq("LIVEKIT"), org.mockito.ArgumentMatchers.startsWith("sha256:"),
                eq("room_started"), any(), any(), any());
    }

    @Test
    public void outOfOrder_leftBeforeJoined_staysDisconnected() {
        assertEquals(ProviderEvent.OUTCOME_APPLIED, service.process(event("EV_L", "participant_left", 2000, "p_abc"), "{}"));
        assertEquals(ProviderEvent.OUTCOME_APPLIED, service.process(event("EV_J", "participant_joined", 1000, "p_abc"), "{}"));

        assertFalse(participant.isConnected());
        assertEquals(CallParticipant.ADMITTED, participant.getStatus(), "network drops never change the status");
    }

    @Test
    public void olderJoinedEvent_isStale() {
        service.process(event("EV_2", "participant_joined", 2000, "p_abc"), "{}");
        assertEquals(ProviderEvent.OUTCOME_STALE, service.process(event("EV_1", "participant_joined", 1000, "p_abc"), "{}"));
        assertEquals(Instant.ofEpochSecond(2000), participant.getProviderJoinedAt());
    }

    @Test
    public void joinedAfterRevocation_enqueuesRemoval() {
        participant.setStatus(CallParticipant.REMOVED);
        participant.setRevokedAt(Instant.ofEpochSecond(500));

        assertEquals(ProviderEvent.OUTCOME_REMOVAL_ENQUEUED,
                service.process(event("EV_3", "participant_joined", 1000, "p_abc"), "{}"));
        verify(callJobService).enqueueRemoveIdentity(session, 7L, "cn_room", "p_abc");
    }

    @Test
    public void unknownIdentityInKnownRoom_enqueuesRemoval() {
        assertEquals(ProviderEvent.OUTCOME_REMOVAL_ENQUEUED,
                service.process(event("EV_4", "participant_joined", 1000, "p_intruder"), "{}"));
        verify(callJobService).enqueueRemoveIdentity(session, null, "cn_room", "p_intruder");
    }

    @Test
    public void roomFinished_neverEndsLiveSession() {
        assertEquals(ProviderEvent.OUTCOME_IGNORED, service.process(event("EV_5", "room_finished", 1000, null), "{}"));
        assertEquals(CallSession.LIVE, session.getStatus());
    }

    @Test
    public void unknownRoom_isIgnored() {
        CallProvider.WebhookEventData e = new CallProvider.WebhookEventData("EV_6", "participant_joined", 1000,
                "cn_unknown", "p_abc");
        assertEquals(ProviderEvent.OUTCOME_IGNORED, service.process(e, "{}"));
        verify(callJobService, never()).enqueueRemoveIdentity(any(), any(), any(), any());
    }

    // ==================== CONTROLLER ====================

    @Test
    public void controller_badSignature_is401_andNothingProcessed() {
        LiveKitProperties props = new LiveKitProperties();
        props.setWebhookEnabled(true);
        when(callProvider.isConfigured()).thenReturn(true);
        when(callProvider.parseWebhook(anyString(), anyString())).thenThrow(new InvalidWebhookSignatureException());
        LiveKitWebhookService webhookService = mock(LiveKitWebhookService.class);
        LiveKitWebhookController controller = new LiveKitWebhookController(props, callProvider, webhookService);

        ResponseEntity<Map<String, Object>> response = controller.receive("{}", "bad-jwt");

        assertEquals(401, response.getStatusCode().value());
        assertEquals("Invalid webhook signature", response.getBody().get("message"));
        verifyNoInteractions(webhookService);
    }

    @Test
    public void controller_missingAuthorization_is401() {
        LiveKitProperties props = new LiveKitProperties();
        props.setWebhookEnabled(true);
        when(callProvider.isConfigured()).thenReturn(true);
        LiveKitWebhookService webhookService = mock(LiveKitWebhookService.class);
        LiveKitWebhookController controller = new LiveKitWebhookController(props, callProvider, webhookService);

        assertEquals(401, controller.receive("{}", null).getStatusCode().value());
        verifyNoInteractions(webhookService);
    }

    @Test
    public void controller_disabledOrNotConfigured_is404() {
        LiveKitProperties props = new LiveKitProperties();
        props.setWebhookEnabled(true);
        when(callProvider.isConfigured()).thenReturn(false);
        LiveKitWebhookController controller = new LiveKitWebhookController(props, callProvider,
                mock(LiveKitWebhookService.class));

        assertEquals(404, controller.receive("{}", "jwt").getStatusCode().value());
    }

    @Test
    public void controller_oversizedBody_is400() {
        LiveKitProperties props = new LiveKitProperties();
        props.setWebhookEnabled(true);
        when(callProvider.isConfigured()).thenReturn(true);
        LiveKitWebhookController controller = new LiveKitWebhookController(props, callProvider,
                mock(LiveKitWebhookService.class));

        assertEquals(400, controller.receive("x".repeat(64 * 1024 + 1), "jwt").getStatusCode().value());
    }
}
