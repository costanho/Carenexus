package com.carenexus.api.communications.listener.clinical;

import com.carenexus.api.common.event.HealthMetricRecordedEvent;
import com.carenexus.api.common.event.MedicationDoseMissedEvent;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.communications.notify.NotificationCategory;
import com.carenexus.api.communications.notify.NotificationCommand;
import com.carenexus.api.communications.notify.NotificationPriority;
import com.carenexus.api.communications.notify.NotificationTarget;
import com.carenexus.api.communications.notify.NotificationType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Caregiver-only monitoring notifications (api/be-clinical-notifications.md N12, N13): two or more missed doses in
 * a row, and an abnormal health reading. The patient is not notified (their dose reminders come from
 * be-reminder-jobs) and no doctor is (doctors get the MISSED_MEDICATION / CRITICAL_VITALS AI alerts of
 * be-ai-generators), D9.
 *
 * Plain {@link EventListener} methods that run in the publisher's transaction.
 */
@Component
@RequiredArgsConstructor
public class CareMonitoringNotificationListener {

    /** Fewest consecutive misses (this dose included) that notify caregivers. */
    static final int MISSED_DOSE_THRESHOLD = 2;

    static final String SCHEDULE_RESOURCE = "MEDICATION_SCHEDULE";
    static final String METRIC_RESOURCE = "HEALTH_METRIC";
    static final String DOSE_MISSED_PREFIX = "DOSE_MISSED:";
    static final String VITALS_PREFIX = "VITALS_ABNORMAL:";
    static final String PARAM_DATE = "date";

    /** The two halves of one blood-pressure measurement count as one metric family. */
    static final Set<String> BLOOD_PRESSURE_TYPES = Set.of("BP_SYSTOLIC", "BP_DIASTOLIC");
    static final String BLOOD_PRESSURE_FAMILY = "BP";
    static final String UNKNOWN_FAMILY = "UNKNOWN";
    static final int MAX_FAMILY_LENGTH = 60;

    private final ClinicalNotifier notifier;
    /** Clinic-zone clock: the clinic day of the vitals dedupe key. */
    private final Clock clock;

    /**
     * N12: caregivers with MEDICATIONS, once per prescription and day of the missed dose, from the first event of
     * that day with two or more consecutive misses.
     */
    @EventListener
    public void onDoseMissed(MedicationDoseMissedEvent event) {
        if (event.patientSystemId() == null || event.consecutiveMisses() < MISSED_DOSE_THRESHOLD) {
            return;
        }
        Integer keyId = event.prescriptionId() != null ? event.prescriptionId() : event.scheduleId();
        if (keyId == null) {
            return;
        }
        Map<String, Audience> caregivers =
                notifier.recipients(null, event.patientSystemId(), false, PatientPermission.MEDICATIONS);
        if (caregivers.isEmpty()) {
            return;
        }
        LocalDate day = event.scheduleDate() != null ? event.scheduleDate() : LocalDate.now(clock);
        String patient = notifier.names(event.patientSystemId()).patient(event.patientSystemId());
        NotificationCommand.Builder template = NotificationCommand.builder()
                .type(NotificationType.MEDICATION)
                .category(NotificationCategory.MEDICATION_REMINDER)
                .priority(NotificationPriority.HIGH)
                .title(ClinicalNotificationTexts.TITLE_DOSES_MISSED)
                .message(ClinicalNotificationTexts.TITLE_DOSES_MISSED)
                .target(NotificationTarget.MEDICATION_SCHEDULE)
                .resourceType(SCHEDULE_RESOURCE)
                .resourceId(event.scheduleId())
                .patientSystemId(event.patientSystemId())
                .actorSystemId(null)
                .dedupeKey(DOSE_MISSED_PREFIX + keyId + ":" + day);
        if (event.scheduleDate() != null) {
            template.param(PARAM_DATE, event.scheduleDate().toString());
        }
        notifier.publishEach(caregivers, template.build(), audience -> ClinicalNotificationTexts.dosesMissed(patient));
    }

    /**
     * N13: caregivers with HEALTH_METRICS, at most one ABNORMAL and one CRITICAL notification per patient, metric
     * family and clinic day (D10), so the two events of a blood-pressure pair give one notification. A caregiver
     * who entered the reading is the actor and is not notified. The metric type and value are never shown.
     */
    @EventListener
    public void onHealthMetricRecorded(HealthMetricRecordedEvent event) {
        if (!event.abnormal() || event.patientSystemId() == null || event.metricId() == null) {
            return;
        }
        Map<String, Audience> caregivers =
                notifier.recipients(null, event.patientSystemId(), false, PatientPermission.HEALTH_METRICS);
        if (caregivers.isEmpty()) {
            return;
        }
        boolean critical = HealthMetricRecordedEvent.CRITICAL.equals(event.severity());
        String severity = critical ? HealthMetricRecordedEvent.CRITICAL : HealthMetricRecordedEvent.ABNORMAL;
        String patientId = event.patientSystemId().trim();
        String patient = notifier.names(patientId).patient(patientId);
        String dedupeKey = VITALS_PREFIX + patientId + ":" + family(event.type()) + ":" + LocalDate.now(clock) + ":"
                + severity;
        NotificationCommand template = NotificationCommand.builder()
                .type(NotificationType.SYSTEM)
                .category(NotificationCategory.VITALS)
                .priority(critical ? NotificationPriority.HIGH : NotificationPriority.NORMAL)
                .title(ClinicalNotificationTexts.vitalsTitle(critical))
                .message(ClinicalNotificationTexts.vitalsTitle(critical))
                .target(NotificationTarget.HEALTH_TRACKING)
                .resourceType(METRIC_RESOURCE)
                .resourceId(event.metricId())
                .patientSystemId(patientId)
                .actorSystemId(event.recordedBySystemId())
                .dedupeKey(dedupeKey)
                .build();
        notifier.publishEach(caregivers, template, audience -> ClinicalNotificationTexts.vitals(critical, patient));
    }

    /**
     * {@code BP} for either blood-pressure half; otherwise the type as sent (trimmed; cut to
     * {@link #MAX_FAMILY_LENGTH} characters so the dedupe key always fits its column).
     */
    static String family(String type) {
        String value = ClinicalNotifier.trimToNull(type);
        if (value == null) {
            return UNKNOWN_FAMILY;
        }
        if (BLOOD_PRESSURE_TYPES.contains(value.toUpperCase(Locale.ROOT))) {
            return BLOOD_PRESSURE_FAMILY;
        }
        return value.length() > MAX_FAMILY_LENGTH ? value.substring(0, MAX_FAMILY_LENGTH) : value;
    }
}
