package com.carenexus.api.delivery.sms;

import com.carenexus.api.delivery.DeliveryException;
import com.carenexus.api.delivery.SendResult;

/**
 * SMS switched off ({@code carenexus.delivery.sms.provider=disabled}): the channel is reported as unavailable and
 * the outbox worker marks SMS rows SKIPPED without calling {@link #send}.
 */
public class DisabledSmsSender implements SmsSender {

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
    public SendResult send(SmsMessage message) {
        throw new DeliveryException("Provider disabled", false);
    }
}
