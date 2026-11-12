package com.carenexus.api.calls.service;

import com.carenexus.api.calls.model.CallParticipant;
import com.carenexus.api.calls.model.CallSession;
import com.carenexus.api.calls.model.ProviderEvent;
import com.carenexus.api.calls.provider.CallProvider;
import com.carenexus.api.calls.repository.CallParticipantRepository;
import com.carenexus.api.calls.repository.CallSessionRepository;
import com.carenexus.api.calls.repository.ProviderEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Applies verified LiveKit webhooks. Idempotent by event id (duplicates are recorded once and skipped) and tolerant
 * of out-of-order delivery (presence timestamps only move forward). A finished room never ends a LIVE session.
 * Presence of anyone who is not an ADMITTED, unrevoked participant of a LIVE session is removed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class LiveKitWebhookService {

    public static final String PROVIDER = "LIVEKIT";
    static final String PARTICIPANT_JOINED = "participant_joined";
    static final String PARTICIPANT_LEFT = "participant_left";

    private final ProviderEventRepository providerEventRepository;
    private final CallSessionRepository callSessionRepository;
    private final CallParticipantRepository callParticipantRepository;
    private final CallJobService callJobService;

    /** @return the outcome, or null when the event was a duplicate delivery. */
    public String process(CallProvider.WebhookEventData event, String rawBody) {
        String eventId = event.id() == null || event.id().isBlank() ? sha256(rawBody) : event.id();
        String eventType = event.event() == null || event.event().isBlank() ? "unknown" : truncate(event.event(), 50);
        Instant eventTime = event.createdAtEpochSeconds() > 0
                ? Instant.ofEpochSecond(event.createdAtEpochSeconds())
                : Instant.now();
        String roomName = truncate(event.roomName(), 64);
        String identity = truncate(event.identity(), 64);

        int inserted = providerEventRepository.insertIfAbsent(PROVIDER, truncate(eventId, 100), eventType, roomName,
                identity, eventTime);
        if (inserted == 0) {
            return null;
        }
        String outcome = apply(eventType, roomName, identity, eventTime);
        providerEventRepository.findByProviderAndEventId(PROVIDER, truncate(eventId, 100)).ifPresent(row -> {
            row.setOutcome(outcome);
            row.setProcessedAt(Instant.now());
            providerEventRepository.save(row);
        });
        return outcome;
    }

    String apply(String eventType, String roomName, String identity, Instant eventTime) {
        if (!PARTICIPANT_JOINED.equals(eventType) && !PARTICIPANT_LEFT.equals(eventType)) {
            return ProviderEvent.OUTCOME_IGNORED;
        }
        if (roomName == null || identity == null) {
            return ProviderEvent.OUTCOME_IGNORED;
        }
        CallSession found = callSessionRepository.findByRoomName(roomName).orElse(null);
        if (found == null) {
            return ProviderEvent.OUTCOME_IGNORED;
        }
        CallSession session = callSessionRepository.findForUpdateById(found.getCallSessionId()).orElse(found);
        CallParticipant participant = callParticipantRepository.findByProviderIdentity(identity)
                .filter(p -> p.getCallSessionId().equals(session.getCallSessionId()))
                .orElse(null);

        if (PARTICIPANT_LEFT.equals(eventType)) {
            if (participant == null) {
                return ProviderEvent.OUTCOME_IGNORED;
            }
            // A network drop is not a Leave: status never changes here.
            if (participant.getProviderLeftAt() == null || eventTime.isAfter(participant.getProviderLeftAt())) {
                participant.setProviderLeftAt(eventTime);
                callParticipantRepository.save(participant);
                return ProviderEvent.OUTCOME_APPLIED;
            }
            return ProviderEvent.OUTCOME_STALE;
        }

        // participant_joined
        boolean allowed = participant != null
                && CallParticipant.ADMITTED.equals(participant.getStatus())
                && participant.getRevokedAt() == null
                && CallSession.LIVE.equals(session.getStatus());
        if (!allowed) {
            if (participant != null && participant.getProviderJoinedAt() == null) {
                participant.setProviderJoinedAt(eventTime);
                callParticipantRepository.save(participant);
            }
            callJobService.enqueueRemoveIdentity(session, participant != null ? participant.getCallParticipantId() : null,
                    roomName, identity);
            log.info("Webhook: unexpected presence in call session {}; removal enqueued", session.getCallSessionId());
            return ProviderEvent.OUTCOME_REMOVAL_ENQUEUED;
        }
        if (participant.getProviderJoinedAt() == null || eventTime.isAfter(participant.getProviderJoinedAt())) {
            participant.setProviderJoinedAt(eventTime);
            callParticipantRepository.save(participant);
            return ProviderEvent.OUTCOME_APPLIED;
        }
        return ProviderEvent.OUTCOME_STALE;
    }

    static String sha256(String body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash, 0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
