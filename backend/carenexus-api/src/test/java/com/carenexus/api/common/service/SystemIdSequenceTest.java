package com.carenexus.api.common.service;

import com.carenexus.api.auth.service.SystemIdGenerator;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * system_id allocation from the V23 sequences: parallel registrations never collide, ids already taken (fixtures,
 * seed rows) are skipped, and the PREFIX-%05d format is kept. Runs committed, like production.
 */
public class SystemIdSequenceTest extends CommittedIntegrationTest {

    private static final int PARALLEL = 8;

    @Autowired
    private SystemIdGenerator systemIdGenerator;

    @Test
    void parallelRegistrations_allSucceedWithDistinctSystemIds() throws Exception {
        String run = clientId().substring(0, 8);
        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (int i = 0; i < PARALLEL; i++) {
                Map<String, Object> body = Map.of(
                        "firstName", "Parallel",
                        "lastName", "Patient" + i,
                        "email", "parallel-" + run + "-" + i + "@example.com",
                        "password", "Str0ng-Passw0rd-" + i,
                        "role", "patient");
                Callable<MvcResult> task = () -> {
                    start.await(10, TimeUnit.SECONDS);
                    return mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body))).andReturn();
                };
                futures.add(pool.submit(task));
            }
            start.countDown();

            Set<String> systemIds = new HashSet<>();
            for (Future<MvcResult> future : futures) {
                MvcResult result = future.get(120, TimeUnit.SECONDS);
                assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
                JsonNode user = json(result).path("user");
                String systemId = user.path("system_id").asText();
                assertTrue(systemId.matches("^PAT-\\d{5}$"), systemId);
                assertTrue(systemIds.add(systemId), "duplicate system id " + systemId);
            }
            assertEquals(PARALLEL, systemIds.size());
            // The fixtures hold PAT-00001 and PAT-00002: a registration is never given an id that is in use.
            assertFalse(systemIds.contains(patient.getSystemId()));
            assertFalse(systemIds.contains(otherPatient.getSystemId()));
            assertEquals(PARALLEL + 2, jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE role = 'PATIENT'", Integer.class));
            assertEquals(PARALLEL + 2, jdbc.queryForObject("SELECT COUNT(*) FROM patients", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void idsAlreadyInUse_areSkipped_forEveryRole() {
        // Fixtures: PAT-00001/2, DOC-00001/2, CGV-00001/2, ADM-00001 were inserted with explicit ids.
        Set<String> taken = new HashSet<>(jdbc.queryForList("SELECT system_id FROM users", String.class));
        assertEquals(7, taken.size());

        for (String role : new String[]{"PATIENT", "DOCTOR", "CAREGIVER", "ADMIN"}) {
            String first = systemIdGenerator.generateSystemId(role);
            String second = systemIdGenerator.generate(role.toLowerCase());
            assertFalse(taken.contains(first), first + " is already in use");
            assertFalse(taken.contains(second), second + " is already in use");
            assertNotEquals(first, second);
        }
    }

    @Test
    void format_isPrefixDashFiveDigits_andGrowsBeyondFiveDigits() {
        assertTrue(systemIdGenerator.generateSystemId("PATIENT").matches("^PAT-\\d{5}$"));
        assertTrue(systemIdGenerator.generateSystemId("DOCTOR").matches("^DOC-\\d{5}$"));
        assertTrue(systemIdGenerator.generateSystemId("CAREGIVER").matches("^CGV-\\d{5}$"));
        assertTrue(systemIdGenerator.generateSystemId("ADMIN").matches("^ADM-\\d{5}$"));

        // Past 99,999 the id simply gets longer (the old string sort broke here). The base class restarts the
        // sequences before and after every test, so this does not leak into other tests.
        jdbc.queryForObject("SELECT setval('system_id_cgv_seq', 99999, true)", Long.class);
        assertEquals("CGV-100000", systemIdGenerator.generateSystemId("CAREGIVER"));
        assertEquals("CGV-100001", systemIdGenerator.generateSystemId("CAREGIVER"));
    }

    @Test
    void sequencesRestartForEveryTest_soTheFirstApiCreatedAccountHasAFixedId() throws Exception {
        // Whatever earlier tests in this JVM registered, the first ids after the fixtures are always the same.
        assertEquals("PAT-00003", systemIdGenerator.generateSystemId("PATIENT"));
        assertEquals("DOC-00003", systemIdGenerator.generateSystemId("DOCTOR"));
        assertEquals("CGV-00003", systemIdGenerator.generateSystemId("CAREGIVER"));
        assertEquals("ADM-00002", systemIdGenerator.generateSystemId("ADMIN"));

        // Ids are handed out once: the next account created through the API gets the next number.
        MvcResult registered = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("firstName", "Fixed", "lastName", "Id",
                        "email", "fixed-" + clientId().substring(0, 8) + "@example.com",
                        "password", "Str0ng-Passw0rd-1", "role", "patient")))).andReturn();
        assertEquals(201, registered.getResponse().getStatus(), registered.getResponse().getContentAsString());
        assertEquals("PAT-00004", json(registered).path("user").path("system_id").asText());
    }

    @Test
    void unknownOrBlankRole_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> systemIdGenerator.generateSystemId("NURSE"));
        assertThrows(IllegalArgumentException.class, () -> systemIdGenerator.generateSystemId(" "));
        assertThrows(IllegalArgumentException.class, () -> systemIdGenerator.generateSystemId(null));
    }

    @Test
    void selfRegisteredDoctor_startsPendingVerification_andPatientHasNoForcedPasswordChange() throws Exception {
        String run = clientId().substring(0, 8);
        MvcResult doctorResult = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("firstName", "New", "lastName", "Doctor",
                        "email", "doc-" + run + "@example.com", "password", "Str0ng-Passw0rd-1", "role", "doctor")))).andReturn();
        assertEquals(201, doctorResult.getResponse().getStatus(), doctorResult.getResponse().getContentAsString());
        String doctorId = json(doctorResult).path("user").path("system_id").asText();
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT verification_status FROM doctors WHERE system_id = ?", String.class, doctorId));

        // Fixture doctors are verified clinicians.
        assertEquals("APPROVED", jdbc.queryForObject(
                "SELECT verification_status FROM doctors WHERE system_id = ?", String.class, doctor.getSystemId()));
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
                "SELECT must_change_password FROM users WHERE system_id = ?", Boolean.class, doctorId));
        assertNotNull(jdbc.queryForObject(
                "SELECT email_verified_at FROM users WHERE system_id = ?", java.sql.Timestamp.class, patient.getSystemId()));
    }
}
