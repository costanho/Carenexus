package com.carenexus.api.calls;

import com.carenexus.api.calls.config.LiveKitProperties;
import com.carenexus.api.calls.provider.CallProvider;
import com.carenexus.api.calls.provider.InvalidWebhookSignatureException;
import com.carenexus.api.calls.provider.LiveKitCallProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LiveKit adapter without a server: token claims and webhook signature rejection.
 */
public class LiveKitCallProviderTest {

    private static LiveKitCallProvider provider() {
        LiveKitProperties props = new LiveKitProperties();
        props.setEnabled(true);
        props.setUrl("http://localhost:7880");
        props.setClientUrl("ws://localhost:7880");
        props.setApiKey("devkey");
        props.setApiSecret("unit-test-secret-0123456789abcdefghijklmnop");
        return new LiveKitCallProvider(props);
    }

    @Test
    public void token_deniesDataChannel_limitsSources_andExpiresInFiveMinutes() throws Exception {
        Instant expiresAt = Instant.now().plusSeconds(300);
        CallProvider.IssuedToken token = provider().issueToken(new CallProvider.TokenRequest("cn_room", "p_abc",
                "Sarah Dube (Caregiver for Thomas Dube)", "{\"role\":\"CAREGIVER\",\"actingForPatient\":false}",
                expiresAt));

        String[] parts = token.token().split("\\.");
        JsonNode claims = new ObjectMapper().readTree(
                new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        assertEquals("p_abc", claims.get("sub").asText());
        assertEquals("Sarah Dube (Caregiver for Thomas Dube)", claims.get("name").asText());
        JsonNode video = claims.get("video");
        assertTrue(video.get("roomJoin").asBoolean());
        assertEquals("cn_room", video.get("room").asText());
        assertFalse(video.get("canPublishData").asBoolean());
        assertEquals(2, video.get("canPublishSources").size());
        assertEquals(expiresAt.getEpochSecond(), claims.get("exp").asLong());
        assertFalse(token.token().contains("PAT-") || claims.toString().contains("@"));
    }

    @Test
    public void webhook_withBadSignature_isRejected() {
        assertThrows(InvalidWebhookSignatureException.class,
                () -> provider().parseWebhook("{\"event\":\"room_started\"}", "not-a-jwt"));
    }

    @Test
    public void notConfiguredWithoutSecret() {
        LiveKitProperties props = new LiveKitProperties();
        props.setEnabled(true);
        props.setUrl("http://localhost:7880");
        props.setApiKey("devkey");
        assertFalse(props.isConfigured());
    }
}
