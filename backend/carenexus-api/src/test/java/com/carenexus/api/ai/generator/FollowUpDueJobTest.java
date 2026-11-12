package com.carenexus.api.ai.generator;

import com.carenexus.api.ai.alert.AiAlertService;
import com.carenexus.api.ai.generator.config.AiGeneratorProperties;
import com.carenexus.api.ai.generator.job.FollowUpDueJob;
import com.carenexus.api.ai.generator.repository.AiGeneratorJdbc;
import com.carenexus.api.common.job.JobSupport;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Row 6 of api/be-ai-generators.md 5.1, rule 5.5: the follow-up-due job, driven with {@code sweepOnce()} and a fixed
 * clock. A completed consultation whose follow-up date has passed (within the look-back window) with no later
 * SCHEDULED, RESCHEDULED or COMPLETED appointment with the same doctor raises one FOLLOW_UP_DUE alert for that
 * doctor, once for ever.
 */
class FollowUpDueJobTest extends CommittedIntegrationTest {

    private static final ZoneId CLINIC = ZoneId.of("Africa/Harare");
    /** Clinic today in this test: 15 March 2026, 10:00. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 15, 10, 0);
    private static final LocalDate TODAY = NOW.toLocalDate();

    @Autowired
    private JobSupport jobSupport;

    @Autowired
    private AiGeneratorJdbc generatorJdbc;

    @Autowired
    private AiAlertService aiAlertService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private AiGeneratorProperties properties;

    private AiGeneratorFixtures fixtures;
    private FollowUpDueJob job;

    @BeforeEach
    void setUpJob() {
        fixtures = new AiGeneratorFixtures(jdbc);
        job = jobAt(NOW);
    }

    private FollowUpDueJob jobAt(LocalDateTime clinicNow) {
        Clock fixed = Clock.fixed(clinicNow.atZone(CLINIC).toInstant(), CLINIC);
        return new FollowUpDueJob(jobSupport, generatorJdbc, aiAlertService, transactionManager, fixed, properties,
                CLINIC.getId());
    }

    /** A completed consultation two weeks ago with a follow-up due on {@code followUpDate}. */
    private int completed(String patientId, String doctorId, LocalDate followUpDate) {
        return fixtures.consultation(patientId, doctorId, "COMPLETED", true, followUpDate, NOW.minusDays(14));
    }

    private List<Map<String, Object>> followUpAlerts() {
        return jdbc.queryForList("SELECT * FROM ai_alerts WHERE alert_type = 'FOLLOW_UP_DUE' ORDER BY source_id");
    }

    @Test
    void anOverdueFollowUpWithoutALaterAppointmentRaisesOneLowAlertForTheConsultationsDoctor() throws Exception {
        int consultationId = completed(patient.getSystemId(), doctor.getSystemId(), TODAY.minusDays(3));

        assertEquals(1, job.sweepOnce());

        List<Map<String, Object>> alerts = followUpAlerts();
        assertEquals(1, alerts.size());
        Map<String, Object> alert = alerts.get(0);
        assertEquals(patient.getSystemId(), alert.get("patient_system_id"));
        assertEquals(doctor.getSystemId(), alert.get("doctor_system_id"));
        assertEquals("LOW", alert.get("priority"));
        assertEquals(Boolean.FALSE, alert.get("action_required"));
        assertEquals("Follow-up due", alert.get("title"));
        assertEquals("The follow-up recommended for 12 Mar 2026 has passed and no later appointment is booked.",
                alert.get("message"));
        assertEquals("Contact the patient to book the follow-up appointment.", alert.get("suggested_action"));
        assertEquals("CONSULTATION", alert.get("source_type"));
        assertEquals(consultationId, alert.get("source_id"));
        assertEquals("FOLLOW_UP_DUE:" + consultationId, alert.get("dedupe_key"));

        List<Map<String, Object>> notifications = fixtures.notificationsAbout((Integer) alert.get("alert_id"));
        assertEquals(1, notifications.size());
        assertEquals(doctor.getSystemId(), notifications.get(0).get("system_id"));
        assertEquals("LOW", notifications.get(0).get("priority"));

        // Once for ever: a second sweep, or booking the follow-up afterwards, changes nothing.
        assertEquals(0, job.sweepOnce());
        fixtures.appointment(patient.getSystemId(), doctor.getSystemId(), "SCHEDULED", NOW.plusDays(2));
        assertEquals(0, jobAt(NOW.plusDays(1)).sweepOnce());
        assertEquals(1, followUpAlerts().size());
        assertEquals(1, fixtures.aiAlertNotifications(doctor.getSystemId()));

        // The patient sees it verbatim, without the clinician's suggested action.
        JsonNode own = json(doGet("/api/ai-alerts/patient/" + patient.getSystemId(), patient));
        assertEquals(1, own.path("content").size(), own.toString());
        JsonNode row = own.path("content").get(0);
        assertEquals("FOLLOW_UP_DUE", row.path("alertType").asText());
        assertEquals("The follow-up recommended for 12 Mar 2026 has passed and no later appointment is booked.",
                row.path("message").asText());
        assertTrue(row.path("suggestedAction").isNull(), row.toString());
    }

    @Test
    void onlyFollowUpDatesBeforeTodayAndInsideTheLookBackWindowCount() {
        int edge = completed(patient.getSystemId(), doctor.getSystemId(), TODAY.minusDays(30));
        completed(patient.getSystemId(), doctor.getSystemId(), TODAY.minusDays(31));
        completed(patient.getSystemId(), doctor.getSystemId(), TODAY);
        completed(patient.getSystemId(), doctor.getSystemId(), TODAY.plusDays(4));

        assertEquals(1, job.sweepOnce());
        assertEquals(List.of(edge), followUpAlerts().stream().map(a -> a.get("source_id")).toList());
    }

    @Test
    void theLookBackWindowComesFromConfiguration() {
        completed(patient.getSystemId(), doctor.getSystemId(), TODAY.minusDays(45));
        int previous = properties.getFollowUpLookbackDays();
        try {
            properties.setFollowUpLookbackDays(60);
            assertEquals(1, job.sweepOnce());
        } finally {
            properties.setFollowUpLookbackDays(previous);
        }
    }

    @Test
    void aLaterActiveAppointmentWithTheSameDoctorMeansTheFollowUpIsBooked() {
        int scheduled = completed(patient.getSystemId(), doctor.getSystemId(), TODAY.minusDays(2));
        fixtures.appointment(patient.getSystemId(), doctor.getSystemId(), "SCHEDULED", NOW.plusDays(3));

        int rescheduled = completed(otherPatient.getSystemId(), otherDoctor.getSystemId(), TODAY.minusDays(2));
        fixtures.appointment(otherPatient.getSystemId(), otherDoctor.getSystemId(), "RESCHEDULED",
                NOW.minusDays(5).plusMinutes(5));

        assertEquals(0, job.sweepOnce());
        assertTrue(followUpAlerts().isEmpty(), "ids " + scheduled + ", " + rescheduled);
    }

    @Test
    void aCompletedVisitAfterTheConsultationAlsoCounts() {
        completed(patient.getSystemId(), doctor.getSystemId(), TODAY.minusDays(2));
        fixtures.appointment(patient.getSystemId(), doctor.getSystemId(), "COMPLETED", NOW.minusDays(4));

        assertEquals(0, job.sweepOnce());
    }

    @Test
    void cancelledNoShowEarlierOrOtherDoctorsAppointmentsDoNotCount() {
        int consultationId = completed(patient.getSystemId(), doctor.getSystemId(), TODAY.minusDays(2));
        fixtures.appointment(patient.getSystemId(), doctor.getSystemId(), "CANCELLED", NOW.plusDays(1));
        fixtures.appointment(patient.getSystemId(), doctor.getSystemId(), "NO_SHOW", NOW.minusDays(3));
        fixtures.appointment(patient.getSystemId(), doctor.getSystemId(), "SCHEDULED", NOW.minusDays(20));
        fixtures.appointment(patient.getSystemId(), otherDoctor.getSystemId(), "SCHEDULED", NOW.plusDays(2));

        assertEquals(1, job.sweepOnce());
        assertEquals(consultationId, followUpAlerts().get(0).get("source_id"));
    }

    @Test
    void consultationsThatAreNotCompletedOrNeedNoFollowUpAreIgnored() {
        fixtures.consultation(patient.getSystemId(), doctor.getSystemId(), "COMPLETED", false, TODAY.minusDays(2),
                NOW.minusDays(14));
        fixtures.consultation(patient.getSystemId(), doctor.getSystemId(), "COMPLETED", true, null,
                NOW.minusDays(13));
        fixtures.consultation(patient.getSystemId(), doctor.getSystemId(), "IN_PROGRESS", true, TODAY.minusDays(2),
                NOW.minusDays(12));
        fixtures.consultation(patient.getSystemId(), doctor.getSystemId(), "CANCELLED", true, TODAY.minusDays(2),
                NOW.minusDays(11));

        assertEquals(0, job.sweepOnce());
        assertEquals(0, fixtures.alertCount());
    }

    @Test
    void manyOverdueFollowUpsAreHandledAcrossBatches() {
        // More than one batch of 200: every consultation gets exactly one alert. Each consultation's own visit lies
        // before every completion time, so no visit counts as a later appointment.
        for (int i = 0; i < 205; i++) {
            jdbc.update("INSERT INTO consultations (appointment_id, patient_system_id, doctor_system_id, "
                            + "follow_up_required, follow_up_date, status, completed_at, created_at) "
                            + "VALUES (?, ?, ?, TRUE, ?, 'COMPLETED', ?, ?)",
                    fixtures.appointment(patient.getSystemId(), doctor.getSystemId(), "COMPLETED",
                            NOW.minusDays(21).plusMinutes(5L * i)),
                    patient.getSystemId(), doctor.getSystemId(), java.sql.Date.valueOf(TODAY.minusDays(1)),
                    java.sql.Timestamp.valueOf(NOW.minusDays(20)), java.sql.Timestamp.valueOf(NOW.minusDays(20)));
        }

        assertEquals(205, job.sweepOnce());
        assertEquals(205, followUpAlerts().size());
        assertEquals(0, job.sweepOnce());
    }
}
