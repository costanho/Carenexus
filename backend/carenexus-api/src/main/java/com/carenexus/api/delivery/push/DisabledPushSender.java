package com.carenexus.api.delivery.push;

import com.carenexus.api.delivery.DeliveryException;
import com.carenexus.api.delivery.SendResult;

/**
 * Push switched off ({@code carenexus.delivery.push.provider=disabled}): the channel is reported as unavailable and
 * the outbox worker marks push rows SKIPPED without calling {@link #send}.
 */
public class DisabledPushSender implements PushSender {

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
    public SendResult send(PushMessage message) {
        throw new DeliveryException("Provider disabled", false);
    }
}
