package com.carenexus.api.delivery.email;

import com.carenexus.api.delivery.Redaction;
import com.carenexus.api.delivery.SendResult;
import lombok.extern.slf4j.Slf4j;

/**
 * Local email implementation ({@code carenexus.delivery.email.provider=local}, the default): nothing leaves the
 * machine. The rendered message stays in its {@code outbound_messages} row, which the worker marks SENT with
 * provider {@code local}; developers and e2e tests read it through the development outbox endpoint.
 */
@Slf4j
public class LocalOutboxEmailSender implements EmailSender {

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
    public SendResult send(EmailMessage message) {
        log.info("Local outbox: EMAIL template={} to={} kept in outbound_messages (not sent)",
                message.templateId(), Redaction.maskEmail(message.to()));
        return new SendResult(PROVIDER + "-" + message.messageKey());
    }
}
