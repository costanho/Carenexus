package com.carenexus.api.calls.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LiveKit connection settings ({@code carenexus.livekit.*}). The API key and secret are never logged.
 */
@Getter
@Setter
@ConfigurationProperties("carenexus.livekit")
public class LiveKitProperties {

    private boolean enabled = false;
    /** Server-side API URL (http/https), used by the API only. */
    private String url;
    /** URL returned to browsers as serverUrl (ws/wss). Falls back to {@link #url} when blank. */
    private String clientUrl;
    private String apiKey;
    private String apiSecret;
    private boolean webhookEnabled = false;
    private int tokenTtlSeconds = 300;
    private int maxParticipants = 6;

    /** enabled and url, api-key and api-secret are all set. */
    public boolean isConfigured() {
        return enabled && notBlank(url) && notBlank(apiKey) && notBlank(apiSecret);
    }

    public String effectiveClientUrl() {
        return notBlank(clientUrl) ? clientUrl.trim() : (url == null ? null : url.trim());
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public String toString() {
        // Never print the key or secret.
        return "LiveKitProperties{enabled=" + enabled + ", configured=" + isConfigured()
                + ", webhookEnabled=" + webhookEnabled + "}";
    }
}
