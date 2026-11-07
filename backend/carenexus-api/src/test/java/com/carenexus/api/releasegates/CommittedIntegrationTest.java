package com.carenexus.api.releasegates;

import com.carenexus.api.BaseIntegrationTest;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.calls.service.CallJobService;
import com.carenexus.api.common.config.FakeCallProviderConfig.FakeCallProvider;
import com.carenexus.api.core.model.CareTeam;
import com.carenexus.api.core.model.DependentAccess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Release-gate tests run WITHOUT a test transaction: every request commits, so the AFTER_COMMIT / BEFORE_COMMIT
 * event listeners (caregiver revocation cleanup, appointment -> call session sync) really run, exactly as in
 * production. Every table except Flyway's history is truncated after each test.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
abstract class CommittedIntegrationTest extends BaseIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected FakeCallProvider fakeCallProvider;

    @Autowired
    protected CallJobService callJobService;

    /** A small care circle: patient Thomas, his doctor Amara, caregiver Sarah, plus unrelated users. */
    protected User patient;
    protected User doctor;
    protected User caregiver;
    protected User otherPatient;
    protected User otherDoctor;
    protected User otherCaregiver;
    protected User admin;

    @BeforeEach
    void createCareCircle() {
        truncateAll();
        fakeCallProvider.reset();
        patient = createAndSaveUser(1, "PAT-00001", "thomas@example.com", "PATIENT");
        doctor = createAndSaveUser(2, "DOC-00001", "amara@example.com", "DOCTOR");
        caregiver = createAndSaveUser(3, "CGV-00001", "sarah@example.com", "CAREGIVER");
        otherPatient = createAndSaveUser(4, "PAT-00002", "mary@example.com", "PATIENT");
        otherDoctor = createAndSaveUser(5, "DOC-00002", "other.doc@example.com", "DOCTOR");
        otherCaregiver = createAndSaveUser(6, "CGV-00002", "other.cg@example.com", "CAREGIVER");
        admin = createAndSaveUser(7, "ADM-00001", "admin@example.com", "ADMIN");

        careTeamRepository.saveAndFlush(CareTeam.builder()
                .patientSystemId(patient.getSystemId())
                .providerSystemId(doctor.getSystemId())
                .role("PRIMARY_DOCTOR")
                .assignedAt(LocalDateTime.now())
                .isActive(true)
                .build());
        careTeamRepository.saveAndFlush(CareTeam.builder()
                .patientSystemId(otherPatient.getSystemId())
                .providerSystemId(otherDoctor.getSystemId())
                .role("PRIMARY_DOCTOR")
                .assignedAt(LocalDateTime.now())
                .isActive(true)
                .build());
    }

    @AfterEach
    void cleanDatabase() {
        truncateAll();
    }

    private void truncateAll() {
        List<String> tables = jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'",
                String.class);
        if (!tables.isEmpty()) {
            jdbc.execute("TRUNCATE TABLE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE");
        }
    }

    // ==================== FIXTURES ====================

    /** An active grant for the caregiver with messaging and calls approved by the patient. */
    protected DependentAccess grantApprovedAccess(User forCaregiver, User forPatient) {
        Instant now = Instant.now();
        return dependentAccessRepository.saveAndFlush(DependentAccess.builder()
                .patientSystemId(forPatient.getSystemId())
                .caregiverSystemId(forCaregiver.getSystemId())
                .authorizedBySystemId(forPatient.getSystemId())
                .relationship("daughter")
                .accessLevel("CUSTOM")
                .canViewAppointments(true)
                .canMessageCareTeam(true)
                .messagingApprovedAt(now)
                .messagingApprovedBySystemId(forPatient.getSystemId())
                .canJoinCalls(true)
                .callsApprovedAt(now)
                .callsApprovedBySystemId(forPatient.getSystemId())
                .authorizedDate(LocalDateTime.now())
                .isActive(true)
                .build());
    }

    // ==================== HTTP HELPERS ====================

    protected String tokenFor(User user) {
        return bearerToken(generateToken(user.getUserId(), user.getSystemId(), user.getEmail(), user.getRole()));
    }

    protected MvcResult call(MockHttpServletRequestBuilder builder, User as, Object body) throws Exception {
        builder.header("Authorization", tokenFor(as));
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(builder).andReturn();
    }

    protected MvcResult doGet(String url, User as) throws Exception {
        return call(get(url), as, null);
    }

    protected MvcResult doPost(String url, User as, Object body) throws Exception {
        return call(post(url), as, body);
    }

    protected MvcResult doPatch(String url, User as, Object body) throws Exception {
        return call(patch(url), as, body);
    }

    protected MvcResult doDelete(String url, User as) throws Exception {
        return call(delete(url), as, null);
    }

    protected JsonNode json(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? objectMapper.nullNode() : objectMapper.readTree(content);
    }

    protected String message(MvcResult result) throws Exception {
        return json(result).path("message").asText();
    }

    protected static String clientId() {
        return UUID.randomUUID().toString();
    }

    /** A 5-minute-aligned wall-clock time {@code minutesAhead} (rounded up) from now, in the clinic zone. */
    protected static LocalDateTime slotInMinutes(int minutesAhead) {
        LocalDateTime t = LocalDateTime.now().plusMinutes(minutesAhead).withSecond(0).withNano(0);
        int rem = t.getMinute() % 5;
        return rem == 0 ? t : t.plusMinutes(5 - rem);
    }
}
