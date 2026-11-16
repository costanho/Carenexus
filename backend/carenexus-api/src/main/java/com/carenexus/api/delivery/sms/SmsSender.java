package com.carenexus.api.delivery.sms;

import com.carenexus.api.delivery.DeliveryException;
import com.carenexus.api.delivery.SendResult;

/**
 * Provider seam for SMS. Selected by {@code carenexus.delivery.sms.provider}: {@code local} (default),
 * {@code disabled}, or a real provider id ({@code twilio}, {@code africastalking}) once an adapter is added to
 * {@code DeliveryConfig}. Callers queue through {@code OutboundMessageService}; the outbox worker calls {@link #send}.
 */
public interface SmsSender {

    /** Lower-case provider id, for example {@code local}, {@code disabled}, {@code twilio}. */
    String providerName();

    /** False when the provider cannot send (disabled, or missing credentials): messages are marked SKIPPED. */
    boolean isConfigured();

    /**
     * Hands one message to the provider. Implementations use bounded timeouts and never log the text.
     *
     * @throws DeliveryException when the provider did not accept the message; {@code retryable} decides whether
     *                           the outbox tries again
     */
    SendResult send(SmsMessage message) throws DeliveryException;
}
