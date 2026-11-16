package com.carenexus.api.core.consultations;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.core.model.Appointment;
import com.carenexus.api.core.model.Consultation;
import com.carenexus.api.core.repository.AppointmentRepository;
import com.carenexus.api.core.repository.ConsultationRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test data for consultation tests: appointments and consultations written straight through the repositories (no
 * events, no service rules), with times on the clinic clock.
 *
 * Appointment times are spread 5 minutes apart so the V13 one-active-appointment-per-slot indexes never collide,
 * whatever the number of appointments a test creates.
 */
public class ConsultationFixtures {

    private final AppointmentRepository appointmentRepository;
    private final ConsultationRepository consultationRepository;
    private final Clock clock;
    private final AtomicInteger slot = new AtomicInteger();

    public ConsultationFixtures(AppointmentRepository appointmentRepository,
                                ConsultationRepository consultationRepository, Clock clock) {
        this.appointmentRepository = appointmentRepository;
        this.consultationRepository = consultationRepository;
        this.clock = clock;
    }

    /** Wall-clock time in the clinic zone, the clock the consultation rules use. */
    public LocalDateTime clinicNow() {
        return LocalDateTime.now(clock);
    }

    /** A SCHEDULED appointment between the two users that started a little while ago. */
    public Appointment startedAppointment(User patient, User doctor) {
        return appointment(patient, doctor, "SCHEDULED", pastSlot());
    }

    /** An appointment in the given status that started a little while ago. */
    public Appointment startedAppointment(User patient, User doctor, String status) {
        return appointment(patient, doctor, status, pastSlot());
    }

    /** A SCHEDULED appointment tomorrow (not started yet). */
    public Appointment futureAppointment(User patient, User doctor) {
        return appointment(patient, doctor, "SCHEDULED",
                clinicNow().plusDays(1).withSecond(0).withNano(0).plusMinutes(5L * slot.incrementAndGet()));
    }

    public Appointment appointment(User patient, User doctor, String status, LocalDateTime scheduledAt) {
        return appointmentRepository.saveAndFlush(Appointment.builder()
                .patientSystemId(patient.getSystemId())
                .doctorSystemId(doctor.getSystemId())
                .scheduledAt(scheduledAt)
                .durationMinutes(30)
                .type("IN_PERSON")
                .status(status)
                .build());
    }

    /** A consultation row in the given status with a chief complaint and a diagnosis, for its own new appointment. */
    public Consultation consultation(User patient, User doctor, String status) {
        return consultation(startedAppointment(patient, doctor), status, "Persistent cough", "Post-viral cough");
    }

    public Consultation consultation(Appointment appointment, String status, String chiefComplaint, String diagnosis) {
        LocalDateTime now = clinicNow();
        return consultationRepository.saveAndFlush(Consultation.builder()
                .appointmentId(appointment.getAppointmentId())
                .patientSystemId(appointment.getPatientSystemId())
                .doctorSystemId(appointment.getDoctorSystemId())
                .chiefComplaint(chiefComplaint)
                .diagnosis(diagnosis)
                .status(status)
                .startedAt(Consultation.STATUS_IN_PROGRESS.equals(status) ? now : null)
                .completedAt(Consultation.STATUS_COMPLETED.equals(status) ? now : null)
                .createdAt(now)
                .build());
    }

    private LocalDateTime pastSlot() {
        return clinicNow().minusHours(1).withSecond(0).withNano(0).minusMinutes(5L * slot.incrementAndGet());
    }
}
