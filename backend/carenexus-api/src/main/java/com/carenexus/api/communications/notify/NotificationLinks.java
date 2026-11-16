package com.carenexus.api.communications.notify;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds the in-app link of a notification (the web deep-link contract):
 * <pre>
 * /dashboard/&lt;role&gt;?section=&lt;sectionKey&gt;[&amp;patient=&lt;PAT id&gt;][&amp;id=&lt;resource id&gt;][&amp;tab=&lt;tab&gt;][&amp;&lt;other&gt;=&lt;value&gt;...]
 * </pre>
 * <ul>
 *   <li>{@code role}: the recipient's role in lower case; CAREGIVER becomes {@code proxy};</li>
 *   <li>{@code section} (and sometimes a fixed {@code tab}): from the target table below, per role. The keys are
 *       the web section registry keys; a target with no section for the role gives no link (null);</li>
 *   <li>{@code patient}: added when a patient is given and the recipient is a caregiver (every target) or a doctor
 *       on the targets that open one patient (HEALTH_TRACKING, CARE_TEAM). Never for patient or admin
 *       recipients, and not for the other doctor targets, where it would switch on the list filter;</li>
 *   <li>{@code id}: {@code params.id}, else the resource id;</li>
 *   <li>{@code tab}: the tab fixed by the table; when the table fixes none, {@code params.tab};</li>
 *   <li>every other entry of {@code params} is passed through, sorted by key. {@code section} and {@code patient}
 *       keys inside {@code params} are ignored.</li>
 * </ul>
 * Values are percent-encoded (UTF-8, a space is {@code %20}). The link is relative: it carries no origin.
 */
public final class NotificationLinks {

    static final String ROLE_PATIENT = "patient";
    static final String ROLE_DOCTOR = "doctor";
    static final String ROLE_PROXY = "proxy";
    static final String ROLE_ADMIN = "admin";

    /** Resource type that sends a caregiver's CAREGIVER_ACCESS link to the access-requests section. */
    public static final String RESOURCE_ACCESS_REQUEST = "ACCESS_REQUEST";

    private static final String PARAM_SECTION = "section";
    private static final String PARAM_PATIENT = "patient";
    private static final String PARAM_ID = "id";
    private static final String PARAM_TAB = "tab";
    private static final Set<String> RESERVED = Set.of(PARAM_SECTION, PARAM_PATIENT, PARAM_ID, PARAM_TAB);

    /** Doctor targets that open one patient and therefore carry the patient parameter. */
    private static final Set<NotificationTarget> DOCTOR_PER_PATIENT_TARGETS =
            EnumSet.of(NotificationTarget.HEALTH_TRACKING, NotificationTarget.CARE_TEAM);

    private static final Section PROXY_ACCESS_REQUESTS = new Section("access-requests", null);

    /** A dashboard section and the tab the target fixes inside it (null = none fixed). */
    private record Section(String key, String tab) {
    }

    private static final Map<NotificationTarget, Section> PATIENT = new EnumMap<>(NotificationTarget.class);
    private static final Map<NotificationTarget, Section> DOCTOR = new EnumMap<>(NotificationTarget.class);
    private static final Map<NotificationTarget, Section> PROXY = new EnumMap<>(NotificationTarget.class);
    private static final Map<NotificationTarget, Section> ADMIN = new EnumMap<>(NotificationTarget.class);

    static {
        row(NotificationTarget.APPOINTMENTS, "my-appointments", "appointments", "appointments", null);
        row(NotificationTarget.CONSULTATIONS, "consultation-history", "consultations", "documents-results&consultations", null);
        row(NotificationTarget.PRESCRIPTIONS, "prescriptions", "prescriptions", "medications", null);
        row(NotificationTarget.MEDICATION_SCHEDULE, "prescriptions&schedule", "prescriptions", "medications&schedule", null);
        row(NotificationTarget.LAB_RESULTS, "lab-results", "labs", "documents-results&labs", null);
        row(NotificationTarget.IMAGING_RESULTS, "imaging-results", "imaging", "documents-results&imaging", null);
        row(NotificationTarget.MEDICAL_RECORDS, "medical-records", "medical-records", "documents-results&records", null);
        row(NotificationTarget.DOCUMENTS, "documents", "documents", "documents-results&documents", null);
        row(NotificationTarget.REFERRALS, "referrals", "referrals", "documents-results&referrals", null);
        row(NotificationTarget.CARE_PLANS, "care-plans", "care-plans", "care-plans", null);
        row(NotificationTarget.HEALTH_TRACKING, "health-tracking", "patients&vitals", "health-summary", null);
        row(NotificationTarget.AI_ALERTS, "health-alerts", "ai-alerts", "health-alerts", null);
        row(NotificationTarget.BILLING, "billing", "billing", "billing", "billing");
        // proxy: access-requests when the resource is an access request (see sectionFor)
        row(NotificationTarget.CAREGIVER_ACCESS, "my-proxies", null, "profile-settings", null);
        row(NotificationTarget.CARE_TEAM, "care-team", "patients", "patient-profile", null);
        row(NotificationTarget.MESSAGES, "messages", "messages", "messages", "messages");
        row(NotificationTarget.ACCOUNT_SECURITY, "profile-settings&security", "profile&security", "profile-settings&security", "account");
        row(NotificationTarget.CORRECTIONS, "medical-records&corrections", "medical-records&corrections", "documents-results&corrections", "corrections");
        row(NotificationTarget.ADMIN_CLINICIAN_QUEUE, null, null, null, "clinician-queue");
        row(NotificationTarget.ADMIN_ACCOUNT_REQUESTS, null, null, null, "account-requests");
        row(NotificationTarget.ADMIN_BILLING, null, null, null, "billing");
    }

    private NotificationLinks() {
    }

    /**
     * Link for a target without a referenced resource.
     *
     * @param recipientRole   the recipient's {@code users.role} (PATIENT, DOCTOR, CAREGIVER, ADMIN; any case)
     * @param target          what the notification opens; null gives no link
     * @param params          link parameters (may be null)
     * @param patientSystemId the patient the notification is about (may be null)
     * @return the relative link, or null when the notification is informational for this role
     */
    public static String url(String recipientRole, NotificationTarget target, Map<String, String> params,
                             String patientSystemId) {
        return url(recipientRole, target, params, patientSystemId, null, null);
    }

    /**
     * Link for a target, using the command's resource: {@code resourceId} is the {@code id} parameter unless
     * {@code params.id} is set, and {@code resourceType} ACCESS_REQUEST sends a caregiver's CAREGIVER_ACCESS link to
     * the access-requests section.
     */
    public static String url(String recipientRole, NotificationTarget target, Map<String, String> params,
                             String patientSystemId, String resourceType, Integer resourceId) {
        String role = roleSegment(recipientRole);
        if (role == null || target == null) {
            return null;
        }
        Section section = sectionFor(role, target, resourceType);
        if (section == null) {
            return null;
        }
        Map<String, String> given = params == null ? Map.of() : params;

        StringBuilder url = new StringBuilder("/dashboard/").append(role)
                .append('?').append(PARAM_SECTION).append('=').append(encode(section.key()));
        if (hasText(patientSystemId) && carriesPatient(role, target)) {
            append(url, PARAM_PATIENT, patientSystemId.trim());
        }
        String id = hasText(given.get(PARAM_ID)) ? given.get(PARAM_ID).trim()
                : resourceId != null ? String.valueOf(resourceId) : null;
        if (id != null) {
            append(url, PARAM_ID, id);
        }
        String tab = section.tab() != null ? section.tab()
                : hasText(given.get(PARAM_TAB)) ? given.get(PARAM_TAB).trim() : null;
        if (tab != null) {
            append(url, PARAM_TAB, tab);
        }
        Map<String, String> others = new TreeMap<>();
        given.forEach((key, value) -> {
            if (hasText(key) && hasText(value) && !RESERVED.contains(key)) {
                others.put(key, value);
            }
        });
        others.forEach((key, value) -> append(url, key, value));
        return url.toString();
    }

    /** Role segment of the dashboard path: patient, doctor, proxy or admin; null for an unknown role. */
    static String roleSegment(String recipientRole) {
        if (recipientRole == null) {
            return null;
        }
        return switch (recipientRole.trim().toUpperCase(Locale.ROOT)) {
            case "PATIENT" -> ROLE_PATIENT;
            case "DOCTOR" -> ROLE_DOCTOR;
            case "CAREGIVER" -> ROLE_PROXY;
            case "ADMIN" -> ROLE_ADMIN;
            default -> null;
        };
    }

    private static Section sectionFor(String role, NotificationTarget target, String resourceType) {
        if (ROLE_PROXY.equals(role) && target == NotificationTarget.CAREGIVER_ACCESS
                && resourceType != null && RESOURCE_ACCESS_REQUEST.equals(resourceType.trim().toUpperCase(Locale.ROOT))) {
            return PROXY_ACCESS_REQUESTS;
        }
        return switch (role) {
            case ROLE_PATIENT -> PATIENT.get(target);
            case ROLE_DOCTOR -> DOCTOR.get(target);
            case ROLE_PROXY -> PROXY.get(target);
            case ROLE_ADMIN -> ADMIN.get(target);
            default -> null;
        };
    }

    private static boolean carriesPatient(String role, NotificationTarget target) {
        return ROLE_PROXY.equals(role) || (ROLE_DOCTOR.equals(role) && DOCTOR_PER_PATIENT_TARGETS.contains(target));
    }

    /** One line of the target table; a cell is "section" or "section&tab"; null = no section for that role. */
    private static void row(NotificationTarget target, String patient, String doctor, String proxy, String admin) {
        put(PATIENT, target, patient);
        put(DOCTOR, target, doctor);
        put(PROXY, target, proxy);
        put(ADMIN, target, admin);
    }

    private static void put(Map<NotificationTarget, Section> table, NotificationTarget target, String cell) {
        if (cell == null) {
            return;
        }
        int split = cell.indexOf('&');
        table.put(target, split < 0 ? new Section(cell, null)
                : new Section(cell.substring(0, split), cell.substring(split + 1)));
    }

    private static void append(StringBuilder url, String key, String value) {
        url.append('&').append(encode(key)).append('=').append(encode(value));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
