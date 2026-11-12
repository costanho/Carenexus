package com.carenexus.api.delivery.config;

import com.carenexus.api.delivery.email.DisabledEmailSender;
import com.carenexus.api.delivery.email.EmailSender;
import com.carenexus.api.delivery.email.LocalOutboxEmailSender;
import com.carenexus.api.delivery.push.DisabledPushSender;
import com.carenexus.api.delivery.push.LocalOutboxPushSender;
import com.carenexus.api.delivery.push.PushSender;
import com.carenexus.api.delivery.sms.DisabledSmsSender;
import com.carenexus.api.delivery.sms.LocalOutboxSmsSender;
import com.carenexus.api.delivery.sms.SmsSender;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Outbound delivery wiring: properties, scheduling (the outbox worker) and one sender per channel, chosen by
 * {@code carenexus.delivery.<channel>.provider}.
 *
 * A real provider is added here: write an adapter that implements {@link EmailSender}, {@link SmsSender} or
 * {@link PushSender} and return it from the matching method for its provider id. An id nobody implements fails
 * startup, so a typo never silently falls back to the local implementation.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(DeliveryProperties.class)
public class DeliveryConfig {

    @Bean
    public EmailSender emailSender(DeliveryProperties properties) {
        String provider = properties.getEmail().providerId();
        return switch (provider) {
            case DeliveryProperties.LOCAL -> new LocalOutboxEmailSender();
            case DeliveryProperties.DISABLED -> new DisabledEmailSender();
            default -> throw unknown("email", provider);
        };
    }

    @Bean
    public SmsSender smsSender(DeliveryProperties properties) {
        String provider = properties.getSms().providerId();
        return switch (provider) {
            case DeliveryProperties.LOCAL -> new LocalOutboxSmsSender();
            case DeliveryProperties.DISABLED -> new DisabledSmsSender();
            default -> throw unknown("sms", provider);
        };
    }

    @Bean
    public PushSender pushSender(DeliveryProperties properties) {
        String provider = properties.getPush().providerId();
        return switch (provider) {
            case DeliveryProperties.LOCAL -> new LocalOutboxPushSender();
            case DeliveryProperties.DISABLED -> new DisabledPushSender();
            default -> throw unknown("push", provider);
        };
    }

    private static IllegalStateException unknown(String channel, String provider) {
        return new IllegalStateException("carenexus.delivery." + channel + ".provider is '" + provider
                + "', which this build does not support. Use 'local' or 'disabled', or add the provider adapter.");
    }
}
