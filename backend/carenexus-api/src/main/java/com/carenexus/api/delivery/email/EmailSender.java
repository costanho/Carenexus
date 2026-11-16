package com.carenexus.api.delivery.email;

import com.carenexus.api.delivery.DeliveryException;
import com.carenexus.api.delivery.SendResult;

/**
 * Provider seam for email. Selected by {@code carenexus.delivery.email.provider}: {@code local} (default),
 * {@code disabled}, or a real provider id ({@code smtp}, {@code ses}) once an adapter is added to
 * {@code DeliveryConfig}. Callers never use a sender directly: they queue through {@code OutboundMessageService}
 * and the outbox worker calls {@link #send}.
 */
public interface EmailSender {

    /** Lower-case provider id, for example {@code local}, {@code disabled}, {@code ses}. */
    String providerName();

    /** False when the provider cannot send (disabled, or missing credentials): messages are marked SKIPPED. */
    boolean isConfigured();

    /**
     * Hands one message to the provider. Implementations use bounded timeouts and never log the text.
     *
     * @throws DeliveryException when the provider did not accept the message; {@code retryable} decides whether
     *                           the outbox tries again
     */
    SendResult send(EmailMessage message) throws DeliveryException;
}
