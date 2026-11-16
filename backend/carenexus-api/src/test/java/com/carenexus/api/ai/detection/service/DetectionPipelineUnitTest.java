package com.carenexus.api.ai.detection.service;

import com.carenexus.api.ai.detection.config.AiDetectionProperties;
import com.carenexus.api.ai.provider.DetectionInput;
import com.carenexus.api.ai.provider.DetectionResult;
import com.carenexus.api.ai.provider.DetectionResult.DetectedCondition;
import com.carenexus.api.ai.provider.DisabledRiskDetectionProvider;
import com.carenexus.api.ai.provider.RiskDetectionProvider;
import com.carenexus.api.ai.provider.RiskLevel;
import com.carenexus.api.ai.provider.RuleBasedRiskDetectionProvider;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pieces of the pipeline that need no database: the normalisation of provider results (what may be stored), the
 * timeout and failure handling of {@link RiskAssessmentRunner}, the dedupe key, the age bands and the trigger rules.
 */
class DetectionPipelineUnitTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    private static DetectionInput emptyInput() {
        return new DetectionInput(TODAY, null, null, List.of(), List.of(), List.of(), List.of());
    }

    private static DetectedCondition condition(String code, RiskLevel level, String confidence, String... evidence) {
        return new DetectedCondition(code, "Finding " + code, level, new BigDecimal(confidence), List.of(evidence));
    }

    private static RiskAssessmentRunner runner(RiskDetectionProvider provider, long timeoutMs) {
        AiDetectionProperties properties = new AiDetectionProperties();
        properties.setAssessmentTimeoutMs(timeoutMs);
        return new RiskAssessmentRunner(provider, properties);
    }

    private static RiskDetectionProvider provider(java.util.function.Function<DetectionInput, DetectionResult> body) {
        return new RiskDetectionProvider() {
            @Override
            public String modelVersion() {
                return "test-v1";
            }

            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public DetectionResult assess(DetectionInput input) {
                return body.apply(input);
            }
        };
    }

    // ==================== normalisation ====================

    @Test
    void normalisationOrdersDedupesAndLimits() {
        List<DetectedCondition> conditions = new ArrayList<>(List.of(
                condition("B_CODE", RiskLevel.MEDIUM, "0.6", "b"),
                condition("A_CODE", RiskLevel.MEDIUM, "0.6", "a"),
                condition("C_CODE", RiskLevel.HIGH, "0.5", "c"),
                condition("B_CODE", RiskLevel.HIGH, "0.9", "b high"),
                condition("LOW_CODE", RiskLevel.LOW, "0.99", "low")));
        for (int i = 0; i < 12; i++) {
            conditions.add(condition("EXTRA_" + (10 + i), RiskLevel.LOW, "0.1", "x"));
        }
        DetectionResult result = new DetectionResult(RiskLevel.LOW, null, conditions, List.of(" first ", "", "second"));

        DetectionPipeline.Finding finding = DetectionPipeline.normalize(result);

        assertEquals(RiskLevel.HIGH, finding.riskLevel(), "the overall level is the highest condition level");
        List<String> codes = finding.conditions().stream().map(DetectedCondition::code).toList();
        assertEquals(10, codes.size());
        assertEquals(List.of("B_CODE", "C_CODE", "A_CODE", "LOW_CODE"), codes.subList(0, 4));
        assertEquals(List.of("b high"), finding.conditions().get(0).evidence());
        assertEquals(new BigDecimal("0.900"), finding.conditions().get(0).confidence());
        assertEquals(List.of("first", "second"), finding.recommendations());
    }

    @Test
    void invalidConditionsAreDroppedAndTextIsCut() {
        DetectionResult result = new DetectionResult(RiskLevel.MEDIUM, null, List.of(
                condition(" ", RiskLevel.HIGH, "0.5", "blank code"),
                condition("X".repeat(101), RiskLevel.HIGH, "0.5", "long code"),
                condition("NEGATIVE", RiskLevel.HIGH, "-0.1", "e"),
                condition("TOO_SURE", RiskLevel.HIGH, "1.2", "e"),
                new DetectedCondition("NO_LEVEL", "n", null, BigDecimal.ONE, List.of("e")),
                new DetectedCondition("GOOD", "N".repeat(150), RiskLevel.MEDIUM, new BigDecimal("0.12345"),
                        List.of("e1", " ", "y".repeat(250), "e3", "e4", "e5", "e6"))),
                List.of("r".repeat(400)));

        DetectionPipeline.Finding finding = DetectionPipeline.normalize(result);

        assertEquals(1, finding.conditions().size());
        DetectedCondition good = finding.conditions().get(0);
        assertEquals("GOOD", good.code());
        assertEquals(120, good.name().length());
        assertEquals(new BigDecimal("0.123"), good.confidence());
        assertEquals(5, good.evidence().size());
        assertEquals(200, good.evidence().get(1).length());
        assertEquals(300, finding.recommendations().get(0).length());
    }

    @Test
    void nothingAtMediumIsNoFinding() {
        assertNull(DetectionPipeline.normalize(DetectionResult.noFinding()));
        assertNull(DetectionPipeline.normalize(new DetectionResult(RiskLevel.LOW, new BigDecimal("0.3"),
                List.of(condition("MILD_THING", RiskLevel.LOW, "0.3", "e")), List.of("r"))));
    }

    @Test
    void aRiskWithoutUsableConditionsIsAFailure() {
        RiskAssessmentException e = assertThrows(RiskAssessmentException.class, () -> DetectionPipeline.normalize(
                new DetectionResult(RiskLevel.HIGH, new BigDecimal("0.8"), List.of(), List.of())));
        assertEquals(RiskAssessmentException.Reason.FAILED, e.getReason());
        assertEquals(RiskAssessmentException.Reason.FAILED,
                assertThrows(RiskAssessmentException.class, () -> DetectionPipeline.normalize(null)).getReason());
    }

    @Test
    void dedupeKeyNamesPatientPrimaryConditionAndClinicDay() {
        assertEquals("DISEASE_RISK:PAT-00004:FEVER_RESPIRATORY:2026-11-10",
                DetectionPipeline.dedupeKey("PAT-00004", "FEVER_RESPIRATORY", TODAY));
    }

    // ==================== runner ====================

    @Test
    void runnerReturnsTheProviderResult() {
        RiskAssessmentRunner runner = runner(new RuleBasedRiskDetectionProvider(), 3000);
        try {
            assertTrue(runner.isEnabled());
            assertEquals("rules-v1", runner.modelVersion());
            DetectionResult result = runner.assess(emptyInput());
            assertEquals(RiskLevel.LOW, result.riskLevel());
        } finally {
            runner.shutdown();
        }
    }

    @Test
    void runnerRefusesWhenSwitchedOff() {
        RiskAssessmentRunner runner = runner(new DisabledRiskDetectionProvider(), 3000);
        try {
            assertFalse(runner.isEnabled());
            assertEquals(RiskAssessmentException.Reason.DISABLED,
                    assertThrows(RiskAssessmentException.class, () -> runner.assess(emptyInput())).getReason());
        } finally {
            runner.shutdown();
        }
    }

    @Test
    void runnerTurnsProviderErrorsAndEmptyResultsIntoFailures() {
        RiskAssessmentRunner failing = runner(provider(input -> {
            throw new IllegalStateException("down");
        }), 3000);
        RiskAssessmentRunner empty = runner(provider(input -> null), 3000);
        try {
            RiskAssessmentException e = assertThrows(RiskAssessmentException.class, () -> failing.assess(emptyInput()));
            assertEquals(RiskAssessmentException.Reason.FAILED, e.getReason());
            assertFalse(e.getMessage().contains("down"), "the provider's message is not repeated");
            assertEquals(RiskAssessmentException.Reason.FAILED,
                    assertThrows(RiskAssessmentException.class, () -> empty.assess(emptyInput())).getReason());
        } finally {
            failing.shutdown();
            empty.shutdown();
        }
    }

    @Test
    void runnerStopsWaitingAfterTheTimeoutAndInterruptsTheProvider() throws Exception {
        java.util.concurrent.CountDownLatch interrupted = new java.util.concurrent.CountDownLatch(1);
        RiskAssessmentRunner runner = runner(provider(input -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
            return DetectionResult.noFinding();
        }), 100);
        try {
            long start = System.nanoTime();
            RiskAssessmentException e = assertThrows(RiskAssessmentException.class, () -> runner.assess(emptyInput()));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertEquals(RiskAssessmentException.Reason.TIMEOUT, e.getReason());
            assertTrue(elapsedMs < 2_000, "waited " + elapsedMs + " ms");
            assertTrue(interrupted.await(2, java.util.concurrent.TimeUnit.SECONDS), "the provider thread was interrupted");
        } finally {
            runner.shutdown();
        }
    }

    @Test
    void timeoutIsClampedToASaneRange() {
        AiDetectionProperties properties = new AiDetectionProperties();
        assertEquals(3000, properties.effectiveTimeoutMs());
        properties.setAssessmentTimeoutMs(1);
        assertEquals(50, properties.effectiveTimeoutMs());
        properties.setAssessmentTimeoutMs(10_000_000);
        assertEquals(60_000, properties.effectiveTimeoutMs());
        properties.setProvider("  Disabled ");
        assertEquals("disabled", properties.providerId());
        properties.setProvider(" ");
        assertEquals("rules", properties.providerId());
    }

    // ==================== input and trigger ====================

    @Test
    void ageBandsAreCoarse() {
        assertNull(DetectionInputBuilder.ageBand(null));
        assertNull(DetectionInputBuilder.ageBand(-1));
        assertEquals("0-17", DetectionInputBuilder.ageBand(0));
        assertEquals("0-17", DetectionInputBuilder.ageBand(17));
        assertEquals("18-29", DetectionInputBuilder.ageBand(18));
        assertEquals("18-29", DetectionInputBuilder.ageBand(29));
        assertEquals("30-39", DetectionInputBuilder.ageBand(30));
        assertEquals("70-79", DetectionInputBuilder.ageBand(79));
        assertEquals("80+", DetectionInputBuilder.ageBand(80));
        assertEquals("80+", DetectionInputBuilder.ageBand(104));
    }

    @Test
    void triggerIsEitherASourceOrARequestingDoctor() {
        assertTrue(ScreeningTrigger.manual("DOC-00001").isManual());
        assertFalse(ScreeningTrigger.symptomLog(5).isManual());
        assertThrows(IllegalArgumentException.class, () -> new ScreeningTrigger(null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ScreeningTrigger(
                com.carenexus.api.ai.detection.model.DetectionSourceType.HEALTH_METRIC, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ScreeningTrigger(
                com.carenexus.api.ai.detection.model.DetectionSourceType.HEALTH_METRIC, 4, "DOC-00001"));
    }
}
