package com.carenexus.api.ai.alert.model;

import java.util.Locale;
import java.util.Optional;

/**
 * Alert types (database CHECK {@code ck_ai_alerts_alert_type}, V48). Each type has the title shown when a row has
 * no stored title, and says whether it is non-diagnostic: MISSED_MEDICATION and FOLLOW_UP_DUE are shown to the
 * patient and caregivers as they are; every other type reaches them only when its detection is CONFIRMED.
 */
public enum AiAlertType {

    CRITICAL_VITALS("Critical vitals", false),
    DISEASE_RISK("Possible health risk", false),
    MEDICATION_INTERACTION("Possible medication interaction", false),
    MISSED_MEDICATION("Missed medication doses", true),
    FOLLOW_UP_DUE("Follow-up due", true),
    ANOMALY_DETECTED("Unusual reading", false),
    CRITICAL_RESULT("Critical result", false);

    private final String defaultTitle;
    private final boolean nonDiagnostic;

    AiAlertType(String defaultTitle, boolean nonDiagnostic) {
        this.defaultTitle = defaultTitle;
        this.nonDiagnostic = nonDiagnostic;
    }

    public String defaultTitle() {
        return defaultTitle;
    }

    /** True for the types shown to the patient and caregivers without a confirmed detection. */
    public boolean nonDiagnostic() {
        return nonDiagnostic;
    }

    /** The type named by {@code value} (trimmed, any case), or empty for null, blank or an unknown name. */
    public static Optional<AiAlertType> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (AiAlertType type : values()) {
            if (type.name().equals(normalized)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    /** Default title of a stored type name; a generic heading for a value outside the enum. */
    public static String defaultTitleOf(String storedType) {
        return parse(storedType).map(AiAlertType::defaultTitle).orElse("Clinical alert");
    }
}
