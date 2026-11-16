package com.carenexus.api.delivery;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * One message for the outbox, the general form behind {@link OutboundMessageService#queue(OutboundRequest)}.
 *
 * @param channel           {@code EMAIL}, {@code SMS} or {@code PUSH} (see {@link DeliveryChannel})
 * @param toAddress         email address (EMAIL) or phone number (SMS); null for PUSH. A message without an address
 *                          is stored and later marked SKIPPED
 * @param recipientSystemId account the message is for; required for PUSH (its devices are resolved when sending),
 *                          optional for EMAIL and SMS (null for an address that has no account)
 * @param templateId        EMAIL, SMS: file {@code messages/<templateId>.txt}, rendered when the message is queued
 * @param model             placeholder values of the template; a missing value renders empty
 * @param title             PUSH title (also used as the subject of an EMAIL queued without a template)
 * @param body              PUSH body (also the text of an EMAIL or SMS queued without a template)
 * @param data              PUSH data payload; null for EMAIL and SMS
 * @param dedupeKey         optional idempotency key, unique across the whole outbox (at most 200 characters);
 *                          a repeated key stores nothing and returns the existing message id
 * @param notificationId    the in-app notification this message was fanned out from, or null
 * @param notBefore         earliest send time as clinic wall-clock time ({@code carenexus.scheduling.time-zone});
 *                          null = now. Stored as {@code next_attempt_at}
 */
public record OutboundRequest(String channel, String toAddress, String recipientSystemId, String templateId,
                              Map<String, String> model, String title, String body, Map<String, String> data,
                              String dedupeKey, Integer notificationId, LocalDateTime notBefore) {

    /** Never prints the address, the text or the payload. */
    @Override
    public String toString() {
        return "OutboundRequest[channel=" + channel + ", recipientSystemId=" + recipientSystemId
                + ", templateId=" + templateId + ", notificationId=" + notificationId
                + ", notBefore=" + notBefore + "]";
    }
}
