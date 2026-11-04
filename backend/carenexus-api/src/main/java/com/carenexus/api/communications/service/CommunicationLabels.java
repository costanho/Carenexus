package com.carenexus.api.communications.service;

import com.carenexus.api.auth.model.User;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Display names and labels for conversation participants (contract 7.2):
 * name = "First Last"; label: DOCTOR "Dr. First Last", PATIENT "First Last",
 * CAREGIVER "First Last (Caregiver for PatientFirst PatientLast)".
 */
public final class CommunicationLabels {

    /** A leading "Dr." / "Dr" title, any case. */
    private static final Pattern DOCTOR_TITLE = Pattern.compile("dr(\\.|\\s|$)", Pattern.CASE_INSENSITIVE);

    private CommunicationLabels() {
    }

    public static String name(User user, String fallbackSystemId) {
        if (user == null) {
            return fallbackSystemId;
        }
        String first = user.getFirstName() != null ? user.getFirstName().trim() : "";
        String last = user.getLastName() != null ? user.getLastName().trim() : "";
        String full = (first + " " + last).trim();
        return full.isEmpty() ? fallbackSystemId : full;
    }

    public static String role(User user) {
        return user != null && user.getRole() != null ? user.getRole().toUpperCase(Locale.ROOT) : null;
    }

    public static String label(String name, String role, String patientName) {
        if ("DOCTOR".equals(role)) {
            return withDoctorTitle(name);
        }
        if ("CAREGIVER".equals(role)) {
            return name + " (Caregiver for " + patientName + ")";
        }
        return name;
    }

    /** "Dr. " + name, unless the stored name already carries the title (some seed rows store "Dr. Michael"). */
    public static String withDoctorTitle(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        return DOCTOR_TITLE.matcher(name).lookingAt() ? name : "Dr. " + name;
    }

    public static String label(User user, String fallbackSystemId, String patientName) {
        return label(name(user, fallbackSystemId), role(user), patientName);
    }

    /** Existing TIMESTAMP columns hold clinic wall-clock time (JVM default zone). */
    public static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant();
    }

    /** First 120 characters (code points) of the body for list previews. */
    public static String preview(String body) {
        if (body == null) {
            return "";
        }
        if (body.codePointCount(0, body.length()) <= 120) {
            return body;
        }
        return body.substring(0, body.offsetByCodePoints(0, 120));
    }
}
