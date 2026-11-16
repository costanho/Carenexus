package com.carenexus.api.core.appointments;

import com.carenexus.api.calls.provider.CallProvider;
import com.carenexus.api.calls.provider.DisabledCallProvider;
import com.carenexus.api.common.spi.BookingConstraint;
import com.carenexus.api.core.model.Appointment;
import com.carenexus.api.core.model.Consultation;
import com.carenexus.api.core.repository.AppointmentRepository;
import com.carenexus.api.core.repository.ConsultationRepository;
import com.carenexus.api.core.service.AppointmentService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixtures of the appointment tests: request bodies, clinic times, rows inserted without the booking rules (for
 * appointments that have already started), and swapping AppointmentService collaborators (call provider, booking
 * constraints) for one test without starting another Spring context.
 */
public final class AppointmentFixtures {

    public static final ZoneId CLINIC_ZONE = ZoneId.of("Africa/Harare");
    public static final DateTimeFormatter WALL_CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private AppointmentFixtures() {
    }

    /** Clinic wall-clock time {@code daysAhead} days from today at hour:minute. */
    public static LocalDateTime clinicDay(int daysAhead, int hour, int minute) {
        return LocalDate.now(CLINIC_ZONE).plusDays(daysAhead).atTime(hour, minute);
    }

    public static String wallClock(LocalDateTime value) {
        return value.format(WALL_CLOCK);
    }

    /** A booking body; null values are sent as JSON null, so callers can omit a field by removing it. */
    public static Map<String, Object> booking(String patientSystemId, String doctorSystemId, LocalDateTime at, String type) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (patientSystemId != null) {
            body.put("patientSystemId", patientSystemId);
        }
        if (doctorSystemId != null) {
            body.put("doctorSystemId", doctorSystemId);
        }
        if (at != null) {
            body.put("scheduledAt", wallClock(at));
        }
        body.put("durationMinutes", 30);
        if (type != null) {
            body.put("type", type);
        }
        body.put("reasonForVisit", "Follow-up on blood pressure");
        return body;
    }

    /** Inserts an appointment row directly (no booking rules), for example one that started in the past. */
    public static Appointment insert(AppointmentRepository repository, String patientSystemId, String doctorSystemId,
                                     LocalDateTime at, String status, String type) {
        return repository.saveAndFlush(Appointment.builder()
                .patientSystemId(patientSystemId)
                .doctorSystemId(doctorSystemId)
                .scheduledAt(at)
                .durationMinutes(30)
                .type(type)
                .status(status)
                .reminderSent(false)
                .build());
    }

    /** A consultation of the appointment in the given status (consultations is read natively by the service). */
    public static Consultation consultation(ConsultationRepository repository, Appointment appointment, String status) {
        return repository.saveAndFlush(Consultation.builder()
                .appointmentId(appointment.getAppointmentId())
                .patientSystemId(appointment.getPatientSystemId())
                .doctorSystemId(appointment.getDoctorSystemId())
                .status(status)
                .followUpRequired(false)
                .createdAt(LocalDateTime.now())
                .completedAt("COMPLETED".equals(status) ? LocalDateTime.now() : null)
                .build());
    }

    /**
     * Runs {@code body} with AppointmentService using a call provider that is not configured (video unavailable),
     * then puts the original provider back.
     */
    public static void withVideoUnavailable(AppointmentService service, ThrowingRunnable body) throws Exception {
        Object target = AopTestUtils.getUltimateTargetObject(service);
        CallProvider original = (CallProvider) ReflectionTestUtils.getField(target, "callProvider");
        ReflectionTestUtils.setField(target, "callProvider", new DisabledCallProvider());
        try {
            body.run();
        } finally {
            ReflectionTestUtils.setField(target, "callProvider", original);
        }
    }

    /** Runs {@code body} with exactly these BookingConstraint beans, then restores the real ones. */
    public static void withBookingConstraints(AppointmentService service, List<BookingConstraint> constraints,
                                              ThrowingRunnable body) throws Exception {
        Object target = AopTestUtils.getUltimateTargetObject(service);
        Object original = ReflectionTestUtils.getField(target, "bookingConstraints");
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        for (int i = 0; i < constraints.size(); i++) {
            factory.addBean("testConstraint" + i, constraints.get(i));
        }
        ObjectProvider<BookingConstraint> provider = factory.getBeanProvider(BookingConstraint.class);
        ReflectionTestUtils.setField(target, "bookingConstraints", provider);
        try {
            body.run();
        } finally {
            ReflectionTestUtils.setField(target, "bookingConstraints", original);
        }
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** Convenience for building request bodies inline. */
    public static Map<String, Object> body(Object... keyValues) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            body.put((String) keyValues[i], keyValues[i + 1]);
        }
        return body;
    }
}
