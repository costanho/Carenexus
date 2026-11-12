package com.carenexus.api.delivery.push;

import com.carenexus.api.delivery.DeliveryException;
import com.carenexus.api.delivery.SendResult;

/**
 * Provider seam for push notifications. Selected by {@code carenexus.delivery.push.provider}: {@code local}
 * (default), {@code disabled}, or a real provider id ({@code expo}, {@code fcm}) once an adapter is added to
 * {@code DeliveryConfig}. Callers queue through {@code OutboundMessageService}; the outbox worker calls {@link #send}.
 *
 * An adapter that learns from its provider that a token is no longer registered removes it with
 * {@code PushDeviceService.forgetTokens}.
 */
public interface PushSender {

    /** Lower-case provider id, for example {@code local}, {@code disabled}, {@code expo}. */
    String providerName();

    /** False when the provider cannot send (disabled, or missing credentials): messages are marked SKIPPED. */
    boolean isConfigured();

    /**
     * Hands one notification for all of the recipient's devices to the provider. Implementations use bounded
     * timeouts and never log the text or a token.
     *
     * @throws DeliveryException when the provider did not accept the message; {@code retryable} decides whether
     *                           the outbox tries again
     */
    SendResult send(PushMessage message) throws DeliveryException;
}
