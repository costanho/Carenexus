package com.carenexus.api.careplans;

import com.carenexus.api.BaseIntegrationTest;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.core.model.Appointment;
import com.carenexus.api.core.model.CareTeam;
import com.carenexus.api.core.model.Consultation;
import com.carenexus.api.core.repository.AppointmentRepository;
import com.carenexus.api.core.repository.ConsultationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.carenexus.api.careplans.CarePlanFixtures.body;
import static com.carenexus.api.careplans.CarePlanFixtures.createBody;
import static com.carenexus.api.careplans.CarePlanFixtures.goal;
import static com.carenexus.api.careplans.CarePlanFixtures.intervention;
import static com.carenexus.api.careplans.CarePlanFixtures.updateBody;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Request validation of the care-plan endpoints: the exact 400 texts the web client and the contract rely on,
 * consultation linking, trimming, and the 401 for an anonymous caller. Runs in a rolled-back test transaction.
 */
class CarePlanValidationTest extends BaseIntegrationTest {

    private static final String BASE = "/api/care-plans";

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private ConsultationRepository consultationRepository;

    private User patient;
    private User otherPatient;
    private User doctor;

    @BeforeEach
    void careCircle() {
        patient = createAndSaveUser(1, "PAT-00001", "cp.patient@example.com", "PATIENT");
        otherPatient = createAndSaveUser(2, "PAT-00002", "cp.other@example.com", "PATIENT");
        doctor = createAndSaveUser(3, "DOC-00001", "cp.doctor@example.com", "DOCTOR");
        careTeamRepository.saveAndFlush(CareTeam.builder()
                .patientSystemId(patient.getSystemId())
                .providerSystemId(doctor.getSystemId())
                .role("PRIMARY_DOCTOR")
                .assignedAt(LocalDateTime.now())
                .isActive(true)
                .build());
    }

    @Test
    void create_fieldRules_produceTheContractTexts() throws Exception {
        expect(send(post(BASE), doctor, null), 400, "Malformed request body", null);

        Map<String, Object> missingPatient = createBody(patient.getSystemId());
        missingPatient.remove("patientSystemId");
        expectValidation(send(post(BASE), doctor, missingPatient), "patientSystemId: is required");
        expectValidation(send(post(BASE), doctor, with(createBody(""), "title", "x")), "patientSystemId: is required");
        expectValidation(send(post(BASE), doctor, createBody("patient-1")), "patientSystemId: must be a valid system id");

        expectValidation(send(post(BASE), doctor, with(createBody(patient.getSystemId()), "title", "x".repeat(256))),
                "title: must be at most 255 characters");
        expectValidation(send(post(BASE), doctor,
                        with(createBody(patient.getSystemId()), "description", "x".repeat(4001))),
                "description: must be at most 4000 characters");
        expectValidation(send(post(BASE), doctor, with(createBody(patient.getSystemId()), "startDate", null)),
                "startDate: is required");
        expectValidation(send(post(BASE), doctor, with(createBody(patient.getSystemId()), "consultationId", 0)),
                "consultationId: must be a positive number");
        expect(send(post(BASE), doctor, with(createBody(patient.getSystemId()), "startDate", "2026-11-10T09:00")),
                400, "Malformed request body", null);
        expect(send(post(BASE), doctor, with(createBody(patient.getSystemId()), "targetDate", "next week")),
                400, "Malformed request body", null);

        expectValidation(send(post(BASE), doctor,
                        with(createBody(patient.getSystemId()), "goals", Arrays.asList(goal("ok", null), null))),
                "goals[1]: must not be null");
        expectValidation(send(post(BASE), doctor,
                        with(createBody(patient.getSystemId()), "goals", List.of(goal(" ", null)))),
                "goals[0].description: is required");
        expectValidation(send(post(BASE), doctor,
                        with(createBody(patient.getSystemId()), "goals", List.of(goal("g".repeat(501), null)))),
                "goals[0].description: must be at most 500 characters");
        expectValidation(send(post(BASE), doctor, with(createBody(patient.getSystemId()), "interventions",
                        List.of(intervention("ok", "f".repeat(101), null)))),
                "interventions[0].frequency: must be at most 100 characters");
        expectValidation(send(post(BASE), doctor, with(createBody(patient.getSystemId()), "interventions",
                        List.of(intervention("ok", null, "r".repeat(101))))),
                "interventions[0].responsible: must be at most 100 characters");
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            many.add(intervention("Step " + i, null, null));
        }
        expectValidation(send(post(BASE), doctor, with(createBody(patient.getSystemId()), "interventions", many)),
                "interventions: must contain at most 20 items");

        JsonNode error = json(send(post(BASE), doctor,
                with(with(createBody(patient.getSystemId()), "title", " "), "goals", List.of(goal("", null)))));
        assertEquals(2, error.path("errors").size(), error.toString());
    }

    @Test
    void create_linksAConsultationOfThePatientOnly_andTrimsText() throws Exception {
        Consultation own = consultation(patient);
        Consultation foreign = consultation(otherPatient);

        expect(send(post(BASE), doctor,
                with(createBody(patient.getSystemId()), "consultationId", foreign.getConsultationId())),
                400, "Consultation does not belong to this patient", null);
        expect(send(post(BASE), doctor, with(createBody(patient.getSystemId()), "consultationId", 999999)),
                404, "Consultation not found", null);

        Map<String, Object> request = with(createBody(patient.getSystemId()), "consultationId", own.getConsultationId());
        request.put("title", "  Asthma action plan  ");
        request.put("description", "   ");
        request.put("doctorSystemId", "DOC-09999");
        request.put("status", "COMPLETED");
        request.put("goals", List.of(body("description", " Use the preventer inhaler daily ", "status", "ACHIEVED",
                "progressNote", "ignored")));
        request.put("interventions", List.of(intervention("Inhaler technique review", " ", " Nurse ")));
        MvcResult created = send(post(BASE), doctor, request);
        assertEquals(201, created.getResponse().getStatus(), created.getResponse().getContentAsString());
        JsonNode plan = json(created);
        assertEquals(own.getConsultationId().intValue(), plan.path("consultationId").asInt());
        assertEquals(doctor.getSystemId(), plan.path("doctorSystemId").asText());
        assertEquals("ACTIVE", plan.path("status").asText());
        assertEquals("Asthma action plan", plan.path("title").asText());
        assertTrue(plan.path("description").isNull());
        assertEquals("Use the preventer inhaler daily", plan.path("goals").get(0).path("description").asText());
        assertEquals("OPEN", plan.path("goals").get(0).path("status").asText());
        assertTrue(plan.path("goals").get(0).path("progressNote").isNull());
        assertTrue(plan.path("interventions").get(0).path("frequency").isNull());
        assertEquals("Nurse", plan.path("interventions").get(0).path("responsible").asText());

        JsonNode noChildren = json(send(post(BASE), doctor,
                with(with(createBody(patient.getSystemId()), "goals", null), "interventions", null)));
        assertEquals(0, noChildren.path("goals").size());
        assertEquals(0, noChildren.path("interventions").size());
    }

    @Test
    void writeBodies_fieldRules_produceTheContractTexts() throws Exception {
        JsonNode plan = json(send(post(BASE), doctor, createBody(patient.getSystemId())));
        int planId = plan.path("planId").asInt();
        int goalId = plan.path("goals").get(0).path("goalId").asInt();

        expectValidation(send(put(BASE + "/" + planId), doctor, updateBody("T", List.of(), null)),
                "interventions: is required");
        expectValidation(send(put(BASE + "/" + planId), doctor,
                        with(updateBody("T", List.of(), List.of()), "startDate", null)), "startDate: is required");
        expectValidation(send(put(BASE + "/" + planId), doctor,
                        with(updateBody("T", List.of(), List.of()), "targetDate",
                                CarePlanFixtures.today().minusDays(1).toString())),
                "targetDate: must not be before startDate");
        expect(send(put(BASE + "/abc"), doctor, updateBody("T", List.of(), List.of())), 400,
                "Invalid value for parameter 'id'", null);

        expect(send(patch(BASE + "/" + planId + "/status"), doctor, null), 400, "Malformed request body", null);
        expectValidation(send(patch(BASE + "/" + planId + "/status"), doctor, body()), "status: is required");

        expect(send(patch(BASE + "/" + planId + "/review"), doctor, null), 400, "Malformed request body", null);
        expectValidation(send(patch(BASE + "/" + planId + "/review"), doctor, body("note", "n".repeat(1001))),
                "note: must be at most 1000 characters");

        String goalUrl = BASE + "/" + planId + "/goals/" + goalId;
        expectValidation(send(patch(goalUrl), patient, body("progressNote", "n".repeat(1001))),
                "progressNote: must be at most 1000 characters");
        expectValidation(send(patch(goalUrl), patient, body("status", "")),
                "status: must be one of OPEN, IN_PROGRESS, ACHIEVED, DROPPED");
        expect(send(patch(BASE + "/" + planId + "/goals/x"), patient, body("status", "OPEN")), 400,
                "Invalid value for parameter 'goalId'", null);
        // a progress note and the same status: the note is applied
        JsonNode same = json(send(patch(goalUrl), patient, body("status", "OPEN", "progressNote", "Noted")));
        assertEquals("OPEN", same.path("goals").get(0).path("status").asText());
        assertEquals("Noted", same.path("goals").get(0).path("progressNote").asText());
    }

    @Test
    void anonymousCallers_get401() throws Exception {
        MvcResult result = mockMvc.perform(get(BASE + "/doctor")).andReturn();
        assertEquals(401, result.getResponse().getStatus());
        assertEquals("Authentication required", json(result).path("message").asText());
    }

    // ==================== HELPERS ====================

    private Consultation consultation(User forPatient) {
        Appointment appointment = appointmentRepository.saveAndFlush(Appointment.builder()
                .patientSystemId(forPatient.getSystemId()).doctorSystemId(doctor.getSystemId())
                .scheduledAt(LocalDateTime.now().minusDays(2)).type("IN_PERSON").status("COMPLETED").build());
        return consultationRepository.saveAndFlush(Consultation.builder()
                .appointmentId(appointment.getAppointmentId()).patientSystemId(forPatient.getSystemId())
                .doctorSystemId(doctor.getSystemId()).chiefComplaint("Follow-up").status("COMPLETED").build());
    }

    private static Map<String, Object> with(Map<String, Object> body, String key, Object value) {
        body.put(key, value);
        return body;
    }

    private MvcResult send(MockHttpServletRequestBuilder builder, User as, Object body) throws Exception {
        builder.header("Authorization", bearerToken(generateToken(as.getUserId(), as.getSystemId(), as.getEmail(),
                as.getRole())));
        builder.contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            builder.content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(builder).andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void expect(MvcResult result, int status, String message, String code) throws Exception {
        assertEquals(status, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode error = json(result);
        assertEquals(message, error.path("message").asText());
        if (code != null) {
            assertEquals(code, error.path("code").asText());
        }
    }

    private void expectValidation(MvcResult result, String message) throws Exception {
        expect(result, 400, message, "VALIDATION_FAILED");
    }
}
