package com.carenexus.api.core.appointments;

import com.carenexus.api.common.event.AppointmentCancelledEvent;
import com.carenexus.api.common.event.AppointmentStatusChangedEvent;
import com.carenexus.api.common.event.ConsultationCompletedEvent;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.spi.BookingConstraint;
import com.carenexus.api.common.spi.BookingRequestContext;
import com.carenexus.api.core.dto.response.AppointmentResponse;
import com.carenexus.api.core.model.Appointment;
import com.carenexus.api.core.repository.AppointmentRepository;
import com.carenexus.api.core.repository.ConsultationRepository;
import com.carenexus.api.core.service.AppointmentService;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.carenexus.api.core.appointments.AppointmentFixtures.CLINIC_ZONE;
import static com.carenexus.api.core.appointments.AppointmentFixtures.body;
import static com.carenexus.api.core.appointments.AppointmentFixtures.booking;
import static com.carenexus.api.core.appointments.AppointmentFixtures.clinicDay;
import static com.carenexus.api.core.appointments.AppointmentFixtures.consultation;
import static com.carenexus.api.core.appointments.AppointmentFixtures.insert;
import static com.carenexus.api.core.appointments.AppointmentFixtures.wallClock;
import static com.carenexus.api.core.appointments.AppointmentFixtures.withBookingConstraints;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Appointment and consultation consistency (listener on ConsultationCompletedEvent, cancelBySystem facade) and the
 * BookingConstraint extension point. be-consultations is built in parallel, so the listener is driven by publishing
 * the event from the test (CONTRACTS.v2 6.1 testing rule).
 */
@RecordApplicationEvents
class AppointmentConsistencyTest extends CommittedIntegrationTest {

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private ConsultationRepository consultationRepository;

    @Autowired
    private AppointmentService appointmentService;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ApplicationEvents events;

    private Appointment started(String status) {
        return insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(),
                LocalDateTime.now(CLINIC_ZONE).withSecond(0).withNano(0).minusMinutes(15), status, "IN_PERSON");
    }

    private String dbStatus(Appointment appointment) {
        return jdbc.queryForObject("SELECT status FROM appointments WHERE appointment_id = ?", String.class,
                appointment.getAppointmentId());
    }

    /** Publishes the event inside a transaction, as ConsultationService does when it completes a consultation. */
    private void consultationCompleted(Integer appointmentId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> publisher.publishEvent(
                new ConsultationCompletedEvent(77, appointmentId, patient.getSystemId(), doctor.getSystemId(),
                        false, null)));
    }

    // ==================== LISTENER ====================

    @Test
    void completedConsultationCompletesAStartedActiveAppointment() {
        Appointment appointment = started(AppointmentService.RESCHEDULED);

        consultationCompleted(appointment.getAppointmentId());

        assertEquals("COMPLETED", dbStatus(appointment));
        List<AppointmentStatusChangedEvent> changed = events.stream(AppointmentStatusChangedEvent.class).toList();
        assertEquals(1, changed.size());
        assertEquals("RESCHEDULED", changed.get(0).previousStatus());
        assertEquals("COMPLETED", changed.get(0).status());
        assertEquals(doctor.getSystemId(), changed.get(0).actorSystemId());
        Long audit = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE resource_type = 'APPOINTMENT' "
                + "AND action = 'UPDATE' AND resource_id = ?", Long.class, appointment.getAppointmentId());
        assertEquals(1L, audit);

        // A repeated event is a no-op, never an error.
        consultationCompleted(appointment.getAppointmentId());
        assertEquals(1, events.stream(AppointmentStatusChangedEvent.class).count());
    }

    @Test
    void listenerIgnoresAppointmentsThatDoNotQualify() {
        Appointment future = insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(),
                clinicDay(2, 10, 0), AppointmentService.SCHEDULED, "IN_PERSON");
        Appointment cancelled = started(AppointmentService.CANCELLED);
        Appointment noShow = insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(),
                LocalDateTime.now(CLINIC_ZONE).withSecond(0).withNano(0).minusHours(3), AppointmentService.NO_SHOW,
                "IN_PERSON");

        consultationCompleted(future.getAppointmentId());
        consultationCompleted(cancelled.getAppointmentId());
        consultationCompleted(noShow.getAppointmentId());
        consultationCompleted(null);
        consultationCompleted(999999);

        assertEquals("SCHEDULED", dbStatus(future));
        assertEquals("CANCELLED", dbStatus(cancelled));
        assertEquals("NO_SHOW", dbStatus(noShow));
        assertEquals(0, events.stream(AppointmentStatusChangedEvent.class).count());
    }

    // ==================== cancelBySystem ====================

    @Test
    void cancelBySystemCancelsActiveAppointmentsAndSkipsTheRest() {
        Appointment active = insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(),
                clinicDay(2, 10, 0), AppointmentService.SCHEDULED, "IN_PERSON");

        AppointmentResponse response = appointmentService.cancelBySystem(active.getAppointmentId(),
                "Your doctor is no longer available.");
        assertEquals("CANCELLED", response.getStatus());
        assertEquals("CANCELLED", dbStatus(active));
        assertNull(response.getContactPhone());
        assertEquals("PATIENT User1", response.getPatientName());
        List<AppointmentCancelledEvent> cancelled = events.stream(AppointmentCancelledEvent.class).toList();
        assertEquals(1, cancelled.size());
        assertTrue(cancelled.get(0).bySystem());
        assertNull(cancelled.get(0).actorSystemId());
        assertNull(cancelled.get(0).actorRole());
        assertEquals("Your doctor is no longer available.", cancelled.get(0).reason());

        // Terminal: unchanged, nothing published, no exception.
        AppointmentResponse again = appointmentService.cancelBySystem(active.getAppointmentId(), "Again");
        assertEquals("CANCELLED", again.getStatus());

        Appointment withConsultation = started(AppointmentService.SCHEDULED);
        consultation(consultationRepository, withConsultation, "COMPLETED");
        AppointmentResponse kept = appointmentService.cancelBySystem(withConsultation.getAppointmentId(), "Closed");
        assertEquals("SCHEDULED", kept.getStatus());
        assertEquals("SCHEDULED", dbStatus(withConsultation));
        assertEquals(1, events.stream(AppointmentCancelledEvent.class).count());

        assertThrows(ResourceNotFoundException.class, () -> appointmentService.cancelBySystem(999999, "x"));
    }

    @Test
    void toResponsesWithoutACallerHidesTheContactPhone() {
        Appointment phone = insert(appointmentRepository, patient.getSystemId(), doctor.getSystemId(),
                clinicDay(2, 10, 0), AppointmentService.SCHEDULED, "PHONE");
        phone.setContactPhone("+263771234567");
        appointmentRepository.saveAndFlush(phone);

        List<AppointmentResponse> responses = appointmentService.toResponses(List.of(phone));
        assertEquals(1, responses.size());
        assertNull(responses.get(0).getContactPhone());
        assertEquals("Dr. DOCTOR User2", responses.get(0).getDoctorName());

        authenticateAs(doctor);
        try {
            assertEquals("+263771234567", appointmentService.toResponses(List.of(phone)).get(0).getContactPhone());
        } finally {
            clearAuthentication();
        }
        authenticateAs(otherDoctor);
        try {
            assertNull(appointmentService.toResponses(List.of(phone)).get(0).getContactPhone());
        } finally {
            clearAuthentication();
        }
    }

    // ==================== BOOKING CONSTRAINTS ====================

    @Test
    void bookingConstraintsRunOnlyWhenTheSlotIsCreatedOrChanged() throws Exception {
        List<BookingRequestContext> seen = new CopyOnWriteArrayList<>();
        BookingConstraint recorder = seen::add;

        withBookingConstraints(appointmentService, List.of(recorder), () -> {
            MvcResult created = doPost("/api/appointments", patient,
                    booking(null, doctor.getSystemId(), clinicDay(2, 10, 0), "IN_PERSON"));
            assertEquals(201, created.getResponse().getStatus(), created.getResponse().getContentAsString());
            int id = json(created).path("appointmentId").asInt();
            assertEquals(1, seen.size());
            BookingRequestContext ctx = seen.get(0);
            assertEquals("PATIENT", ctx.actorRole());
            assertNull(ctx.appointmentId());
            assertEquals(clinicDay(2, 10, 0), ctx.start());
            assertEquals(30, ctx.durationMinutes());
            assertEquals("IN_PERSON", ctx.type());

            // A text-only edit runs no constraint.
            assertEquals(200, doPut("/api/appointments/" + id, patient, body("notes", "x")).getResponse().getStatus());
            assertEquals(1, seen.size());

            // A duration-only change and a move do.
            assertEquals(200, doPut("/api/appointments/" + id, doctor, body("durationMinutes", 45)).getResponse().getStatus());
            assertEquals(2, seen.size());
            assertEquals("DOCTOR", seen.get(1).actorRole());
            assertEquals(id, seen.get(1).appointmentId());
            assertEquals(200, doPatch("/api/appointments/" + id + "/reschedule", patient,
                    body("scheduledAt", wallClock(clinicDay(2, 14, 0)))).getResponse().getStatus());
            assertEquals(3, seen.size());
            assertEquals(45, seen.get(2).durationMinutes());
        });
    }

    @Test
    void aRefusingConstraintPropagatesAndNothingIsBooked() throws Exception {
        BookingConstraint refuse = ctx -> {
            if ("PATIENT".equals(ctx.actorRole())) {
                throw new BadRequestException("DOCTOR_UNAVAILABLE", "The doctor is not available at this time");
            }
        };
        withBookingConstraints(appointmentService, List.of(refuse), () -> {
            Map<String, Object> request = booking(null, doctor.getSystemId(), clinicDay(2, 10, 0), "IN_PERSON");
            MvcResult refused = doPost("/api/appointments", patient, request);
            assertEquals(400, refused.getResponse().getStatus());
            JsonNode error = json(refused);
            assertEquals("DOCTOR_UNAVAILABLE", error.path("code").asText());
            assertEquals("The doctor is not available at this time", error.path("message").asText());
            assertEquals(0L, appointmentRepository.count());

            MvcResult byDoctor = doPost("/api/appointments", doctor,
                    booking(patient.getSystemId(), null, clinicDay(2, 10, 0), "IN_PERSON"));
            assertEquals(201, byDoctor.getResponse().getStatus(), byDoctor.getResponse().getContentAsString());
        });
    }
}
