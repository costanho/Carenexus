package com.carenexus.api.releasegates;

import com.carenexus.api.BaseIntegrationTest;
import com.carenexus.api.ai.detection.service.ScreeningDispatcher;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.calls.service.CallJobService;
import com.carenexus.api.common.config.FakeCallProviderConfig.FakeCallProvider;
import com.carenexus.api.common.util.ReferenceTableKeeper;
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
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Release-gate tests run WITHOUT a test transaction: every request commits, so the AFTER_COMMIT / BEFORE_COMMIT
 * event listeners (caregiver revocation cleanup, appointment -> call session sync) really run, exactly as in
 * production.
 *
 * Every table except Flyway's history is truncated before and after each test. The reference tables in
 * {@link #REFERENCE_TABLES} are then put back to the rows their migrations shipped, so each test starts with them
 * and never sees reference rows another test added.
 *
 * Fixture users have fixed system ids (PAT-00001, DOC-00001, ...). Accounts created through the API during a test
 * get the next free id: SystemIdGenerator skips ids that are already taken.
 *
 * Slice-specific fixtures belong in the slice's own test support class, not here.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public abstract class CommittedIntegrationTest extends BaseIntegrationTest {

    /**
     * Tables whose rows are shipped by migrations and must survive the truncation (parent tables first).
     * A table that does not exist in the schema is ignored. system_settings is deliberately not listed: no
     * migration ships rows for it, so it is truncated like any other table and a setting changed by one test
     * never reaches the next.
     */
    protected static final List<String> REFERENCE_TABLES = List.of("legal_documents", "billing_fee_schedule");

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected FakeCallProvider fakeCallProvider;

    @Autowired
    protected CallJobService callJobService;

    @Autowired
    private ScreeningDispatcher screeningDispatcher;

    /** A small care circle: patient Thomas, his doctor Amara, caregiver Sarah, plus unrelated users. */
    protected User patient;
    protected User doctor;
    protected User caregiver;
    protected User otherPatient;
    protected User otherDoctor;
    protected User otherCaregiver;
    protected User admin;

    @BeforeEach
    protected void createCareCircle() {
        awaitBackgroundWork();
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
    protected void cleanDatabase() {
        awaitBackgroundWork();
        truncateAll();
        clearAuthentication();
    }

    private void truncateAll() {
        ReferenceTableKeeper referenceTables = new ReferenceTableKeeper(jdbc, REFERENCE_TABLES);
        // First call in this database: the reference tables hold exactly what the migrations inserted.
        referenceTables.snapshotIfAbsent();
        List<String> tables = jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'",
                String.class).stream()
                .filter(table -> !ReferenceTableKeeper.isSnapshotTable(table))
                .toList();
        if (!tables.isEmpty()) {
            jdbc.execute("TRUNCATE TABLE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE");
        }
        // CASCADE also empties reference tables that point at users; put the shipped rows back.
        referenceTables.restore();
        restartSystemIdSequences();
    }

    /**
     * RESTART IDENTITY only covers sequences owned by a column. The system id sequences (V23) are standalone, so
     * they are restarted here: every test starts from the same state, whatever ran before it in this JVM.
     * SystemIdGenerator skips ids that are in use, so with the care circle in place the first account created
     * through the API is always PAT-00003, DOC-00003, CGV-00003 or ADM-00002.
     */
    private void restartSystemIdSequences() {
        List<String> sequences = jdbc.queryForList(
                "SELECT sequencename FROM pg_sequences WHERE schemaname = 'public' "
                        + "AND sequencename ~ '^system_id_[a-z]+_seq$'", String.class);
        for (String sequence : sequences) {
            jdbc.execute("ALTER SEQUENCE " + sequence + " RESTART WITH 1");
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

    /**
     * An active caregiver grant with NO permissions (access level CUSTOM, no expiry), shaped by {@code customizer}
     * before it is saved, for example:
     * <pre>
     * grantAccess(caregiver, patient, a -&gt; a.setCanViewBilling(true));                           // one flag
     * grantAccess(caregiver, patient, a -&gt; a.setAccessLevel("FULL_ACCESS"));                     // everything
     * grantAccess(caregiver, patient, a -&gt; { a.setCanViewRecords(true);
     *                                        a.setExpiresAt(LocalDateTime.now().minusDays(1)); }); // expired
     * </pre>
     * An existing grant for the same caregiver and patient is reset to that baseline and re-used (the pair is unique).
     */
    protected DependentAccess grantAccess(User forCaregiver, User forPatient, Consumer<DependentAccess> customizer) {
        DependentAccess access = dependentAccessRepository
                .findByCaregiverSystemIdAndPatientSystemId(forCaregiver.getSystemId(), forPatient.getSystemId())
                .orElseGet(DependentAccess::new);
        Integer existingId = access.getAccessId();
        DependentAccess baseline = DependentAccess.builder()
                .accessId(existingId)
                .patientSystemId(forPatient.getSystemId())
                .caregiverSystemId(forCaregiver.getSystemId())
                .authorizedBySystemId(forPatient.getSystemId())
                .relationship("daughter")
                .accessLevel("CUSTOM")
                .authorizedDate(LocalDateTime.now())
                .isActive(true)
                .build();
        if (customizer != null) {
            customizer.accept(baseline);
        }
        return dependentAccessRepository.saveAndFlush(baseline);
    }

    // ==================== BACKGROUND WORK ====================

    /**
     * Waits until the work a committed change handed to a background pool has finished: the automatic disease-risk
     * screening runs after commit on ScreeningDispatcher's pool, not on the request thread. Every request sent through
     * {@link #call} waits for it, so a test reads the detection and its alert right after the request, as a client
     * does moments later. Tests that publish events directly call it after publishing.
     */
    protected void awaitBackgroundWork() {
        if (!screeningDispatcher.awaitIdle(Duration.ofSeconds(30))) {
            throw new AssertionError("Background risk screenings did not finish within 30 seconds");
        }
    }

    // ==================== HTTP HELPERS ====================

    protected String tokenFor(User user) {
        return bearerToken(generateToken(user.getUserId(), user.getSystemId(), user.getEmail(), user.getRole()));
    }

    protected MvcResult call(AbstractMockHttpServletRequestBuilder<?> builder, User as, Object body) throws Exception {
        builder.header("Authorization", tokenFor(as));
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        MvcResult result = mockMvc.perform(builder).andReturn();
        awaitBackgroundWork();
        return result;
    }

    protected MvcResult doGet(String url, User as) throws Exception {
        return call(get(url), as, null);
    }

    protected MvcResult doPost(String url, User as, Object body) throws Exception {
        return call(post(url), as, body);
    }

    protected MvcResult doPut(String url, User as, Object body) throws Exception {
        return call(put(url), as, body);
    }

    /** POST with extra request headers, for example {@code Map.of("Idempotency-Key", clientId())}. */
    protected MvcResult doPostWithHeaders(String url, User as, Object body, Map<String, String> headers) throws Exception {
        MockHttpServletRequestBuilder builder = post(url);
        if (headers != null) {
            headers.forEach(builder::header);
        }
        return call(builder, as, body);
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
