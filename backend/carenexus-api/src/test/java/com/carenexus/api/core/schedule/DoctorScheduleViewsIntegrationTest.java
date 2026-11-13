package com.carenexus.api.core.schedule;

import com.carenexus.api.core.model.Appointment;
import com.carenexus.api.core.model.Consultation;
import com.carenexus.api.core.repository.AppointmentRepository;
import com.carenexus.api.core.repository.ConsultationRepository;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static com.carenexus.api.core.appointments.AppointmentFixtures.clinicDay;
import static com.carenexus.api.core.appointments.AppointmentFixtures.consultation;
import static com.carenexus.api.core.appointments.AppointmentFixtures.insert;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Content of the doctor schedule rows, the dashboard and the stats (API contract be-doctor-schedule sections 1.2,
 * 2.3, 2.7, 3.4): linked consultation on APPOINTMENT rows, the legacy layout with CONSULTATION rows, facility and
 * contact phone, database counts, and the list-read audit.
 */
class DoctorScheduleViewsIntegrationTest extends CommittedIntegrationTest {

    private static final String SCHEDULE = "/api/doctors/schedule";
    private static final String APPOINTMENTS = "/api/doctors/appointments";
    private static final ZoneId CLINIC = ZoneId.of("Africa/Harare");

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private ConsultationRepository consultationRepository;

    @Test
    void appointmentRowsCarryTheLinkedConsultationFacilityAndContactPhone() throws Exception {
        Integer facilityId = jdbc.queryForObject("INSERT INTO facilities (name, address, is_active) "
                + "VALUES ('CareNexus Central Clinic', '12 Samora Machel Avenue, Harare', TRUE) RETURNING facility_id",
                Integer.class);
        LocalDateTime start = clinicNow().minusHours(2).withSecond(0).withNano(0);
        Appointment inPerson = insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(), start,
                "SCHEDULED", "IN_PERSON");
        jdbc.update("UPDATE appointments SET facility_id = ? WHERE appointment_id = ?", facilityId,
                inPerson.getAppointmentId());
        Consultation cancelled = consultation(consultationRepository, inPerson, "CANCELLED");
        Consultation active = consultation(consultationRepository, inPerson, "IN_PROGRESS");

        Appointment phone = insert(appointmentRepository, otherPatient.getSystemId(), doctor.getSystemId(),
                clinicDay(2, 11, 0), "SCHEDULED", "PHONE");
        jdbc.update("UPDATE appointments SET contact_phone = '+263771234567' WHERE appointment_id = ?",
                phone.getAppointmentId());

        JsonNode rows = json(doGet(APPOINTMENTS + "/search?order=asc", doctor)).path("content");
        assertEquals(2, rows.size());
        JsonNode first = rows.get(0);
        assertEquals(inPerson.getAppointmentId().intValue(), first.path("appointmentId").asInt());
        assertEquals(active.getConsultationId().intValue(), first.path("consultationId").asInt());
        assertEquals("IN_PROGRESS", first.path("consultationStatus").asText());
        assertEquals("CareNexus Central Clinic", first.path("facilityName").asText());
        assertTrue(first.path("contactPhone").isNull());
        assertTrue(first.path("reminderSentAt").isNull());
        assertTrue(first.path("videoConsultationLink").isNull());
        assertEquals("PATIENT User1", first.path("patientName").asText());
        // Started 2 hours ago, 30 minutes long, default threshold 30 minutes.
        assertTrue(first.path("needsOutcome").asBoolean());

        JsonNode second = rows.get(1);
        assertEquals("+263771234567", second.path("contactPhone").asText());
        assertTrue(second.path("consultationId").isNull());
        assertTrue(second.path("consultationStatus").isNull());
        assertTrue(second.path("facilityName").isNull());
        assertFalse(second.path("needsOutcome").asBoolean());

        // Only cancelled consultations: no linked consultation.
        jdbc.update("UPDATE consultations SET status = 'CANCELLED' WHERE consultation_id = ?", active.getConsultationId());
        JsonNode afterCancel = json(doGet(APPOINTMENTS + "/search?order=asc", doctor)).path("content").get(0);
        assertTrue(afterCancel.path("consultationId").isNull());
        assertTrue(afterCancel.path("consultationStatus").isNull());

        // The legacy array puts every consultation (any status, oldest first) right after its appointment.
        JsonNode legacy = json(doGet(SCHEDULE, doctor));
        assertEquals(4, legacy.size());
        assertEquals("APT_" + phone.getAppointmentId(), legacy.get(0).path("entryId").asText());
        assertEquals("APT_" + inPerson.getAppointmentId(), legacy.get(1).path("entryId").asText());
        assertEquals("CON_" + cancelled.getConsultationId(), legacy.get(2).path("entryId").asText());
        assertEquals("CON_" + active.getConsultationId(), legacy.get(3).path("entryId").asText());
        JsonNode consultationRow = legacy.get(3);
        assertEquals("CONSULTATION", consultationRow.path("type").asText());
        assertEquals(inPerson.getAppointmentId().intValue(), consultationRow.path("appointmentId").asInt());
        assertEquals(legacy.get(1).path("scheduledAt").asText(), consultationRow.path("scheduledAt").asText());
        assertFalse(consultationRow.path("needsOutcome").asBoolean());
        assertTrue(consultationRow.path("appointmentStatus").isNull());
        assertTrue(consultationRow.path("facilityName").isNull());
    }

    @Test
    void dashboardCountsComeFromTheDatabaseAndTheUpcomingListStopsAtTen() throws Exception {
        for (int i = 0; i < 12; i++) {
            insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(), clinicDay(2 + i, 10, 0),
                    i % 2 == 0 ? "SCHEDULED" : "RESCHEDULED", "IN_PERSON");
        }
        LocalDateTime now = clinicNow().withSecond(0).withNano(0);
        Appointment done = insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(),
                now.minusDays(3), "COMPLETED", "IN_PERSON");
        consultation(consultationRepository, done, "COMPLETED");
        Appointment open = insert(appointmentRepository, otherPatient.getSystemId(), doctor.getSystemId(),
                now.minusDays(4), "SCHEDULED", "IN_PERSON");
        consultation(consultationRepository, open, "DRAFT");
        insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(), now.minusDays(5), "CANCELLED", "IN_PERSON");
        insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(), now.minusDays(6), "NO_SHOW", "IN_PERSON");
        insert(appointmentRepository, patient.getSystemId(), otherDoctor.getSystemId(), clinicDay(3, 15, 0),
                "SCHEDULED", "IN_PERSON");

        MvcResult result = doGet(SCHEDULE + "/dashboard", doctor);
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode dashboard = json(result);
        assertEquals(doctor.getSystemId(), dashboard.path("doctorSystemId").asText());
        assertEquals(16, dashboard.path("totalAppointments").asInt());
        assertEquals(12, dashboard.path("upcomingAppointments").asInt());
        assertEquals(1, dashboard.path("needsOutcomeCount").asInt());
        assertEquals(1, dashboard.path("completedConsultations").asInt());
        assertEquals(1, dashboard.path("pendingConsultations").asInt());
        JsonNode upcoming = dashboard.path("upcomingSchedule");
        assertEquals(10, upcoming.size());
        assertEquals(clinicDay(2, 10, 0), LocalDateTime.parse(upcoming.get(0).path("scheduledAt").asText()));
        assertTrue(dashboard.path("todaySchedule").isArray());

        JsonNode stats = json(doGet(APPOINTMENTS + "/stats", doctor));
        assertEquals(16, stats.path("totalAppointments").asInt());
        assertEquals(12, stats.path("scheduledCount").asInt());
        assertEquals(1, stats.path("completedCount").asInt());
        assertEquals(1, stats.path("cancelledCount").asInt());
        assertEquals(1, stats.path("noShowCount").asInt());
        assertEquals(6, stats.path("rescheduledCount").asInt());
        assertEquals(100.0 / 3, stats.path("completionRate").asDouble(), 1e-9);
        assertEquals(100.0 / 16, stats.path("noShowRate").asDouble(), 1e-9);
        assertEquals(1, stats.path("needsOutcomeCount").asInt());
    }

    @Test
    void todayScheduleHoldsEveryStatusOfTheClinicDay() throws Exception {
        LocalDateTime now = clinicNow();
        LocalDateTime dayStart = now.toLocalDate().atStartOfDay();
        Appointment early = insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(), dayStart,
                "CANCELLED", "IN_PERSON");
        Appointment late = insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(),
                dayStart.plusHours(23).plusMinutes(55), "SCHEDULED", "IN_PERSON");
        insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(), dayStart.plusDays(1),
                "SCHEDULED", "IN_PERSON");
        insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(), dayStart.minusMinutes(5),
                "COMPLETED", "IN_PERSON");

        JsonNode today = json(doGet(SCHEDULE + "/dashboard", doctor)).path("todaySchedule");
        assertEquals(2, today.size());
        assertEquals(late.getAppointmentId().intValue(), today.get(0).path("appointmentId").asInt());
        assertEquals(early.getAppointmentId().intValue(), today.get(1).path("appointmentId").asInt());
    }

    @Test
    void listReadsAreAuditedOncePerPatientAndStatsAreNot() throws Exception {
        insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(), clinicDay(2, 10, 0),
                "SCHEDULED", "IN_PERSON");
        insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(), clinicDay(3, 10, 0),
                "SCHEDULED", "IN_PERSON");
        insert(appointmentRepository, otherPatient.getSystemId(), doctor.getSystemId(), clinicDay(4, 10, 0),
                "SCHEDULED", "IN_PERSON");

        int before = readRows();
        json(doGet(APPOINTMENTS + "/search", doctor));
        assertEquals(before + 2, readRows());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'READ' "
                + "AND resource_type = 'APPOINTMENT' AND patient_system_id = ? AND system_id = ?", Integer.class,
                otherPatient.getSystemId(), doctor.getSystemId()));

        before = readRows();
        json(doGet(APPOINTMENTS + "/stats", doctor));
        assertEquals(before, readRows());

        before = readRows();
        json(doGet(APPOINTMENTS + "/needs-outcome", doctor));
        assertEquals(before, readRows(), "an empty result writes nothing");

        before = readRows();
        json(doGet(SCHEDULE + "/window?from=2000-01-01T00:00:00&to=2100-01-01T00:00:00", doctor));
        assertEquals(before + 2, readRows());

        before = readRows();
        json(doGet(SCHEDULE, doctor));
        assertEquals(before + 2, readRows());
    }

    private int readRows() {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'READ' "
                + "AND resource_type = 'APPOINTMENT'", Integer.class);
        return value == null ? 0 : value;
    }

    private static LocalDateTime clinicNow() {
        return LocalDateTime.now(CLINIC);
    }
}
