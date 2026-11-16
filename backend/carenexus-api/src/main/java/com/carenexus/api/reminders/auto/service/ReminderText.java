package com.carenexus.api.reminders.auto.service;

import com.carenexus.api.communications.service.CommunicationLabels;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Titles, messages, dedupe keys and time phrases of the automatic reminders (English, 24-hour time, generic text:
 * no reason for visit, appointment type, dosage, diagnosis or result).
 *
 * Every time is clinic wall-clock. When the recipient's {@code user_settings.timezone} is a valid zone whose wall
 * clock differs from the clinic's at that instant, the recipient's local time is added:
 * {@code " (HH:mm your time)"} on the same local date, {@code " (EEE d MMM HH:mm your time)"} on another one.
 */
public final class ReminderText {

    public static final String FIRST_REMINDER_TITLE = "Appointment reminder";
    public static final String SECOND_REMINDER_TITLE = "Appointment coming up";
    public static final String DOSE_REMINDER_TITLE = "Medication reminder";
    public static final String NEEDS_OUTCOME_TITLE = "Appointment needs an outcome";

    /** Fixed dedupe prefixes ("24H" and "1H" label first and second; they do not follow the lead-time settings). */
    public static final String FIRST_REMINDER_KEY = "APPT_REMINDER_24H";
    public static final String SECOND_REMINDER_KEY = "APPT_REMINDER_1H";
    public static final String DOSE_REMINDER_KEY = "DOSE_REMINDER";
    public static final String NEEDS_OUTCOME_KEY = "APPT_NEEDS_OUTCOME";

    static final String PATIENT_FALLBACK = "the patient";
    static final String DOCTOR_FALLBACK = "the doctor";
    static final String PATIENT_MEDICATION_FALLBACK = "your medication";
    static final String CAREGIVER_MEDICATION_FALLBACK = "a medication";
    static final int MAX_MEDICATION_NAME = 120;

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("EEE d MMM yyyy 'at' HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter LOCAL_OTHER_DAY =
            DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter KEY_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.ENGLISH);

    private ReminderText() {
    }

    // ==================== MESSAGES ====================

    /** R1 and R2 to the patient: {@code You have an appointment with {doctor} on {when}.} */
    public static String appointmentForPatient(String doctorName, LocalDateTime start, ZoneId clinic, ZoneId recipient) {
        return "You have an appointment with " + doctor(doctorName) + " on " + when(start, clinic, recipient) + ".";
    }

    /** R1 and R2 to a caregiver: {@code {patient} has an appointment with {doctor} on {when}.} */
    public static String appointmentForCaregiver(String patientName, String doctorName, LocalDateTime start,
                                                 ZoneId clinic, ZoneId recipient) {
        return patientSubject(patientName) + " has an appointment with " + doctor(doctorName) + " on "
                + when(start, clinic, recipient) + ".";
    }

    /** R3 to the patient: {@code It is time to take {medication}. Due at {time}.} */
    public static String doseForPatient(String medicationName, LocalDateTime due, ZoneId clinic, ZoneId recipient) {
        return "It is time to take " + medication(medicationName, PATIENT_MEDICATION_FALLBACK) + ". Due at "
                + time(due, clinic, recipient) + ".";
    }

    /** R3 to a caregiver: {@code {patient} has a dose of {medication} due at {time}.} */
    public static String doseForCaregiver(String patientName, String medicationName, LocalDateTime due,
                                          ZoneId clinic, ZoneId recipient) {
        return patientSubject(patientName) + " has a dose of "
                + medication(medicationName, CAREGIVER_MEDICATION_FALLBACK) + " due at "
                + time(due, clinic, recipient) + ".";
    }

    /** R4 to the doctor. */
    public static String needsOutcomeForDoctor(String patientName, LocalDateTime start, ZoneId clinic, ZoneId recipient) {
        return "Your appointment with " + patient(patientName) + " on " + when(start, clinic, recipient)
                + " still has no outcome. Mark it completed, no-show or cancelled.";
    }

    // ==================== KEYS ====================

    /** {@code <prefix>:<appointmentId>:<yyyy-MM-ddTHH:mm>} with the clinic wall-clock start. */
    public static String appointmentKey(String prefix, int appointmentId, LocalDateTime start) {
        return prefix + ":" + appointmentId + ":" + start.format(KEY_DATE_TIME);
    }

    public static String doseKey(int scheduleId) {
        return DOSE_REMINDER_KEY + ":" + scheduleId;
    }

    // ==================== TIME PHRASES ====================

    /** {@code Thu 12 Nov 2026 at 09:00 clinic time} plus the local part. */
    public static String when(LocalDateTime clinicTime, ZoneId clinic, ZoneId recipient) {
        return clinicTime.format(DATE_TIME) + " clinic time" + localPart(clinicTime, clinic, recipient);
    }

    /** {@code 08:00 clinic time} plus the local part. */
    public static String time(LocalDateTime clinicTime, ZoneId clinic, ZoneId recipient) {
        return clinicTime.format(TIME) + " clinic time" + localPart(clinicTime, clinic, recipient);
    }

    /**
     * Empty when the recipient's wall clock equals the clinic's at that instant; otherwise
     * {@code " (HH:mm your time)"} (same date) or {@code " (EEE d MMM HH:mm your time)"} (other date).
     */
    static String localPart(LocalDateTime clinicTime, ZoneId clinic, ZoneId recipient) {
        if (recipient == null || recipient.equals(clinic)) {
            return "";
        }
        LocalDateTime local = clinicTime.atZone(clinic).withZoneSameInstant(recipient).toLocalDateTime();
        if (local.equals(clinicTime)) {
            return "";
        }
        if (local.toLocalDate().equals(clinicTime.toLocalDate())) {
            return " (" + local.format(TIME) + " your time)";
        }
        return " (" + local.format(LOCAL_OTHER_DAY) + " your time)";
    }

    /**
     * The recipient's zone: the stored {@code user_settings.timezone} when it is non-blank and a valid
     * {@link ZoneId}, otherwise the clinic zone (never an error).
     */
    public static ZoneId recipientZone(String stored, ZoneId clinic) {
        if (stored == null || stored.isBlank()) {
            return clinic;
        }
        try {
            return ZoneId.of(stored.trim());
        } catch (DateTimeException invalid) {
            return clinic;
        }
    }

    // ==================== PLACEHOLDERS ====================

    static String patient(String fullName) {
        return fullName == null || fullName.isBlank() ? PATIENT_FALLBACK : fullName.trim();
    }

    static String doctor(String fullName) {
        return fullName == null || fullName.isBlank() ? DOCTOR_FALLBACK
                : CommunicationLabels.withDoctorTitle(fullName.trim());
    }

    static String medication(String name, String fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        String trimmed = name.trim();
        return trimmed.length() > MAX_MEDICATION_NAME ? trimmed.substring(0, MAX_MEDICATION_NAME).trim() : trimmed;
    }

    /** {patient} at the start of a sentence: the name as stored, or "The patient". */
    static String patientSubject(String fullName) {
        return fullName == null || fullName.isBlank() ? "The patient" : fullName.trim();
    }
}
