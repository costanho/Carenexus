package com.carenexus.api.delivery.sms;

import com.carenexus.api.delivery.Redaction;
import com.carenexus.api.delivery.SendResult;
import lombok.extern.slf4j.Slf4j;

/**
 * Local SMS implementation ({@code carenexus.delivery.sms.provider=local}, the default): nothing leaves the
 * machine. The rendered text stays in its {@code outbound_messages} row, which the worker marks SENT with provider
 * {@code local}.
 */
@Slf4j
public class LocalOutboxSmsSender implements SmsSender {

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
    public SendResult send(SmsMessage message) {
        log.info("Local outbox: SMS template={} to={} kept in outbound_messages (not sent)",
                message.templateId(), Redaction.maskPhone(message.to()));
        return new SendResult(PROVIDER + "-" + message.messageKey());
    }
}
