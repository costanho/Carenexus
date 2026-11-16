package com.carenexus.api.delivery.push;

import com.carenexus.api.delivery.SendResult;
import lombok.extern.slf4j.Slf4j;

/**
 * Local push implementation ({@code carenexus.delivery.push.provider=local}, the default): nothing leaves the
 * machine. The title, text and data stay in the {@code outbound_messages} row, which the worker marks SENT with
 * provider {@code local}.
 */
@Slf4j
public class LocalOutboxPushSender implements PushSender {

    public static final String PROVIDER = "local";

    @Override
    public String providerName() {
        return PROVIDER;
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public SendResult send(PushMessage message) {
        log.info("Local outbox: PUSH to={} devices={} kept in outbound_messages (not sent)",
                message.recipientSystemId(), message.targets() == null ? 0 : message.targets().size());
        return new SendResult(PROVIDER + "-" + message.messageKey());
    }
}
