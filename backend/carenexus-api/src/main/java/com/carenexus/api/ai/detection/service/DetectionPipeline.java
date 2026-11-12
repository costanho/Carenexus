package com.carenexus.api.ai.detection.service;

import com.carenexus.api.ai.alert.AiAlertCommand;
import com.carenexus.api.ai.alert.AiAlertService;
import com.carenexus.api.ai.detection.model.DetectionRunOutcome;
import com.carenexus.api.ai.detection.model.DetectionSourceType;
import com.carenexus.api.ai.detection.model.DiseaseDetection;
import com.carenexus.api.ai.detection.repository.DetectionSourceDataRepository;
import com.carenexus.api.ai.detection.repository.DiseaseDetectionRepository;
import com.carenexus.api.ai.provider.DetectionInput;
import com.carenexus.api.ai.provider.DetectionResult;
import com.carenexus.api.ai.provider.DetectionResult.DetectedCondition;
import com.carenexus.api.ai.provider.RiskLevel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The screening pipeline shared by the automatic triggers and a doctor's manual run (api/be-ai-detection.md
 * section 8):
 * <ol>
 *   <li>screening switched off: {@link RiskAssessmentException} DISABLED;</li>
 *   <li>the de-identified {@link DetectionInput} is built with read-only native queries; without a recent reading or
 *       symptom log the outcome is NO_DATA and the provider is not called;</li>
 *   <li>the provider runs with the assessment timeout ({@link RiskAssessmentRunner}); a failure or timeout is a
 *       {@link RiskAssessmentException} and nothing is written;</li>
 *   <li>the result is normalised (ordering, one entry per code, length limits); an overall level below MEDIUM is
 *       NO_FINDING;</li>
 *   <li>one PENDING_REVIEW row is inserted with {@code ON CONFLICT (dedupe_key) DO NOTHING}, the key being
 *       {@code DISEASE_RISK:<patient>:<primary condition code>:<clinic date>}. A repeat writes nothing and answers
 *       DETECTION_EXISTS with the stored row;</li>
 *   <li>a new row raises exactly one DISEASE_RISK alert through {@link AiAlertService#raise}, in the same
 *       transaction and with the same dedupe key, addressed to every related doctor.</li>
 * </ol>
 * A doctor's manual run does all of this inside the request's transaction. An automatic screening (worker thread of
 * {@link ScreeningDispatcher}) uses two short transactions instead: one reads the input, the provider then runs with
 * no transaction and no database connection held, and one inserts the detection and raises the alert.
 * Log lines carry ids, outcomes and exception classes only, never clinical values.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DetectionPipeline {

    public static final String ALERT_TYPE = "DISEASE_RISK";
    static final String DEDUPE_PREFIX = "DISEASE_RISK:";
    static final String ALERT_MESSAGE_PREFIX = "Rule-based screening of recent symptoms and readings flagged: ";
    static final String ALERT_SUGGESTED_ACTION = "Review the detection and confirm or reject it.";

    static final int MAX_CONDITIONS = 10;
    static final int MAX_CODE_LENGTH = 100;
    static final int MAX_NAME_LENGTH = 120;
    static final int MAX_EVIDENCE = 5;
    static final int MAX_EVIDENCE_LENGTH = 200;
    static final int MAX_RECOMMENDATIONS = 10;
    static final int MAX_RECOMMENDATION_LENGTH = 300;

    /** Highest level first, then highest confidence, then code ascending. */
    static final Comparator<DetectedCondition> ORDER = Comparator
            .comparing(DetectedCondition::riskLevel, Comparator.reverseOrder())
            .thenComparing(DetectedCondition::confidence, Comparator.reverseOrder())
            .thenComparing(DetectedCondition::code);

    private final DetectionInputBuilder inputBuilder;
    private final RiskAssessmentRunner runner;
    private final DiseaseDetectionRepository detectionRepository;
    private final DetectionSourceDataRepository sourceData;
    private final AiAlertService aiAlertService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final PlatformTransactionManager transactionManager;

    /**
     * What a screening produced.
     *
     * @param outcome      the outcome of 2.4
     * @param detection    the new (DETECTION_CREATED) or stored (DETECTION_EXISTS) detection, otherwise null
     * @param modelVersion version of the provider that ran
     * @param assessedAt   when the run finished
     */
    public record ScreeningResult(DetectionRunOutcome outcome, DiseaseDetection detection, String modelVersion,
                                  Instant assessedAt) {
    }

    /**
     * Screens one patient inside the caller's transaction (a doctor's manual run). The caller has authorized the
     * request.
     *
     * @throws RiskAssessmentException when screening is switched off, failed or timed out (nothing is written)
     */
    @Transactional
    public ScreeningResult screen(String patientSystemId, ScreeningTrigger trigger) {
        return run(patientSystemId, trigger);
    }

    /**
     * Automatic screening after a symptom log or an abnormal reading committed. Skipped (empty) when screening is
     * switched off or the patient does not exist. Not transactional itself: the input is read in one short
     * transaction, the provider runs with no transaction (so no pooled connection waits for it, up to the assessment
     * timeout), and the detection and its alert are written in a second short transaction. An exception rolls back
     * only that write; the listener logs it and the origin is unaffected.
     */
    public Optional<ScreeningResult> screenAutomatically(String patientSystemId, ScreeningTrigger trigger) {
        if (trigger.isManual()) {
            throw new IllegalArgumentException("An automatic screening needs its source");
        }
        if (!runner.isEnabled()) {
            log.debug("Risk screening switched off: {} {} not screened", trigger.sourceType(), trigger.sourceId());
            return Optional.empty();
        }
        TransactionTemplate read = new TransactionTemplate(transactionManager);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setReadOnly(true);
        Snapshot snapshot = read.execute(status -> patientSystemId != null && sourceData.patientExists(patientSystemId)
                ? snapshot(patientSystemId) : null);
        if (snapshot == null) {
            log.debug("Risk screening skipped: no patient for {} {}", trigger.sourceType(), trigger.sourceId());
            return Optional.empty();
        }
        if (!snapshot.input().hasData()) {
            return Optional.of(noData(snapshot));
        }

        Finding finding = normalize(runner.assess(snapshot.input()));
        if (finding == null) {
            log.debug("Risk screening of {}: no finding", patientSystemId);
            return Optional.of(noFinding(snapshot));
        }

        TransactionTemplate write = new TransactionTemplate(transactionManager);
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return Optional.ofNullable(write.execute(status -> record(patientSystemId, trigger, snapshot, finding)));
    }

    // ==================== pipeline ====================

    /** What the provider is asked about, read at one moment. */
    private record Snapshot(String modelVersion, LocalDateTime now, DetectionInput input) {
    }

    private Snapshot snapshot(String patientSystemId) {
        LocalDateTime now = LocalDateTime.now(clock);
        return new Snapshot(runner.modelVersion(), now, inputBuilder.build(patientSystemId, now.toLocalDate()));
    }

    private ScreeningResult noData(Snapshot snapshot) {
        return new ScreeningResult(DetectionRunOutcome.NO_DATA, null, snapshot.modelVersion(), clock.instant());
    }

    private ScreeningResult noFinding(Snapshot snapshot) {
        return new ScreeningResult(DetectionRunOutcome.NO_FINDING, null, snapshot.modelVersion(), clock.instant());
    }

    private ScreeningResult run(String patientSystemId, ScreeningTrigger trigger) {
        if (!runner.isEnabled()) {
            throw new RiskAssessmentException(RiskAssessmentException.Reason.DISABLED, "Risk screening is switched off");
        }
        Snapshot snapshot = snapshot(patientSystemId);
        if (!snapshot.input().hasData()) {
            return noData(snapshot);
        }

        Finding finding = normalize(runner.assess(snapshot.input()));
        if (finding == null) {
            log.debug("Risk screening of {}: no finding", patientSystemId);
            return noFinding(snapshot);
        }
        return record(patientSystemId, trigger, snapshot, finding);
    }

    /** Inserts the detection (once per dedupe key) and raises its alert. Runs inside a transaction. */
    private ScreeningResult record(String patientSystemId, ScreeningTrigger trigger, Snapshot snapshot,
                                   Finding finding) {
        String modelVersion = snapshot.modelVersion();
        LocalDateTime now = snapshot.now();
        DetectionInput input = snapshot.input();
        DetectedCondition primary = finding.conditions().get(0);
        String dedupeKey = dedupeKey(patientSystemId, primary.code(), now.toLocalDate());
        Integer symptomId = trigger.sourceType() == DetectionSourceType.SYMPTOM_LOG
                && sourceData.symptomLogExists(trigger.sourceId()) ? trigger.sourceId() : null;

        List<Integer> inserted = detectionRepository.insertIfAbsent(
                patientSystemId,
                symptomId,
                trigger.requestedBySystemId(),
                modelVersion,
                toJson(conditionsJson(finding.conditions())),
                primary.confidence(),
                finding.riskLevel().name(),
                toJson(input),
                toJson(finding.recommendations()),
                now,
                trigger.sourceType() != null ? trigger.sourceType().name() : null,
                trigger.sourceId(),
                dedupeKey);

        if (inserted.isEmpty()) {
            DiseaseDetection existing = detectionRepository.findByDedupeKey(dedupeKey)
                    .orElseThrow(() -> new IllegalStateException("Detection dedupe key conflict without a stored row"));
            log.debug("Risk screening of {}: detection {} already recorded today", patientSystemId,
                    existing.getDetectionId());
            return new ScreeningResult(DetectionRunOutcome.DETECTION_EXISTS, existing, modelVersion, clock.instant());
        }

        Integer detectionId = inserted.get(0);
        String names = finding.conditions().stream().map(DetectedCondition::name).collect(Collectors.joining(", "));
        aiAlertService.raise(new AiAlertCommand(
                patientSystemId,
                null,
                ALERT_TYPE,
                finding.riskLevel().name(),
                null,
                ALERT_MESSAGE_PREFIX + names + ".",
                true,
                ALERT_SUGGESTED_ACTION,
                trigger.sourceType() != null ? trigger.sourceType().name() : null,
                trigger.sourceId(),
                detectionId,
                dedupeKey));
        log.info("Risk screening of {}: detection {} stored ({}, model {})", patientSystemId, detectionId,
                finding.riskLevel(), modelVersion);

        DiseaseDetection stored = detectionRepository.findById(detectionId)
                .orElseThrow(() -> new IllegalStateException("Stored detection " + detectionId + " not found"));
        return new ScreeningResult(DetectionRunOutcome.DETECTION_CREATED, stored, modelVersion, clock.instant());
    }

    /** {@code DISEASE_RISK:<patient>:<primary condition code>:<yyyy-MM-dd>}, also the alert's dedupe key. */
    static String dedupeKey(String patientSystemId, String primaryCode, LocalDate clinicDate) {
        return DEDUPE_PREFIX + patientSystemId + ":" + primaryCode + ":" + clinicDate;
    }

    // ==================== normalisation ====================

    /** A provider result reduced to what may be stored. */
    record Finding(RiskLevel riskLevel, List<DetectedCondition> conditions, List<String> recommendations) {
    }

    /**
     * Applies the storage rules to a provider result: invalid entries are dropped, codes are unique (the highest
     * entry wins), names, evidence and recommendations are trimmed and limited, confidences are rounded to 3
     * decimals and the conditions are ordered (level, confidence, code). The overall level is the highest condition
     * level.
     *
     * @return null when nothing reaches MEDIUM
     * @throws RiskAssessmentException FAILED when the provider reported a risk of MEDIUM or higher but no usable
     *                                 condition (an inconsistent result is never stored)
     */
    static Finding normalize(DetectionResult result) {
        if (result == null) {
            throw new RiskAssessmentException(RiskAssessmentException.Reason.FAILED, "Risk provider returned no result");
        }
        List<DetectedCondition> valid = new ArrayList<>();
        for (DetectedCondition condition : result.conditions()) {
            DetectedCondition cleaned = clean(condition);
            if (cleaned != null) {
                valid.add(cleaned);
            }
        }
        valid.sort(ORDER);
        Map<String, DetectedCondition> byCode = new LinkedHashMap<>();
        for (DetectedCondition condition : valid) {
            byCode.putIfAbsent(condition.code(), condition);
        }
        List<DetectedCondition> conditions = byCode.values().stream().limit(MAX_CONDITIONS).toList();

        if (conditions.isEmpty()) {
            if (result.riskLevel() != null && result.riskLevel().isAtLeast(RiskLevel.MEDIUM)) {
                throw new RiskAssessmentException(RiskAssessmentException.Reason.FAILED,
                        "Risk provider reported a risk without a usable condition");
            }
            return null;
        }
        RiskLevel overall = conditions.get(0).riskLevel();
        if (!overall.isAtLeast(RiskLevel.MEDIUM)) {
            return null;
        }
        List<String> recommendations = result.recommendations().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(text -> !text.isEmpty())
                .map(text -> cut(text, MAX_RECOMMENDATION_LENGTH))
                .limit(MAX_RECOMMENDATIONS)
                .toList();
        return new Finding(overall, conditions, recommendations);
    }

    private static DetectedCondition clean(DetectedCondition condition) {
        if (condition == null || condition.riskLevel() == null || condition.confidence() == null) {
            return null;
        }
        String code = condition.code() == null ? "" : condition.code().trim();
        String name = condition.name() == null ? "" : condition.name().trim();
        if (code.isEmpty() || code.length() > MAX_CODE_LENGTH || name.isEmpty()) {
            return null;
        }
        BigDecimal confidence = condition.confidence();
        if (confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
            return null;
        }
        List<String> evidence = condition.evidence().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(text -> !text.isEmpty())
                .map(text -> cut(text, MAX_EVIDENCE_LENGTH))
                .limit(MAX_EVIDENCE)
                .toList();
        return new DetectedCondition(code, cut(name, MAX_NAME_LENGTH), condition.riskLevel(),
                confidence.setScale(3, RoundingMode.HALF_UP), evidence);
    }

    private static String cut(String value, int max) {
        if (value.codePointCount(0, value.length()) <= max) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, max)).trim();
    }

    // ==================== JSON ====================

    /** The stored shape of {@code detected_conditions}: {code, name, riskLevel, confidence, evidence}. */
    private static List<Map<String, Object>> conditionsJson(List<DetectedCondition> conditions) {
        List<Map<String, Object>> json = new ArrayList<>();
        for (DetectedCondition condition : conditions) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", condition.code());
            entry.put("name", condition.name());
            entry.put("riskLevel", condition.riskLevel().name());
            entry.put("confidence", condition.confidence());
            entry.put("evidence", condition.evidence());
            json.add(entry);
        }
        return json;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Screening data could not be serialised (" + e.getClass().getSimpleName() + ")");
        }
    }
}
