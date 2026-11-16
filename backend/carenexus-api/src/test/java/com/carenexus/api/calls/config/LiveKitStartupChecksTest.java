package com.carenexus.api.calls.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveKitStartupChecksTest {

    private static final String REAL_SECRET = "0123456789abcdef0123456789abcdef-real";

    private static LiveKitProperties props(String secret, boolean webhook) {
        LiveKitProperties p = new LiveKitProperties();
        p.setEnabled(true);
        p.setUrl("https://livekit.example.org");
        p.setApiKey("key");
        p.setApiSecret(secret);
        p.setWebhookEnabled(webhook);
        return p;
    }

    private static MockEnvironment env(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }

    @Test
    void productionWithWebhookOffFailsStartup() {
        assertThatThrownBy(() -> new LiveKitStartupChecks(props(REAL_SECRET, false), env("prod")).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("webhook-enabled");
    }

    @Test
    void productionWithWebhookOnStarts() {
        assertThatCode(() -> new LiveKitStartupChecks(props(REAL_SECRET, true), env("prod")).check())
                .doesNotThrowAnyException();
    }

    @Test
    void devSecretOutsideLocalFailsStartup() {
        assertThatThrownBy(() -> new LiveKitStartupChecks(props("secret", true), env("prod")).check())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void localProfileIsNotChecked() {
        assertThatCode(() -> new LiveKitStartupChecks(props("secret", false), env("local")).check())
                .doesNotThrowAnyException();
    }
}
