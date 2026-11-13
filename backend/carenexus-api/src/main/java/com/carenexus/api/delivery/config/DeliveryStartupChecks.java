package com.carenexus.api.delivery.config;

import com.carenexus.api.common.config.LocalProviderGuard;
import com.carenexus.api.delivery.email.EmailSender;
import com.carenexus.api.delivery.email.LocalOutboxEmailSender;
import com.carenexus.api.delivery.push.LocalOutboxPushSender;
import com.carenexus.api.delivery.push.PushSender;
import com.carenexus.api.delivery.sms.LocalOutboxSmsSender;
import com.carenexus.api.delivery.sms.SmsSender;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Applies the provider-seam startup rule ({@link LocalProviderGuard}) to the three delivery channels: a local
 * sender is fine on developer profiles, logs a warning on staging and refuses to start in production unless
 * {@code carenexus.allow-local-providers=true}. A disabled channel is an explicit choice and always starts.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryStartupChecks {

    private final LocalProviderGuard localProviderGuard;
    private final EmailSender emailSender;
    private final SmsSender smsSender;
    private final PushSender pushSender;

    @PostConstruct
    void check() {
        localProviderGuard.check("email", emailSender.providerName(), emailSender instanceof LocalOutboxEmailSender);
        localProviderGuard.check("SMS", smsSender.providerName(), smsSender instanceof LocalOutboxSmsSender);
        localProviderGuard.check("push", pushSender.providerName(), pushSender instanceof LocalOutboxPushSender);
        log.info("Outbound delivery providers: email={}, sms={}, push={}",
                emailSender.providerName(), smsSender.providerName(), pushSender.providerName());
    }
}
