package com.carenexus.api.ai.generator.config;

import com.carenexus.api.ai.interaction.DisabledInteractionChecker;
import com.carenexus.api.ai.interaction.MedicationInteractionChecker;
import com.carenexus.api.ai.interaction.StaticRuleInteractionChecker;
import com.carenexus.api.common.config.EnvironmentInfo;
import com.carenexus.api.common.config.LocalProviderGuard;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Configuration of the alert generators (api/be-ai-generators.md 5.4 and 5.8): code defaults, the checker chosen by
 * {@code carenexus.ai.interaction-provider}, an unknown provider failing startup, threshold validation and the
 * provider-guard call.
 */
class AiGeneratorConfigTest {

    @Test
    void defaultsMatchTheContract() {
        AiGeneratorProperties properties = new AiGeneratorProperties();
        assertEquals(3, properties.getMissedDoseThreshold());
        assertEquals(0, new BigDecimal("25").compareTo(properties.getAnomalyDeviationPercent()));
        assertEquals("rules", properties.getInteractionProvider());
        assertEquals(30, properties.getFollowUpLookbackDays());
        assertTrue(AiGeneratorStartupChecks.problems(properties).isEmpty());
    }

    @Test
    void theProviderIdSelectsTheChecker() {
        AiGeneratorConfig config = new AiGeneratorConfig();
        AiGeneratorProperties properties = new AiGeneratorProperties();
        assertInstanceOf(StaticRuleInteractionChecker.class, config.medicationInteractionChecker(properties));

        properties.setInteractionProvider("  DISABLED ");
        MedicationInteractionChecker disabled = config.medicationInteractionChecker(properties);
        assertInstanceOf(DisabledInteractionChecker.class, disabled);
        assertEquals("disabled", disabled.providerName());

        properties.setInteractionProvider(" ");
        assertInstanceOf(StaticRuleInteractionChecker.class, config.medicationInteractionChecker(properties),
                "blank falls back to the default");
        properties.setInteractionProvider(null);
        assertInstanceOf(StaticRuleInteractionChecker.class, config.medicationInteractionChecker(properties));
    }

    @Test
    void anUnknownProviderFailsStartup() {
        AiGeneratorProperties properties = new AiGeneratorProperties();
        properties.setInteractionProvider("formulary-api");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new AiGeneratorConfig().medicationInteractionChecker(properties));
        assertTrue(failure.getMessage().contains("formulary-api"), failure.getMessage());
    }

    @Test
    void unusableThresholdsAreReported() {
        AiGeneratorProperties properties = new AiGeneratorProperties();
        properties.setMissedDoseThreshold(0);
        properties.setAnomalyDeviationPercent(BigDecimal.ZERO);
        properties.setFollowUpLookbackDays(0);
        assertEquals(List.of("missed-dose-threshold must be at least 1",
                        "anomaly-deviation-percent must be greater than 0",
                        "follow-up-lookback-days must be at least 1"),
                AiGeneratorStartupChecks.problems(properties));

        properties.setAnomalyDeviationPercent(null);
        assertTrue(AiGeneratorStartupChecks.problems(properties)
                .contains("anomaly-deviation-percent must be greater than 0"));
    }

    @Test
    void startupRefusesBadThresholdsAndReportsTheCheckerToTheGuard() {
        LocalProviderGuard guard = mock(LocalProviderGuard.class);
        AiGeneratorProperties properties = new AiGeneratorProperties();

        new AiGeneratorStartupChecks(guard, new StaticRuleInteractionChecker(), properties).check();
        verify(guard).check("medication-interactions", "rules", true);

        properties.setMissedDoseThreshold(-1);
        AiGeneratorStartupChecks broken = new AiGeneratorStartupChecks(guard, new StaticRuleInteractionChecker(),
                properties);
        IllegalStateException failure = assertThrows(IllegalStateException.class, broken::check);
        assertTrue(failure.getMessage().contains("missed-dose-threshold"), failure.getMessage());
    }

    @Test
    void productionRefusesTheLocalCheckerUnlessLocalProvidersAreAllowed() {
        EnvironmentInfo production = mock(EnvironmentInfo.class);
        when(production.isDevProfile()).thenReturn(false);
        when(production.isProduction()).thenReturn(true);
        when(production.environment()).thenReturn("production");
        AiGeneratorProperties properties = new AiGeneratorProperties();

        AiGeneratorStartupChecks refused = new AiGeneratorStartupChecks(new LocalProviderGuard(production, false),
                new StaticRuleInteractionChecker(), properties);
        IllegalStateException failure = assertThrows(IllegalStateException.class, refused::check);
        assertTrue(failure.getMessage().contains("medication-interactions"), failure.getMessage());

        new AiGeneratorStartupChecks(new LocalProviderGuard(production, true), new StaticRuleInteractionChecker(),
                properties).check();
    }
}
