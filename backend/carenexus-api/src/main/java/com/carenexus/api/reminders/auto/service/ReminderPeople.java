package com.carenexus.api.reminders.auto.service;

import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.reminders.auto.repository.ReminderJobsJdbc;
import com.carenexus.api.reminders.auto.repository.ReminderJobsJdbc.PersonName;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Who receives an automatic reminder, and the names and time zones its text needs.
 *
 * The recipient set of a patient-circle reminder is the one {@code NotificationPublisher.publishToPatientCircle}
 * uses (the patient plus {@link AuthorizationService#caregiversWithPermission}: active, unrevoked, unexpired grants
 * that give the permission), resolved at the moment of the sweep. The jobs publish one command per recipient
 * because the wording and the local-time part differ per recipient.
 */
@Component
@RequiredArgsConstructor
public class ReminderPeople {

    private final AuthorizationService authorizationService;
    private final ReminderJobsJdbc jdbc;
    private final Clock clock;

    /** One recipient of a patient-circle reminder. */
    public record Recipient(String systemId, boolean patient) {
    }

    /** Names and stored time zones of a batch of people, looked up once per batch. */
    public static final class Directory {

        private final Map<String, PersonName> names;
        private final Map<String, String> zones;
        private final ZoneId clinic;

        Directory(Map<String, PersonName> names, Map<String, String> zones, ZoneId clinic) {
            this.names = names;
            this.zones = zones;
            this.clinic = clinic;
        }

        /** "First Last", or null when unknown (the text then uses its fallback). */
        public String fullName(String systemId) {
            PersonName name = systemId == null ? null : names.get(systemId);
            return name == null ? null : name.fullName();
        }

        /** The person's {@code user_settings.timezone} when valid, otherwise the clinic zone. */
        public ZoneId zone(String systemId) {
            return ReminderText.recipientZone(systemId == null ? null : zones.get(systemId), clinic);
        }

        public ZoneId clinic() {
            return clinic;
        }
    }

    /** The clinic zone ({@code carenexus.scheduling.time-zone}, the zone of the shared Clock). */
    public ZoneId clinicZone() {
        return clock.getZone();
    }

    /** The patient first, then every caregiver holding {@code permission} on the patient, without duplicates. */
    public List<Recipient> patientCircle(String patientSystemId, PatientPermission permission) {
        if (patientSystemId == null || patientSystemId.isBlank()) {
            return List.of();
        }
        Map<String, Recipient> recipients = new LinkedHashMap<>();
        recipients.put(patientSystemId, new Recipient(patientSystemId, true));
        for (String caregiver : authorizationService.caregiversWithPermission(patientSystemId, permission)) {
            if (caregiver != null) {
                recipients.putIfAbsent(caregiver, new Recipient(caregiver, false));
            }
        }
        return new ArrayList<>(recipients.values());
    }

    /**
     * {@link #patientCircle} for several patients with one grant query (batch jobs): patient system id to its circle,
     * the patient first. Blank ids are skipped.
     */
    public Map<String, List<Recipient>> patientCircles(Collection<String> patientSystemIds, PatientPermission permission) {
        Map<String, List<String>> caregivers = authorizationService.caregiversWithPermissionByPatient(patientSystemIds, permission);
        Map<String, List<Recipient>> circles = new LinkedHashMap<>();
        caregivers.forEach((patientSystemId, ids) -> {
            Map<String, Recipient> recipients = new LinkedHashMap<>();
            recipients.put(patientSystemId, new Recipient(patientSystemId, true));
            for (String caregiver : ids) {
                if (caregiver != null) {
                    recipients.putIfAbsent(caregiver, new Recipient(caregiver, false));
                }
            }
            circles.put(patientSystemId, new ArrayList<>(recipients.values()));
        });
        return circles;
    }

    /** Looks up the names and time zones of everybody named in {@code systemIds}. */
    public Directory directory(Collection<String> systemIds) {
        Set<String> ids = new LinkedHashSet<>(systemIds);
        ids.remove(null);
        return new Directory(jdbc.names(ids), jdbc.timeZones(ids), clinicZone());
    }
}
