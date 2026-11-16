package com.carenexus.api.releasegates;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.releasegates.OpenApiCoverageTest.Op;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.AntPathMatcher;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Release gate for route security (CONTRACTS 3.8), driven by the OpenAPI document so a new endpoint is covered the
 * moment it is added:
 * <ul>
 *   <li>every operation that is not on the public list answers 401 from the filter chain without a token, and every
 *       public one is let through;</li>
 *   <li>{@code /api/admin/**}: 403 for patient, doctor and caregiver;</li>
 *   <li>{@code /api/doctors/**}: 403 for patient and caregiver, except the directory and public-profile reads;</li>
 *   <li>{@code /api/caregiver/**}: 403 for everyone but caregivers;</li>
 *   <li>{@code /api/patients/me/caregivers/**} and {@code /api/patients/me/caregiver-access/**}: 403 for doctor and
 *       caregiver;</li>
 *   <li>{@code /api/integrations/**}: closed except the two provider webhooks.</li>
 * </ul>
 * A refusal is recognised by the filter chain's own message, so a 401/403 a controller writes later (for example a
 * service-level ownership check) never counts as the route gate. A role that should pass the gate is asserted on
 * GET operations only, so the gate never runs a write with placeholder ids.
 */
@TestPropertySource(properties = "springdoc.api-docs.enabled=true")
class SecurityRouteReleaseGateTest extends CommittedIntegrationTest {

    private static final String FILTER_401 = "Authentication required";
    private static final String FILTER_403 = "You don't have permission to access this resource";
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /** Reads under /api/doctors that every signed-in user may call (booking directory and public profiles). */
    private static final List<String> DOCTOR_DIRECTORY = List.of("/api/doctors/profile/directory",
            "/api/doctors/profile/directory/**", "/api/doctors/profile/public/**", "/api/doctors/profile/list/active");

    private static final List<String> PATIENT_ONLY_CAREGIVER_MANAGEMENT = List.of("/api/patients/me/caregivers",
            "/api/patients/me/caregivers/**", "/api/patients/me/caregiver-access", "/api/patients/me/caregiver-access/**");

    private List<Op> operations;

    @BeforeEach
    void loadOperations() throws Exception {
        JsonNode docs = OpenApiCoverageTest.loadApiDocs(mockMvc, objectMapper);
        operations = OpenApiCoverageTest.operations(docs);
        assertTrue(operations.size() > 100, "the OpenAPI document lists only " + operations.size() + " operations");
    }

    // ==================== HELPERS ====================

    private MockHttpServletRequestBuilder requestFor(String method, String path) {
        MockHttpServletRequestBuilder builder = request(HttpMethod.valueOf(method), path);
        if (!"GET".equals(method) && !"DELETE".equals(method)) {
            builder.contentType(MediaType.APPLICATION_JSON).content("{}");
        }
        return builder;
    }

    private MvcResult anonymous(String method, String path) throws Exception {
        return mockMvc.perform(requestFor(method, path)).andReturn();
    }

    private MvcResult as(User user, String method, String path) throws Exception {
        return call(requestFor(method, path), user, null);
    }

    private boolean refusedByFilter(MvcResult result, int status) throws Exception {
        String expected = status == 401 ? FILTER_401 : FILTER_403;
        return result.getResponse().getStatus() == status && expected.equals(message(result));
    }

    private static boolean under(String path, List<String> patterns) {
        String concrete = OpenApiCoverageTest.concretePath(path);
        return patterns.stream().anyMatch(p -> MATCHER.match(p, concrete));
    }

    private List<Op> select(Predicate<Op> filter) {
        return operations.stream().filter(filter).toList();
    }

    /** Asserts that every operation is refused by the route gate (403) for each of the users. */
    private void assertRoleGate(String what, List<Op> ops, List<User> refused) throws Exception {
        assertFalse(ops.isEmpty(), "no operation found for " + what);
        Set<String> violations = new TreeSet<>();
        for (Op op : ops) {
            String path = OpenApiCoverageTest.concretePath(op.path());
            for (User user : refused) {
                MvcResult result = as(user, op.method(), path);
                if (!refusedByFilter(result, 403)) {
                    violations.add(op.key() + " as " + user.getRole() + ": " + result.getResponse().getStatus()
                            + " " + result.getResponse().getContentAsString());
                }
            }
        }
        failOn(what, violations);
    }

    /** Asserts that the route gate lets each user through on every GET operation of the list. */
    private void assertGateOpenForReads(String what, List<Op> ops, List<User> allowed) throws Exception {
        Set<String> violations = new TreeSet<>();
        for (Op op : ops) {
            if (!"GET".equals(op.method())) {
                continue;
            }
            String path = OpenApiCoverageTest.concretePath(op.path());
            for (User user : allowed) {
                MvcResult result = as(user, op.method(), path);
                if (refusedByFilter(result, 403) || refusedByFilter(result, 401)) {
                    violations.add(op.key() + " as " + user.getRole() + " was refused by the route gate");
                }
            }
        }
        failOn(what, violations);
    }

    private static void failOn(String what, Set<String> violations) {
        if (!violations.isEmpty()) {
            fail(violations.size() + " violations of " + what + ":\n  " + String.join("\n  ", violations));
        }
    }

    // ==================== AUTHENTICATION ====================

    @Test
    void everyNonPublicOperation_needsAToken_andPublicOnesAreOpen() throws Exception {
        Set<String> violations = new TreeSet<>();
        int publicCount = 0;
        for (Op op : operations) {
            String path = OpenApiCoverageTest.concretePath(op.path());
            MvcResult result = anonymous(op.method(), path);
            if (OpenApiCoverageTest.isPublic(op.method(), op.path())) {
                publicCount++;
                if (refusedByFilter(result, 401) || refusedByFilter(result, 403)) {
                    violations.add(op.key() + ": public route refused by the filter chain ("
                            + result.getResponse().getStatus() + ")");
                }
            } else if (!refusedByFilter(result, 401)) {
                violations.add(op.key() + ": answered " + result.getResponse().getStatus() + " without a token");
            }
        }
        failOn("the authentication rule", violations);
        assertTrue(publicCount >= 10, "expected the auth, legal and webhook routes in the document, found " + publicCount);
    }

    @Test
    void anInvalidOrForeignToken_isTreatedAsNoToken() throws Exception {
        for (String path : List.of("/api/users/me", "/api/admin/users", "/api/doctors/patients", "/api/caregiver/dependents")) {
            MvcResult garbage = mockMvc.perform(requestFor("GET", path).header("Authorization", "Bearer not-a-jwt"))
                    .andReturn();
            assertTrue(refusedByFilter(garbage, 401), path + " with a malformed token: " + garbage.getResponse().getStatus());
            MvcResult basic = mockMvc.perform(requestFor("GET", path).header("Authorization", "Basic YTpi")).andReturn();
            assertTrue(refusedByFilter(basic, 401), path + " with a Basic header: " + basic.getResponse().getStatus());
        }
    }

    // ==================== ROLE GATES ====================

    @Test
    void adminRoutes_areRefusedToPatientDoctorAndCaregiver() throws Exception {
        List<Op> admin = select(op -> op.path().startsWith("/api/admin/"));
        assertRoleGate("the /api/admin gate", admin, List.of(patient, doctor, caregiver));
        assertGateOpenForReads("the /api/admin gate for administrators", admin, List.of(this.admin));
    }

    @Test
    void doctorRoutes_areRefusedToPatientAndCaregiver_exceptTheDirectory() throws Exception {
        List<Op> doctorOnly = select(op -> op.path().startsWith("/api/doctors/")
                && !("GET".equals(op.method()) && under(op.path(), DOCTOR_DIRECTORY)));
        assertRoleGate("the /api/doctors gate", doctorOnly, List.of(patient, caregiver));
        assertGateOpenForReads("the /api/doctors gate for doctors and administrators", doctorOnly,
                List.of(doctor, admin));

        List<Op> directory = select(op -> "GET".equals(op.method()) && op.path().startsWith("/api/doctors/")
                && under(op.path(), DOCTOR_DIRECTORY));
        assertTrue(directory.size() >= 3, "directory, public profile and the legacy active list: " + directory);
        assertGateOpenForReads("the doctor directory", directory, List.of(patient, caregiver, doctor, admin));
        // The directory exception is for reads only.
        for (String path : List.of("/api/doctors/profile/directory", "/api/doctors/profile/public/DOC-00001")) {
            for (User user : List.of(patient, caregiver)) {
                assertTrue(refusedByFilter(as(user, "POST", path), 403), "POST " + path + " as " + user.getRole());
            }
        }
    }

    @Test
    void caregiverRoutes_areRefusedToEveryoneButCaregivers() throws Exception {
        List<Op> caregiverOnly = select(op -> op.path().startsWith("/api/caregiver/"));
        assertRoleGate("the /api/caregiver gate", caregiverOnly, List.of(patient, doctor, admin));
        assertGateOpenForReads("the /api/caregiver gate for caregivers", caregiverOnly, List.of(caregiver));
    }

    @Test
    void patientCaregiverManagementRoutes_areRefusedToDoctorsAndCaregivers() throws Exception {
        List<Op> patientOnly = select(op -> under(op.path(), PATIENT_ONLY_CAREGIVER_MANAGEMENT));
        assertRoleGate("the patient caregiver-management gate", patientOnly, List.of(doctor, caregiver));
        assertGateOpenForReads("the patient caregiver-management gate for patients and administrators", patientOnly,
                List.of(patient, admin));
    }

    // ==================== INTEGRATIONS ====================

    @Test
    void integrationRoutes_areClosedExceptTheTwoProviderWebhooks() throws Exception {
        List<Op> integrations = select(op -> op.path().startsWith("/api/integrations/"));
        Set<String> documented = new TreeSet<>();
        integrations.forEach(op -> documented.add(op.key()));
        assertEquals(Set.of("POST /api/integrations/livekit/webhook", "POST /api/integrations/payments/{provider}/webhook"),
                documented, "the only integration operations are the two provider webhooks");

        // The webhooks are reachable without a token (their controllers verify the provider signature).
        assertFalse(refusedByFilter(anonymous("POST", "/api/integrations/livekit/webhook"), 401));
        assertFalse(refusedByFilter(anonymous("POST", "/api/integrations/payments/local/webhook"), 401));

        // Anything else under /api/integrations is denied to every caller, admin included.
        Map<String, String> closed = Map.of(
                "GET", "/api/integrations/livekit/webhook",
                "PUT", "/api/integrations/payments/local/webhook",
                "POST", "/api/integrations/payments/local/refund",
                "DELETE", "/api/integrations/livekit/rooms/1");
        for (Map.Entry<String, String> route : closed.entrySet()) {
            assertTrue(refusedByFilter(anonymous(route.getKey(), route.getValue()), 401),
                    route + " without a token");
            for (User user : List.of(patient, doctor, caregiver, admin)) {
                assertTrue(refusedByFilter(as(user, route.getKey(), route.getValue()), 403),
                        route + " as " + user.getRole());
            }
        }
        assertTrue(refusedByFilter(as(admin, "POST", "/api/integrations/other/webhook"), 403),
                "only the payments and LiveKit webhooks are open");
    }
}
