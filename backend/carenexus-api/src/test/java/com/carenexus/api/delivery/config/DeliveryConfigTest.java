package com.carenexus.api.delivery.config;

import com.carenexus.api.common.config.EnvironmentInfo;
import com.carenexus.api.common.config.LocalProviderGuard;
import com.carenexus.api.delivery.DeliveryException;
import com.carenexus.api.delivery.Redaction;
import com.carenexus.api.delivery.email.DisabledEmailSender;
import com.carenexus.api.delivery.email.EmailMessage;
import com.carenexus.api.delivery.email.EmailSender;
import com.carenexus.api.delivery.email.LocalOutboxEmailSender;
import com.carenexus.api.delivery.push.DisabledPushSender;
import com.carenexus.api.delivery.push.LocalOutboxPushSender;
import com.carenexus.api.delivery.push.PushMessage;
import com.carenexus.api.delivery.push.PushSender;
import com.carenexus.api.delivery.push.PushTarget;
import com.carenexus.api.delivery.sms.DisabledSmsSender;
import com.carenexus.api.delivery.sms.LocalOutboxSmsSender;
import com.carenexus.api.delivery.sms.SmsMessage;
import com.carenexus.api.delivery.sms.SmsSender;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Provider selection and the startup rule of the delivery seam, without a Spring context.
 */
class DeliveryConfigTest {

    private final DeliveryConfig config = new DeliveryConfig();

    private static DeliveryProperties properties(String email, String sms, String push) {
        DeliveryProperties properties = new DeliveryProperties();
        properties.getEmail().setProvider(email);
        properties.getSms().setProvider(sms);
        properties.getPush().setProvider(push);
        return properties;
    }

    private static LocalProviderGuard guard(String environment, boolean allowLocal, String... profiles) {
        MockEnvironment spring = new MockEnvironment();
        spring.setActiveProfiles(profiles);
        return new LocalProviderGuard(new EnvironmentInfo(spring, environment), allowLocal);
    }

    private DeliveryStartupChecks checks(LocalProviderGuard guard, DeliveryProperties properties) {
        return new DeliveryStartupChecks(guard, config.emailSender(properties), config.smsSender(properties),
                config.pushSender(properties));
    }

    // ==================== SELECTION ====================

    @Test
    void localIsTheDefaultOnEveryChannel() {
        DeliveryProperties defaults = new DeliveryProperties();

        EmailSender email = config.emailSender(defaults);
        SmsSender sms = config.smsSender(defaults);
        PushSender push = config.pushSender(defaults);

        assertInstanceOf(LocalOutboxEmailSender.class, email);
        assertInstanceOf(LocalOutboxSmsSender.class, sms);
        assertInstanceOf(LocalOutboxPushSender.class, push);
        assertEquals("local", email.providerName());
        assertEquals("local", sms.providerName());
        assertEquals("local", push.providerName());
        assertTrue(email.isConfigured() && sms.isConfigured() && push.isConfigured());
        assertEquals(5, defaults.getMaxAttempts());
        assertEquals(200, defaults.getBatchSize());
    }

    @Test
    void disabledSelectsTheDisabledSender_perChannel() {
        DeliveryProperties properties = properties(" Disabled ", "local", "DISABLED");

        EmailSender email = config.emailSender(properties);
        PushSender push = config.pushSender(properties);

        assertInstanceOf(DisabledEmailSender.class, email);
        assertInstanceOf(LocalOutboxSmsSender.class, config.smsSender(properties));
        assertInstanceOf(DisabledPushSender.class, push);
        assertEquals("disabled", email.providerName());
        assertFalse(email.isConfigured());
        assertFalse(push.isConfigured());
        assertInstanceOf(DisabledSmsSender.class, config.smsSender(properties("local", "disabled", "local")));
    }

    @Test
    void aBlankProviderMeansLocal() {
        DeliveryProperties properties = properties("", null, "  ");

        assertInstanceOf(LocalOutboxEmailSender.class, config.emailSender(properties));
        assertInstanceOf(LocalOutboxSmsSender.class, config.smsSender(properties));
        assertInstanceOf(LocalOutboxPushSender.class, config.pushSender(properties));
    }

    @Test
    void anUnknownProvider_failsStartup_insteadOfFallingBackToLocal() {
        IllegalStateException email = assertThrows(IllegalStateException.class,
                () -> config.emailSender(properties("ses", "local", "local")));
        assertTrue(email.getMessage().contains("carenexus.delivery.email.provider"));
        assertTrue(email.getMessage().contains("'ses'"));
        assertThrows(IllegalStateException.class, () -> config.smsSender(properties("local", "twilio", "local")));
        assertThrows(IllegalStateException.class, () -> config.pushSender(properties("local", "local", "lcoal")));
    }

    // ==================== SENDERS ====================

    @Test
    void localSenders_acceptEveryMessage_andAnswerWithALocalId() {
        assertEquals("local-42", new LocalOutboxEmailSender().send(
                new EmailMessage("john.doe@example.com", "Subject", "Body", "delivery-test", "42")).providerMessageId());
        assertEquals("local-43", new LocalOutboxSmsSender().send(
                new SmsMessage("+263771234567", "Text", "delivery-test", "43")).providerMessageId());
        assertEquals("local-44", new LocalOutboxPushSender().send(new PushMessage("PAT-00001",
                List.of(new PushTarget("IOS", "device-token")), "Title", "Text", Map.of(), "44")).providerMessageId());
    }

    @Test
    void disabledSenders_refuseToSend() {
        DeliveryException email = assertThrows(DeliveryException.class, () -> new DisabledEmailSender().send(
                new EmailMessage("john.doe@example.com", "Subject", "Body", null, "1")));
        assertEquals("Provider disabled", email.getMessage());
        assertFalse(email.isRetryable());
        assertThrows(DeliveryException.class, () -> new DisabledSmsSender().send(
                new SmsMessage("+263771234567", "Text", null, "2")));
        assertThrows(DeliveryException.class, () -> new DisabledPushSender().send(new PushMessage("PAT-00001",
                List.of(), "Title", "Text", Map.of(), "3")));
    }

    @Test
    void messagesNeverPrintTheirTextAddressOrTokens() {
        String email = new EmailMessage("john.doe@example.com", "Secret subject", "Secret body", "delivery-test", "42").toString();
        assertFalse(email.contains("john.doe"));
        assertFalse(email.contains("Secret"));
        assertTrue(email.contains("j***@example.com"));

        String sms = new SmsMessage("+263771234567", "Secret text", "delivery-test", "43").toString();
        assertFalse(sms.contains("+26377123"));
        assertFalse(sms.contains("Secret"));
        assertTrue(sms.contains("***4567"));

        String push = new PushMessage("PAT-00001", List.of(new PushTarget("IOS", "secret-device-token")), "Secret title",
                "Secret text", Map.of("k", "secret-value"), "44").toString();
        assertFalse(push.contains("secret"));
        assertFalse(push.contains("Secret"));
        assertFalse(new PushTarget("IOS", "secret-device-token").toString().contains("secret"));
    }

    @Test
    void redaction_masksAddresses() {
        assertEquals("j***@example.com", Redaction.maskEmail(" john.doe@example.com "));
        assertEquals("***", Redaction.maskEmail("not-an-address"));
        assertEquals("***", Redaction.maskEmail("@example.com"));
        assertEquals("-", Redaction.maskEmail(null));
        assertEquals("-", Redaction.maskEmail(" "));
        assertEquals("***4567", Redaction.maskPhone("+263771234567"));
        assertEquals("***", Redaction.maskPhone("12345"));
        assertEquals("-", Redaction.maskPhone(null));
    }

    // ==================== STARTUP RULE ====================

    @Test
    void localSenders_startOnDeveloperProfiles_andOnStaging() {
        DeliveryProperties defaults = new DeliveryProperties();
        assertDoesNotThrow(() -> checks(guard("development", false, "local"), defaults).check());
        assertDoesNotThrow(() -> checks(guard("development", false, "test"), defaults).check());
        assertDoesNotThrow(() -> checks(guard("staging", false), defaults).check(), "staging only warns");
    }

    @Test
    void localSenders_refuseToStartInProduction_unlessExplicitlyAllowed() {
        DeliveryProperties defaults = new DeliveryProperties();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> checks(guard("production", false), defaults).check());
        assertTrue(ex.getMessage().contains("email"));
        assertTrue(ex.getMessage().contains("carenexus.allow-local-providers"));

        // One local channel is enough to refuse.
        IllegalStateException sms = assertThrows(IllegalStateException.class,
                () -> checks(guard("production", false), properties("disabled", "local", "disabled")).check());
        assertTrue(sms.getMessage().contains("SMS"));
        IllegalStateException push = assertThrows(IllegalStateException.class,
                () -> checks(guard("production", false), properties("disabled", "disabled", "local")).check());
        assertTrue(push.getMessage().contains("push"));

        assertDoesNotThrow(() -> checks(guard("production", true), defaults).check());
    }

    @Test
    void disabledChannels_alwaysStart() {
        assertDoesNotThrow(() -> checks(guard("production", false), properties("disabled", "disabled", "disabled")).check());
    }
}
