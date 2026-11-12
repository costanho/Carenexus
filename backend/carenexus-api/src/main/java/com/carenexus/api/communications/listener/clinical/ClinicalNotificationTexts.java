package com.carenexus.api.communications.listener.clinical;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The exact titles and messages of the clinical notifications (api/be-clinical-notifications.md section 3.2).
 *
 * The text is deliberately generic: it never contains a result value, diagnosis, medication name or dose, metric
 * type or value, document title, appointment type or a user-typed reason. Times are clinic wall-clock only.
 * A message is never built with the text "null": a missing name falls back to "the patient", "the doctor" or
 * "The clinic", and the first letter of every message is upper case.
 */
public final class ClinicalNotificationTexts {

    // ==================== TITLES ====================

    public static final String TITLE_APPOINTMENT_BOOKED = "Appointment booked";
    public static final String TITLE_APPOINTMENT_RESCHEDULED = "Appointment rescheduled";
    public static final String TITLE_APPOINTMENT_CANCELLED = "Appointment cancelled";
    public static final String TITLE_APPOINTMENT_NO_SHOW = "Appointment marked as missed";
    public static final String TITLE_CONSULTATION_COMPLETED = "Consultation summary available";
    public static final String TITLE_PRESCRIPTION_ISSUED = "New prescription";
    public static final String TITLE_PRESCRIPTION_EXPIRED = "Prescription expired";
    public static final String TITLE_PRESCRIPTION_CANCELLED = "Prescription cancelled";
    public static final String TITLE_LAB_AVAILABLE = "New lab result";
    public static final String TITLE_IMAGING_AVAILABLE = "New imaging result";
    public static final String TITLE_LAB_CRITICAL = "Important lab result";
    public static final String TITLE_IMAGING_CRITICAL = "Important imaging result";
    public static final String TITLE_DOCUMENT_UPLOADED = "New document";
    public static final String TITLE_DOSES_MISSED = "Missed medication doses";
    public static final String TITLE_VITALS_ABNORMAL = "Abnormal health reading";
    public static final String TITLE_VITALS_CRITICAL = "Critical health reading";

    // ==================== PLACEHOLDER FALLBACKS ====================

    public static final String FALLBACK_PATIENT = "the patient";
    public static final String FALLBACK_DOCTOR = "the doctor";
    public static final String FALLBACK_UPLOADER = "The clinic";

    /** Longest system cancellation reason shown (characters, after whitespace was collapsed). */
    public static final int MAX_REASON_LENGTH = 200;

    /** {@code {when}}: clinic wall-clock, for example {@code 12 Nov 2026 at 09:00}. */
    public static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy 'at' HH:mm", Locale.ENGLISH);
    /** {@code {date}}: for example {@code 26 Nov 2026}. */
    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private ClinicalNotificationTexts() {
    }

    // ==================== APPOINTMENTS (N1 to N4) ====================

    /** N1. {@code when} null leaves the time out. */
    public static String appointmentBooked(Audience audience, String patient, String doctor, LocalDateTime when) {
        String on = on(when);
        return sentence(switch (audience) {
            case PATIENT -> "An appointment with " + doctor + " was booked for you" + on;
            case CAREGIVER -> "An appointment with " + doctor + " was booked for " + patient + on;
            case DOCTOR -> patient + " has a new appointment with you" + on;
        });
    }

    /** N2: {@code when} is the new time. */
    public static String appointmentRescheduled(Audience audience, String patient, String doctor, LocalDateTime when) {
        String to = when == null ? " was moved" : " was moved to " + WHEN.format(when);
        return sentence(switch (audience) {
            case PATIENT -> "Your appointment with " + doctor + to;
            case CAREGIVER -> patient + "'s appointment with " + doctor + to;
            case DOCTOR -> "Your appointment with " + patient + to;
        });
    }

    /**
     * N3. {@code systemReason} is appended only for a system cancellation (D4); a user-typed reason is never passed
     * here.
     */
    public static String appointmentCancelled(Audience audience, String patient, String doctor, LocalDateTime when,
                                              String systemReason) {
        String on = on(when);
        String text = switch (audience) {
            case PATIENT -> "Your appointment with " + doctor + on + " was cancelled";
            case CAREGIVER -> patient + "'s appointment with " + doctor + on + " was cancelled";
            case DOCTOR -> "Your appointment with " + patient + on + " was cancelled";
        };
        String reason = normalizeReason(systemReason);
        if (reason != null) {
            text = text + ". Reason: " + reason;
        }
        return sentence(text);
    }

    /** N4: the event carries no time, so the text has none. */
    public static String appointmentNoShow(Audience audience, String patient, String doctor) {
        return sentence(switch (audience) {
            case PATIENT -> "Your appointment with " + doctor + " was marked as missed";
            case CAREGIVER -> patient + "'s appointment with " + doctor + " was marked as missed";
            case DOCTOR -> "Your appointment with " + patient + " was marked as missed";
        });
    }

    // ==================== CONSULTATIONS AND PRESCRIPTIONS (N5 to N8) ====================

    /** N5 for the patient or a caregiver, with the follow-up sentence when one is recommended. */
    public static String consultationCompleted(Audience audience, String patient, String doctor,
                                               boolean followUpRequired, LocalDate followUpDate) {
        String text = audience == Audience.CAREGIVER
                ? doctor + " completed a consultation with " + patient + ". The summary is available."
                : doctor + " completed your consultation. The summary is available.";
        if (followUpRequired) {
            text = text + (followUpDate == null
                    ? " A follow-up visit is recommended."
                    : " A follow-up visit is recommended by " + DATE.format(followUpDate) + ".");
        }
        return sentence(text);
    }

    /** N6. */
    public static String prescriptionIssued(Audience audience, String patient, String doctor) {
        return sentence(audience == Audience.CAREGIVER
                ? doctor + " issued a new prescription for " + patient
                : doctor + " issued a new prescription for you");
    }

    /** N7 (patient only). */
    public static String prescriptionExpired() {
        return "One of your prescriptions has expired. Contact your doctor if you need a renewal.";
    }

    /** N8 (patient only). */
    public static String prescriptionCancelled(String doctor) {
        return sentence(doctor + " cancelled one of your prescriptions");
    }

    // ==================== RESULTS (N9, N10) ====================

    public static String resultAvailableTitle(boolean imaging) {
        return imaging ? TITLE_IMAGING_AVAILABLE : TITLE_LAB_AVAILABLE;
    }

    /** N9. */
    public static String resultAvailable(Audience audience, boolean imaging, String patient) {
        if (audience == Audience.CAREGIVER) {
            return sentence(imaging
                    ? "An imaging result for " + patient + " is available"
                    : "A lab result for " + patient + " is available");
        }
        return imaging
                ? "A new imaging result is available in your records"
                : "A new lab result is available in your records";
    }

    public static String resultCriticalTitle(boolean imaging) {
        return imaging ? TITLE_IMAGING_CRITICAL : TITLE_LAB_CRITICAL;
    }

    /** N10. */
    public static String resultCritical(Audience audience, boolean imaging, String patient) {
        if (audience == Audience.CAREGIVER) {
            return sentence(imaging
                    ? "An imaging result for " + patient + " needs attention. Please contact the care team."
                    : "A lab result for " + patient + " needs attention. Please contact the care team.");
        }
        return imaging
                ? "An imaging result needs your attention. Please contact your care team."
                : "A lab result needs your attention. Please contact your care team.";
    }

    // ==================== DOCUMENTS, DOSES, VITALS (N11 to N13) ====================

    /** N11 (patient only). */
    public static String documentUploaded(String uploader) {
        return sentence(uploader + " added a document to your records");
    }

    /** N12 (caregivers only). */
    public static String dosesMissed(String patient) {
        return sentence(patient + " has missed scheduled medication doses in a row. Please check in with them.");
    }

    public static String vitalsTitle(boolean critical) {
        return critical ? TITLE_VITALS_CRITICAL : TITLE_VITALS_ABNORMAL;
    }

    /** N13 (caregivers only). */
    public static String vitals(boolean critical, String patient) {
        return sentence(critical
                ? "A health reading recorded for " + patient
                        + " is far outside the expected range. Please contact the care team."
                : "A health reading recorded for " + patient + " is outside the expected range");
    }

    // ==================== HELPERS ====================

    /**
     * A system cancellation reason as shown: trimmed, runs of whitespace collapsed to one space, cut to
     * {@link #MAX_REASON_LENGTH} characters; null when nothing is left.
     */
    public static String normalizeReason(String reason) {
        if (reason == null) {
            return null;
        }
        String collapsed = reason.trim().replaceAll("\\s+", " ");
        if (collapsed.isEmpty()) {
            return null;
        }
        if (collapsed.codePointCount(0, collapsed.length()) > MAX_REASON_LENGTH) {
            collapsed = collapsed.substring(0, collapsed.offsetByCodePoints(0, MAX_REASON_LENGTH)).trim();
        }
        return collapsed;
    }

    private static String on(LocalDateTime when) {
        return when == null ? "" : " on " + WHEN.format(when);
    }

    /** Upper-cases the first letter, so a fallback such as "the patient" can open the sentence. */
    static String sentence(String text) {
        if (text == null || text.isEmpty() || !Character.isLowerCase(text.charAt(0))) {
            return text;
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
