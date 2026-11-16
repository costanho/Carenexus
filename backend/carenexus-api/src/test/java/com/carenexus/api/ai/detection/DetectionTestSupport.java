package com.carenexus.api.ai.detection;

import com.carenexus.api.ai.provider.DetectionInput;
import com.carenexus.api.ai.provider.DetectionResult;
import com.carenexus.api.ai.provider.RiskDetectionProvider;
import com.carenexus.api.ai.provider.RuleBasedRiskDetectionProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Test support of the detection tests. Import it with {@code @Import(DetectionTestSupport.class)}; the importing
 * classes share one application context.
 * <p>
 * It replaces the risk provider with a {@link SwitchableRiskDetectionProvider} that behaves exactly like
 * {@code rules-v1} until a test switches it to off, failing or hanging (and back in {@code @AfterEach}). It also has
 * row fixtures written with plain SQL, so they publish no event and start no automatic screening.
 */
@TestConfiguration
public class DetectionTestSupport {

    @Bean
    @Primary
    public SwitchableRiskDetectionProvider switchableRiskDetectionProvider() {
        return new SwitchableRiskDetectionProvider();
    }

    /** A provider whose behaviour a test can switch; counts the assessments it was asked for. */
    public static class SwitchableRiskDetectionProvider implements RiskDetectionProvider {

        public enum Mode { RULES, DISABLED, FAIL, HANG }

        private final RuleBasedRiskDetectionProvider rules = new RuleBasedRiskDetectionProvider();
        private final AtomicInteger calls = new AtomicInteger();
        private volatile Mode mode = Mode.RULES;
        private volatile DetectionInput lastInput;

        public void set(Mode newMode) {
            this.mode = newMode;
        }

        public void reset() {
            mode = Mode.RULES;
            calls.set(0);
            lastInput = null;
        }

        public int calls() {
            return calls.get();
        }

        public DetectionInput lastInput() {
            return lastInput;
        }

        @Override
        public String modelVersion() {
            return rules.modelVersion();
        }

        @Override
        public boolean isConfigured() {
            return mode != Mode.DISABLED;
        }

        @Override
        public DetectionResult assess(DetectionInput input) {
            calls.incrementAndGet();
            lastInput = input;
            switch (mode) {
                case FAIL -> throw new IllegalStateException("simulated provider outage");
                case HANG -> {
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    throw new IllegalStateException("simulated provider hang");
                }
                case DISABLED -> throw new IllegalStateException("switched off");
                default -> {
                    return rules.assess(input);
                }
            }
        }
    }

    // ==================== row fixtures (plain SQL, no events) ====================

    /** One reading of a patient; abnormal when {@code severity} is not NORMAL. */
    public static int insertReading(JdbcTemplate jdbc, String patientSystemId, String type, String value, String unit,
                                    LocalDateTime recordedAt) {
        String severity = "NORMAL";
        return jdbc.queryForObject("INSERT INTO health_metrics (patient_system_id, recorded_by_system_id, type, value, "
                        + "unit, source, is_abnormal, severity, recorded_at) VALUES (?, ?, ?, ?, ?, 'MANUAL', FALSE, ?, ?) "
                        + "RETURNING metric_id",
                Integer.class, patientSystemId, patientSystemId, type, new BigDecimal(value), unit, severity, recordedAt);
    }

    /** One symptom log of a patient. */
    public static int insertSymptomLog(JdbcTemplate jdbc, String patientSystemId, String severity,
                                       LocalDateTime loggedAt, String... symptoms) {
        String json = List.of(symptoms).stream().map(s -> "\"" + s.replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(",", "[", "]"));
        return jdbc.queryForObject("INSERT INTO symptom_logs (patient_system_id, logged_by_system_id, symptoms, severity, "
                        + "source, logged_at) VALUES (?, ?, CAST(? AS JSONB), ?, 'PATIENT_REPORTED', ?) RETURNING symptom_id",
                Integer.class, patientSystemId, patientSystemId, json, severity, loggedAt);
    }

    /**
     * A stored detection as the pipeline writes it (one MEDIUM blood-pressure finding), without an alert.
     *
     * @param dedupeKey unique per row; null allowed
     */
    public static int insertDetection(JdbcTemplate jdbc, String patientSystemId, String reviewStatus,
                                      LocalDateTime createdAt, String dedupeKey) {
        String conditions = "[{\"code\":\"HYPERTENSIVE_BP\",\"name\":\"Blood pressure in the hypertensive range\","
                + "\"riskLevel\":\"MEDIUM\",\"confidence\":0.700,"
                + "\"evidence\":[\"Blood pressure 148/92 mmHg recorded on 2026-11-10\"]}]";
        String recommendations = "[\"Repeat the blood pressure measurement and review blood pressure management.\"]";
        return jdbc.queryForObject("INSERT INTO disease_detection (patient_system_id, model_version, detected_conditions, "
                        + "confidence_score, risk_level, input_data, recommendations, is_confirmed, created_at, review_status, "
                        + "dedupe_key) VALUES (?, 'rules-v1', CAST(? AS JSONB), 0.700, 'MEDIUM', '{}'::jsonb, "
                        + "CAST(? AS JSONB), ?, ?, ?, ?) RETURNING detection_id",
                Integer.class, patientSystemId, conditions, recommendations, "CONFIRMED".equals(reviewStatus), createdAt,
                reviewStatus, dedupeKey);
    }
}
