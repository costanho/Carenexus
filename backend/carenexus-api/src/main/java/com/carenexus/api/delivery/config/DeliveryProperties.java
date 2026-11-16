package com.carenexus.api.delivery.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;

/**
 * Outbound delivery settings ({@code carenexus.delivery.*}). Defaults live here; application.yml only overrides.
 *
 * <pre>
 * carenexus.delivery.email.provider = local | disabled   (later: smtp, ses)
 * carenexus.delivery.sms.provider   = local | disabled   (later: twilio, africastalking)
 * carenexus.delivery.push.provider  = local | disabled   (later: expo, fcm)
 * carenexus.delivery.max-attempts   = 5      send attempts before a message becomes FAILED
 * carenexus.delivery.batch-size     = 200    messages per worker pass
 * </pre>
 */
@Getter
@Setter
@ConfigurationProperties("carenexus.delivery")
public class DeliveryProperties {

    public static final String LOCAL = "local";
    public static final String DISABLED = "disabled";

    private Channel email = new Channel();
    private Channel sms = new Channel();
    private Channel push = new Channel();

    /** Send attempts after which a message becomes FAILED (stored on each row when it is queued). */
    private int maxAttempts = 5;

    /** Most messages one worker pass handles. */
    private int batchSize = 200;

    @Getter
    @Setter
    public static class Channel {

        /** Provider id; {@code local} keeps messages on this machine, {@code disabled} skips them. */
        private String provider = LOCAL;

        /** The provider id trimmed and lower-cased; a blank value means {@code local}. */
        public String providerId() {
            String value = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
            return value.isEmpty() ? LOCAL : value;
        }
    }

    public int safeMaxAttempts() {
        return Math.max(1, Math.min(maxAttempts, 50));
    }

    public int safeBatchSize() {
        return Math.max(1, Math.min(batchSize, 200));
    }
}
