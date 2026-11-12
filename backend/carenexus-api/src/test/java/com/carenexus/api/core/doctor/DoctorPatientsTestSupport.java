package com.carenexus.api.core.doctor;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.core.model.Appointment;
import com.carenexus.api.core.model.CareTeam;
import com.carenexus.api.core.model.Consultation;
import com.carenexus.api.core.repository.AppointmentRepository;
import com.carenexus.api.core.repository.ConsultationRepository;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Fixtures for the doctor patient list, the doctor profile and the directory tests (committed transactions).
 *
 * Inherits the standard care circle: patient PAT-00001 is on doctor DOC-00001's care team (PRIMARY_DOCTOR),
 * otherPatient PAT-00002 on otherDoctor DOC-00002's.
 */
public abstract class DoctorPatientsTestSupport extends CommittedIntegrationTest {

    @Autowired
    protected AppointmentRepository appointmentRepository;

    @Autowired
    protected ConsultationRepository consultationRepository;

    @Autowired
    protected Clock clock;

    private int nextFixtureNumber = 100;

    /** Clinic wall-clock now, whole minutes. */
    protected LocalDateTime clinicNow() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES);
    }

    /** A patient account with the given names, gender (nullable) and health status. */
    protected User newPatient(String systemId, String firstName, String lastName, String gender, String healthStatus) {
        int n = nextFixtureNumber++;
        User user = createAndSaveUser(n, systemId, systemId.toLowerCase() + "@example.com", "PATIENT");
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user = userRepository.saveAndFlush(user);
        jdbc.update("UPDATE patients SET gender = ?, health_status = ? WHERE system_id = ?",
                gender, healthStatus, systemId);
        return user;
    }

    /** A doctor account with explicit verification state and both active flags. */
    protected User newDoctor(String systemId, String firstName, String lastName, String specialization,
                             String verificationStatus, boolean doctorActive, boolean userActive) {
        int n = nextFixtureNumber++;
        User user = createAndSaveUser(n, systemId, systemId.toLowerCase() + "@example.com", "DOCTOR");
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setIsActive(userActive);
        user = userRepository.saveAndFlush(user);
        jdbc.update("UPDATE doctors SET specialization = ?, verification_status = ?, is_active = ? WHERE system_id = ?",
                specialization, verificationStatus, doctorActive, systemId);
        return user;
    }

    protected Appointment appointment(User forPatient, User withDoctor, LocalDateTime at, String status, String type) {
        return appointmentRepository.saveAndFlush(Appointment.builder()
                .patientSystemId(forPatient.getSystemId())
                .doctorSystemId(withDoctor.getSystemId())
                .scheduledAt(at)
                .durationMinutes(30)
                .type(type)
                .status(status)
                .reasonForVisit("Follow-up")
                .build());
    }

    protected Appointment appointment(User forPatient, User withDoctor, LocalDateTime at, String status) {
        return appointment(forPatient, withDoctor, at, status, "IN_PERSON");
    }

    /** A consultation between the two, attached to {@code onAppointment}. */
    protected Consultation consultation(User forPatient, User byDoctor, Appointment onAppointment) {
        return consultationRepository.saveAndFlush(Consultation.builder()
                .appointmentId(onAppointment.getAppointmentId())
                .patientSystemId(forPatient.getSystemId())
                .doctorSystemId(byDoctor.getSystemId())
                .status("COMPLETED")
                .build());
    }

    protected CareTeam careTeam(User forPatient, User provider, String role, boolean active) {
        return careTeamRepository.saveAndFlush(CareTeam.builder()
                .patientSystemId(forPatient.getSystemId())
                .providerSystemId(provider.getSystemId())
                .role(role)
                .assignedAt(LocalDateTime.now())
                .isActive(active)
                .build());
    }

    /** GET with query parameters set literally (no URI template encoding): {@code key, value, key, value, ...}. */
    protected org.springframework.test.web.servlet.MvcResult getWithParams(String url, User as, String... keyValues)
            throws Exception {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder =
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url);
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            builder.param(keyValues[i], keyValues[i + 1]);
        }
        return call(builder, as, null);
    }

    protected long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    protected static List<String> ids(com.fasterxml.jackson.databind.JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.path(field).asText()));
        return values;
    }
}
