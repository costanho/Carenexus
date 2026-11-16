package com.carenexus.api.delivery.push;

import java.util.List;
import java.util.Map;

/**
 * One push notification handed to a {@link PushSender}: the same content for every registered device of the
 * recipient (resolved by the outbox worker when the message is sent, never empty).
 *
 * @param recipientSystemId account the notification is for
 * @param targets           the recipient's devices, most recently seen first
 * @param title             notification title
 * @param body              notification text (generic, never a clinical value)
 * @param data              data payload (for example {@code actionUrl}); never null
 * @param messageKey        stable key of the outbox row (its id); the same for every attempt
 */
public record PushMessage(String recipientSystemId, List<PushTarget> targets, String title, String body,
                          Map<String, String> data, String messageKey) {

    /** Never prints the text, the payload or a token. */
    @Override
    public String toString() {
        return "PushMessage[recipientSystemId=" + recipientSystemId + ", devices="
                + (targets == null ? 0 : targets.size()) + ", messageKey=" + messageKey + "]";
    }
}
