package com.carenexus.api.communications.listener.clinical;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.communications.notify.NotificationCommand;
import com.carenexus.api.communications.notify.NotificationPublisher;
import com.carenexus.api.communications.service.CommunicationLabels;
import com.carenexus.api.communications.service.CommunicationsAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Shared plumbing of the clinical notification listeners: resolving recipients (doctor, patient, caregivers holding
 * a permission), loading display names once per event, and handing one command per recipient to the
 * {@link NotificationPublisher}.
 *
 * The listeners do not call {@code publishToPatientCircle}, because the wording differs by audience (D2); they
 * resolve the same circle here ({@link AuthorizationService#caregiversWithPermission}: active, unrevoked, unexpired
 * grants that give the permission) and publish per recipient. The publisher still applies the actor rule, the
 * recipient's category toggle and the per-recipient dedupe key.
 */
@Component
@RequiredArgsConstructor
public class ClinicalNotifier {

    private final NotificationPublisher publisher;
    private final AuthorizationService authorizationService;
    private final CommunicationsAccessService communicationsAccessService;

    /**
     * Recipients in the order doctor, patient, caregivers, each system id at most once (the first audience wins).
     * A null or blank id is skipped.
     *
     * @param doctorSystemId    the appointment's doctor, or null when no doctor is addressed
     * @param patientSystemId   the patient
     * @param includePatient    whether the patient is a recipient
     * @param caregiverPermission the permission a caregiver needs, or null for no caregivers
     */
    public Map<String, Audience> recipients(String doctorSystemId, String patientSystemId, boolean includePatient,
                                            PatientPermission caregiverPermission) {
        Map<String, Audience> recipients = new LinkedHashMap<>();
        String doctor = trimToNull(doctorSystemId);
        String patient = trimToNull(patientSystemId);
        if (doctor != null) {
            recipients.putIfAbsent(doctor, Audience.DOCTOR);
        }
        if (patient != null && includePatient) {
            recipients.putIfAbsent(patient, Audience.PATIENT);
        }
        if (patient != null && caregiverPermission != null) {
            for (String caregiver : authorizationService.caregiversWithPermission(patient, caregiverPermission)) {
                String id = trimToNull(caregiver);
                if (id != null) {
                    recipients.putIfAbsent(id, Audience.CAREGIVER);
                }
            }
        }
        return recipients;
    }

    /** Loads the named users in one query. */
    public Names names(String... systemIds) {
        List<String> ids = new ArrayList<>();
        for (String id : systemIds) {
            String trimmed = trimToNull(id);
            if (trimmed != null) {
                ids.add(trimmed);
            }
        }
        return new Names(ids.isEmpty() ? Map.of() : communicationsAccessService.usersById(ids));
    }

    /**
     * Publishes one command per recipient. {@code message} builds the text for each audience; {@code template}
     * carries every other field (the recipient is replaced).
     */
    public void publishEach(Map<String, Audience> recipients, NotificationCommand template,
                            Function<Audience, String> message) {
        recipients.forEach((recipient, audience) -> publisher.publish(template.toBuilder()
                .recipientSystemId(recipient)
                .message(message.apply(audience))
                .build()));
    }

    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Display names of the users of one event (never a raw system id, never "null"). */
    public static final class Names {

        /** Longest name shown in a message (characters, before a "Dr." title is added). */
        public static final int MAX_NAME_LENGTH = 100;

        private final Map<String, User> users;

        Names(Map<String, User> users) {
            this.users = users;
        }

        /** "First Last", or "the patient". */
        public String patient(String systemId) {
            String name = name(systemId);
            return name != null ? name : ClinicalNotificationTexts.FALLBACK_PATIENT;
        }

        /** "Dr. First Last", or "the doctor". */
        public String doctor(String systemId) {
            String name = name(systemId);
            return name != null ? CommunicationLabels.withDoctorTitle(name) : ClinicalNotificationTexts.FALLBACK_DOCTOR;
        }

        /**
         * Uploader of a document: a doctor as "Dr. First Last", a caregiver or patient as "First Last", an
         * administrator, an unknown account or a missing name as "The clinic".
         */
        public String uploader(String systemId) {
            User user = user(systemId);
            String name = name(systemId);
            if (user == null || name == null || user.getRole() == null) {
                return ClinicalNotificationTexts.FALLBACK_UPLOADER;
            }
            return switch (user.getRole().trim().toUpperCase(Locale.ROOT)) {
                case AuthorizationService.DOCTOR -> CommunicationLabels.withDoctorTitle(name);
                case AuthorizationService.CAREGIVER, AuthorizationService.PATIENT -> name;
                default -> ClinicalNotificationTexts.FALLBACK_UPLOADER;
            };
        }

        private User user(String systemId) {
            String id = trimToNull(systemId);
            return id == null ? null : users.get(id);
        }

        /**
         * "First Last" of the user (cut to {@link #MAX_NAME_LENGTH} characters, so the longest message stays under
         * the 500-character limit), or null when the row is missing or the name is blank.
         */
        private String name(String systemId) {
            User user = user(systemId);
            if (user == null) {
                return null;
            }
            String name = CommunicationLabels.name(user, null);
            if (name == null || name.isBlank()) {
                return null;
            }
            String trimmed = name.trim();
            if (trimmed.codePointCount(0, trimmed.length()) > MAX_NAME_LENGTH) {
                trimmed = trimmed.substring(0, trimmed.offsetByCodePoints(0, MAX_NAME_LENGTH)).trim();
            }
            return trimmed;
        }
    }
}
