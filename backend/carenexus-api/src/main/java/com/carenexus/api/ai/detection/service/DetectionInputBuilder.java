package com.carenexus.api.ai.detection.service;

import com.carenexus.api.ai.detection.repository.DetectionSourceDataRepository;
import com.carenexus.api.ai.detection.repository.DetectionSourceDataRepository.PatientProfileRow;
import com.carenexus.api.ai.provider.DetectionInput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the de-identified {@link DetectionInput} of one patient with read-only native queries: age band, sex and
 * chronic conditions from the profile, the non-deleted symptom logs of the last 14 clinic days (at most 10, newest
 * first), the latest reading per type of the last 7 clinic days and the names of the active, unexpired
 * prescriptions. No name, system id, date of birth, contact detail or free-text note is copied.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DetectionInputBuilder {

    static final int MAX_CHRONIC_CONDITIONS = 20;
    static final int MAX_TEXT_LENGTH = 100;
    private static final Set<String> SEXES = Set.of("MALE", "FEMALE", "OTHER");

    private final DetectionSourceDataRepository sourceData;
    private final ObjectMapper objectMapper;

    /**
     * @param patientSystemId the patient (used for the queries only; it is not part of the result)
     * @param today           the clinic date of the screening
     */
    public DetectionInput build(String patientSystemId, LocalDate today) {
        Optional<PatientProfileRow> profile = sourceData.findPatientProfile(patientSystemId, today);

        List<DetectionInput.SymptomEntry> symptoms = sourceData.findRecentSymptomLogs(patientSystemId,
                        today.minusDays(DetectionInput.SYMPTOM_WINDOW_DAYS - 1L).atStartOfDay(),
                        DetectionInput.MAX_SYMPTOM_LOGS).stream()
                .map(row -> new DetectionInput.SymptomEntry(parseDate(row.getLoggedOn()), row.getSeverity(),
                        textList(row.getSymptoms(), Integer.MAX_VALUE)))
                .toList();

        List<DetectionInput.Reading> readings = sourceData.findLatestReadings(patientSystemId,
                        today.minusDays(DetectionInput.READING_WINDOW_DAYS - 1L).atStartOfDay()).stream()
                .filter(row -> row.getType() != null && row.getValue() != null)
                .map(row -> new DetectionInput.Reading(row.getType(), row.getValue(), row.getUnit(),
                        parseDate(row.getRecordedOn())))
                .toList();

        List<String> medications = sourceData.findActiveMedicationNames(patientSystemId, today);

        return new DetectionInput(today,
                profile.map(PatientProfileRow::getAgeYears).map(DetectionInputBuilder::ageBand).orElse(null),
                profile.map(PatientProfileRow::getGender).map(DetectionInputBuilder::sex).orElse(null),
                profile.map(PatientProfileRow::getChronicConditions)
                        .map(json -> textList(json, MAX_CHRONIC_CONDITIONS)).orElse(List.of()),
                symptoms, readings, medications);
    }

    /** Decade bands from 18 ({@code "0-17"}, {@code "18-29"}, {@code "30-39"}, ..., {@code "80+"}). */
    static String ageBand(Integer years) {
        if (years == null || years < 0) {
            return null;
        }
        if (years < 18) {
            return "0-17";
        }
        if (years < 30) {
            return "18-29";
        }
        if (years >= 80) {
            return "80+";
        }
        int start = years / 10 * 10;
        return start + "-" + (start + 9);
    }

    private static String sex(String gender) {
        if (gender == null) {
            return null;
        }
        String normalized = gender.trim().toUpperCase(Locale.ROOT);
        return SEXES.contains(normalized) ? normalized : null;
    }

    /**
     * Strings of a JSON array: plain strings, or the {@code name} / {@code condition} field of objects. Anything
     * else (a malformed or differently shaped value) yields an empty list instead of failing the screening.
     */
    List<String> textList(String json, int max) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isArray()) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            for (JsonNode item : node) {
                if (values.size() >= max) {
                    break;
                }
                String text = null;
                if (item.isTextual()) {
                    text = item.asText();
                } else if (item.isObject()) {
                    JsonNode name = item.hasNonNull("name") ? item.get("name") : item.get("condition");
                    text = name != null && name.isTextual() ? name.asText() : null;
                }
                if (text != null && !text.isBlank()) {
                    String trimmed = text.trim();
                    values.add(trimmed.length() > MAX_TEXT_LENGTH ? trimmed.substring(0, MAX_TEXT_LENGTH) : trimmed);
                }
            }
            return values;
        } catch (Exception e) {
            log.debug("Screening input: ignored a JSON value that is not a list ({})", e.getClass().getSimpleName());
            return List.of();
        }
    }

    private static LocalDate parseDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
