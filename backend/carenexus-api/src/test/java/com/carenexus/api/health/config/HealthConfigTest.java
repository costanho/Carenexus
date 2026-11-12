package com.carenexus.api.health.config;

import com.carenexus.api.health.provider.DisabledWearableConnector;
import com.carenexus.api.health.provider.WearableConnector;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wearable connector selection ({@code carenexus.health.wearables.provider}) and the startup consistency check of
 * the configured reference ranges.
 */
class HealthConfigTest {

    private final HealthConfig config = new HealthConfig();

    @Test
    void defaultProviderIsTheDisabledConnector_whichNeverReturnsReadings() {
        WearableConnector connector = config.wearableConnector(new HealthProperties());

        assertInstanceOf(DisabledWearableConnector.class, connector);
        assertEquals("disabled", connector.providerName());
        assertFalse(connector.isEnabled());
        assertEquals(List.of(), connector.fetchReadings("PAT-00001", LocalDateTime.now().minusDays(1)));
    }

    @Test
    void blankOrMixedCaseDisabledProviderIsAccepted() {
        HealthProperties properties = new HealthProperties();
        properties.getWearables().setProvider("  ");
        assertInstanceOf(DisabledWearableConnector.class, config.wearableConnector(properties));
        properties.getWearables().setProvider(" Disabled ");
        assertInstanceOf(DisabledWearableConnector.class, config.wearableConnector(properties));
    }

    @Test
    void unknownProviderFailsStartup() {
        HealthProperties properties = new HealthProperties();
        properties.getWearables().setProvider("fitbit");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> config.wearableConnector(properties));
        assertTrue(failure.getMessage().contains("fitbit"));
    }

    @Test
    void defaultRangesAreConsistent() {
        assertEquals(List.of(), HealthStartupChecks.rangeProblems(new HealthProperties().getRanges()));
    }

    @Test
    void inconsistentOverridesAreReported() {
        HealthProperties.Ranges ranges = new HealthProperties().getRanges();
        ranges.setHeartRate(new HealthProperties.Range(
                new BigDecimal("70"), new BigDecimal("60"), new BigDecimal("100"), new BigDecimal("130")));
        ranges.setGlucose(new HealthProperties.Range(null, new BigDecimal("8"), new BigDecimal("4"), null));
        ranges.setTemperature(new HealthProperties.Range(null, null, new BigDecimal("38"), new BigDecimal("37")));

        List<String> problems = HealthStartupChecks.rangeProblems(ranges);

        assertTrue(problems.contains("HEART_RATE: critical-low must not be above low"), problems.toString());
        assertTrue(problems.contains("GLUCOSE: low must be below high"), problems.toString());
        assertTrue(problems.contains("TEMPERATURE: high must not be above critical-high"), problems.toString());
        assertEquals(3, problems.size(), problems.toString());
    }
}
