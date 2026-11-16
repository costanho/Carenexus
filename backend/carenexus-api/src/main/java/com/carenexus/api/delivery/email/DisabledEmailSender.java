package com.carenexus.api.delivery.email;

import com.carenexus.api.delivery.DeliveryException;
import com.carenexus.api.delivery.SendResult;

/**
 * Email switched off ({@code carenexus.delivery.email.provider=disabled}): the channel is reported as unavailable
 * and the outbox worker marks email rows SKIPPED without calling {@link #send}.
 */
public class DisabledEmailSender implements EmailSender {

    public static final String PROVIDER = "disabled";

    @Override
    public String providerName() {
        return PROVIDER;
    }

    @Override
    public boolean isConfigured() {
        return false;
    }

    @Override
    public SendResult send(EmailMessage message) {
        throw new DeliveryException("Provider disabled", false);
    }
}
