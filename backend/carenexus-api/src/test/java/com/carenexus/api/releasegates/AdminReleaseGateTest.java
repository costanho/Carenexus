package com.carenexus.api.releasegates;

import com.carenexus.api.admin.user.UserAdminService;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.event.AdminPasswordResetRequestedEvent;
import com.carenexus.api.common.event.DoctorVerificationDecidedEvent;
import com.carenexus.api.common.event.UserDeactivatedEvent;
import com.carenexus.api.common.event.UserLoggedOutEvent;
import com.carenexus.api.common.event.UserReactivatedEvent;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.core.model.Appointment;
import com.carenexus.api.core.repository.AppointmentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Release gate for administrator account controls, the audit search and clinician verification
 * (be-admin-users):
 * <ul>
 *   <li>every admin path is 401 without a token and 403 for patients, doctors and caregivers;</li>
 *   <li>the account list pages, filters and searches; the detail carries the status details;</li>
 *   <li>deactivation ends every session at once and blocks the next request; self and last-administrator
 *       protection; activation re-opens closed accounts but never anonymised ones or unverified clinicians;</li>
 *   <li>a clinician cannot sign in until approved; rejection, licence correction and approval; the licence number
 *       is required for approval and unique;</li>
 *   <li>every state change and every audit read leaves an audit row.</li>
 * </ul>
 */
@RecordApplicationEvents
class AdminReleaseGateTest extends CommittedIntegrationTest {

    private static final String PASSWORD = "password123";
    private static final String NEW_PASSWORD = "Str0ng-Passw0rd";

    @Autowired
    private ApplicationEvents events;

    @Autowired
    private UserAdminService userAdminService;

    @Autowired
    private AppointmentRepository appointmentRepository;

    // ==================== ROUTE SECURITY ====================

    @Test
    void everyAdminPath_is401WithoutAToken_and403ForPatientDoctorAndCaregiver() throws Exception {
        List<String[]> routes = List.of(
                new String[]{"GET", "/api/admin/users"},
                new String[]{"GET", "/api/admin/users/PAT-00001"},
                new String[]{"POST", "/api/admin/users"},
                new String[]{"POST", "/api/admin/users/PAT-00002/activate"},
                new String[]{"POST", "/api/admin/users/PAT-00002/deactivate"},
                new String[]{"POST", "/api/admin/users/PAT-00002/sessions/revoke"},
                new String[]{"POST", "/api/admin/users/PAT-00002/password-reset"},
                new String[]{"GET", "/api/admin/users/PAT-00001/audit-logs"},
                new String[]{"GET", "/api/admin/audit-logs"},
                new String[]{"GET", "/api/admin/audit-logs/1"},
                new String[]{"GET", "/api/admin/doctors/pending"},
                new String[]{"GET", "/api/admin/doctors"},
                new String[]{"POST", "/api/admin/doctors/DOC-00002/verify"},
                new String[]{"POST", "/api/admin/doctors/DOC-00002/reject"},
                new String[]{"PUT", "/api/admin/doctors/DOC-00002/licence"});
        Map<String, Object> body = Map.of("reason", "test", "licenseNo", "X-1", "firstName", "A", "lastName", "B",
                "email", "x@example.com", "password", NEW_PASSWORD);

        for (String[] route : routes) {
            MvcResult anonymous = mockMvc.perform(request(route[0], route[1])
                    .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body))).andReturn();
            assertEquals(401, anonymous.getResponse().getStatus(), route[0] + " " + route[1]);
            assertEquals("Authentication required", message(anonymous));

            for (User caller : List.of(patient, doctor, caregiver)) {
                MvcResult denied = call(request(route[0], route[1]), caller, body);
                assertEquals(403, denied.getResponse().getStatus(), caller.getRole() + " " + route[0] + " " + route[1]);
                assertEquals("You don't have permission to access this resource", message(denied));
            }
        }
        // Even on their own system id.
        assertEquals(403, doGet("/api/admin/users/" + patient.getSystemId(), patient).getResponse().getStatus());

        // Nothing changed.
        assertEquals(7, count("SELECT COUNT(*) FROM users WHERE is_active"));
        assertEquals(0, count("SELECT COUNT(*) FROM audit_log WHERE action IN ('USER_DEACTIVATE', 'USER_ACTIVATE', "
                + "'DOCTOR_VERIFY', 'DOCTOR_REJECT', 'LICENCE_UPDATE', 'USER_CREATE', 'SESSIONS_REVOKE')"));
        assertEquals("APPROVED", jdbc.queryForObject(
                "SELECT verification_status FROM doctors WHERE system_id = 'DOC-00002'", String.class));
    }

    // ==================== USER LIST AND DETAIL ====================

    @Test
    void userList_pagesNewestFirst_filtersAndSearchesNameEmailAndSystemId() throws Exception {
        JsonNode all = json(doGet("/api/admin/users?size=3", admin));
        assertEquals(7, all.path("totalElements").asInt());
        assertEquals(3, all.path("totalPages").asInt());
        assertEquals(3, all.path("size").asInt());
        assertEquals("ADM-00001", all.path("content").get(0).path("systemId").asText(), "newest account first");
        JsonNode row = all.path("content").get(0);
        for (String field : List.of("userId", "systemId", "firstName", "lastName", "email", "phone", "role", "isActive",
                "createdAt", "updatedAt", "emailVerified", "mustChangePassword", "closedAt", "anonymised",
                "verificationStatus")) {
            assertTrue(row.has(field), "missing field " + field);
        }
        assertTrue(row.path("passwordHash").isMissingNode());
        assertTrue(row.path("verificationStatus").isNull());
        assertTrue(row.path("emailVerified").asBoolean());
        assertFalse(row.path("anonymised").asBoolean());

        JsonNode doctors = json(doGet("/api/admin/users?role=doctor", admin));
        assertEquals(2, doctors.path("totalElements").asInt());
        assertEquals("APPROVED", doctors.path("content").get(0).path("verificationStatus").asText());
        assertEquals(2, json(doGet("/api/admin/users?role=PROXY", admin)).path("totalElements").asInt());

        // Search: email, system id (any case), full name; filters combine with AND.
        assertEquals(List.of("DOC-00001"), systemIds(doGet("/api/admin/users?q=AMARA@", admin)));
        assertEquals(List.of("DOC-00002", "DOC-00001"), systemIds(doGet("/api/admin/users?q=doc-0000", admin)));
        assertEquals(List.of("PAT-00002"), systemIds(search(" patient user4 ")));
        assertEquals(List.of(), systemIds(doGet("/api/admin/users?q=doc-0000&role=PATIENT", admin)));
        // Wildcards are matched literally.
        jdbc.update("UPDATE users SET email = 'thomas_k@example.com' WHERE system_id = 'PAT-00001'");
        assertEquals(List.of(), systemIds(search("%")));
        assertEquals(List.of("PAT-00001"), systemIds(search("_")));
        assertEquals(List.of(), systemIds(search("s_a")), "'_' is not a single-character wildcard");

        jdbc.update("UPDATE users SET is_active = FALSE WHERE system_id = 'CGV-00002'");
        assertEquals(List.of("CGV-00002"), systemIds(doGet("/api/admin/users?active=false", admin)));
        assertEquals(6, json(doGet("/api/admin/users?active=true", admin)).path("totalElements").asInt());

        MvcResult tooLong = doGet("/api/admin/users?q=" + "a".repeat(101), admin);
        assertEquals(400, tooLong.getResponse().getStatus());
        assertEquals("VALIDATION_FAILED", json(tooLong).path("code").asText());
        assertEquals("q: Search text must be at most 100 characters", message(tooLong));
        assertEquals("Invalid role. Must be PATIENT, DOCTOR, CAREGIVER or ADMIN",
                message(doGet("/api/admin/users?role=nurse", admin)));
        assertEquals("Invalid value for parameter 'active'", message(doGet("/api/admin/users?active=maybe", admin)));
        assertEquals("Invalid value for parameter 'page'", message(doGet("/api/admin/users?page=x", admin)));
        // Account directory reads are not audited.
        assertEquals(0, count("SELECT COUNT(*) FROM audit_log WHERE action = 'READ'"));
    }

    @Test
    void userDetail_carriesSessionsAppointmentsAndTheClinicianRecord() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        appointment(patient, doctor, now.plusDays(2), "SCHEDULED");
        appointment(patient, doctor, now.plusDays(3), "RESCHEDULED");
        appointment(patient, doctor, now.plusDays(4), "CANCELLED");
        appointment(patient, doctor, now.minusDays(1), "SCHEDULED");
        appointment(otherPatient, doctor, now.plusDays(5), "SCHEDULED");
        login(doctor.getEmail(), PASSWORD);
        login(doctor.getEmail(), PASSWORD);

        JsonNode doc = json(doGet("/api/admin/users/DOC-00001", admin));
        assertEquals("DOCTOR", doc.path("role").asText());
        assertEquals(3, doc.path("futureActiveAppointments").asInt());
        assertEquals(2, doc.path("activeSessions").asInt());
        assertTrue(doc.path("hasPassword").asBoolean());
        assertTrue(doc.path("deactivationReason").isNull());
        assertEquals("APPROVED", doc.path("verificationStatus").asText());
        JsonNode clinician = doc.path("doctor");
        assertEquals("DOC-00001", clinician.path("doctorSystemId").asText());
        assertEquals("UNVERIFIED_MANUAL_REVIEW", clinician.path("registryStatus").asText());
        assertTrue(clinician.path("licenseNo").isNull());
        assertTrue(clinician.path("isActive").asBoolean());

        JsonNode pat = json(doGet("/api/admin/users/PAT-00001", admin));
        assertEquals(2, pat.path("futureActiveAppointments").asInt());
        assertTrue(pat.path("doctor").isNull());
        assertTrue(pat.path("verificationStatus").isNull());
        assertEquals(0, json(doGet("/api/admin/users/CGV-00001", admin)).path("futureActiveAppointments").asInt());

        MvcResult missing = doGet("/api/admin/users/PAT-99999", admin);
        assertEquals(404, missing.getResponse().getStatus());
        assertEquals("User not found", message(missing));
    }

    // ==================== CREATE ADMINISTRATOR ====================

    @Test
    void createAdministrator_isIdempotent_forcesAPasswordChange_andIsAudited() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("firstName", "  Farai ");
        body.put("lastName", "Ncube");
        body.put("email", " Farai.Ncube@CareNexus.test ");
        body.put("password", NEW_PASSWORD);
        body.put("phone", null);
        body.put("role", "PATIENT");
        String key = "create-admin-" + System.nanoTime();

        MvcResult first = doPostWithHeaders("/api/admin/users", admin, body, Map.of("Idempotency-Key", key));
        assertEquals(201, first.getResponse().getStatus(), first.getResponse().getContentAsString());
        JsonNode created = json(first);
        assertEquals("ADM-00002", created.path("systemId").asText());
        assertEquals("ADMIN", created.path("role").asText());
        assertEquals("Farai", created.path("firstName").asText());
        assertEquals("farai.ncube@carenexus.test", created.path("email").asText());
        assertTrue(created.path("isActive").asBoolean());
        assertTrue(created.path("mustChangePassword").asBoolean());
        assertTrue(created.path("emailVerified").asBoolean());
        assertTrue(created.path("doctor").isNull());
        assertTrue(created.path("password").isMissingNode());

        MvcResult replay = doPostWithHeaders("/api/admin/users", admin, body, Map.of("Idempotency-Key", key));
        assertEquals(200, replay.getResponse().getStatus());
        assertEquals(created, json(replay));
        assertEquals(1, count("SELECT COUNT(*) FROM users WHERE email = 'farai.ncube@carenexus.test'"));
        assertEquals(0, count("SELECT COUNT(*) FROM idempotency_keys WHERE response_json LIKE '%" + NEW_PASSWORD + "%'"));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'USER_CREATE' AND resource_type = 'ACCOUNT' "
                + "AND system_id = 'ADM-00001' AND target_system_id = 'ADM-00002' AND status = 'SUCCESS'"));
        assertEquals(0, count("SELECT COUNT(*) FROM outbound_messages"), "no email for a created administrator");

        // The new administrator signs in with the temporary password and must change it.
        assertEquals(200, login("farai.ncube@carenexus.test", NEW_PASSWORD).getResponse().getStatus());
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "SELECT must_change_password FROM users WHERE system_id = 'ADM-00002'", Boolean.class));

        MvcResult duplicate = doPost("/api/admin/users", admin, Map.of("firstName", "A", "lastName", "B",
                "email", "admin@example.com", "password", NEW_PASSWORD));
        assertEquals(409, duplicate.getResponse().getStatus());
        assertEquals("Email already registered", message(duplicate));

        MvcResult weak = doPost("/api/admin/users", admin, Map.of("firstName", "A", "lastName", "B",
                "email", "weak@example.com", "password", "short"));
        assertEquals(400, weak.getResponse().getStatus());
        assertEquals("VALIDATION_FAILED", json(weak).path("code").asText());
        assertEquals("password: Password must be at least 8 characters and contain at least one letter and one number",
                message(weak));
        MvcResult noName = doPost("/api/admin/users", admin, Map.of("firstName", " ", "lastName", "B",
                "email", "noname@example.com", "password", NEW_PASSWORD));
        assertEquals("firstName: First name is required", message(noName));
        MvcResult badPhone = doPost("/api/admin/users", admin, Map.of("firstName", "A", "lastName", "B",
                "email", "phone@example.com", "password", NEW_PASSWORD, "phone", "call me"));
        assertEquals("phone: Please enter a valid phone number", message(badPhone));
        MvcResult badKey = doPostWithHeaders("/api/admin/users", admin, Map.of("firstName", "A", "lastName", "B",
                "email", "key@example.com", "password", NEW_PASSWORD), Map.of("Idempotency-Key", "bad key!"));
        assertEquals(400, badKey.getResponse().getStatus());
        assertEquals("Idempotency-Key must be 8 to 64 characters: letters, digits, '_' or '-'", message(badKey));
        assertEquals(2, count("SELECT COUNT(*) FROM users WHERE role = 'ADMIN'"));
    }

    // ==================== DEACTIVATE AND ACTIVATE ====================

    @Test
    void deactivatingADoctor_endsEverySession_blocksTheNextRequest_andSuspendsTheClinician() throws Exception {
        appointment(patient, doctor, LocalDateTime.now().plusDays(1), "SCHEDULED");
        appointment(otherPatient, doctor, LocalDateTime.now().plusDays(2), "SCHEDULED");
        JsonNode session = json(login(doctor.getEmail(), PASSWORD));
        String accessToken = session.path("tokens").path("accessToken").asText();
        String refreshToken = session.path("tokens").path("refreshToken").asText();
        assertEquals(200, bearer("/api/users/me", accessToken).getResponse().getStatus());

        MvcResult result = doPost("/api/admin/users/DOC-00001/deactivate", admin,
                Map.of("reason", "  Left the practice on 30 November "));
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode body = json(result);
        assertEquals("Account deactivated. The user has been signed out everywhere.", body.path("message").asText());
        assertFalse(body.path("isActive").asBoolean());
        assertEquals("SUSPENDED", body.path("verificationStatus").asText());
        assertEquals("Left the practice on 30 November", body.path("deactivationReason").asText());
        assertEquals(0, body.path("activeSessions").asInt());
        assertEquals(2, body.path("futureActiveAppointments").asInt());
        assertEquals("SUSPENDED", body.path("doctor").path("verificationStatus").asText());
        assertEquals("Left the practice on 30 November", body.path("doctor").path("rejectionReason").asText());
        assertEquals("ADM-00001", body.path("doctor").path("verifiedBySystemId").asText());
        assertFalse(body.path("doctor").path("isActive").asBoolean());

        // The very next request is refused, the refresh token is dead and the password no longer works.
        assertEquals(401, bearer("/api/users/me", accessToken).getResponse().getStatus());
        assertEquals(401, postJson("/api/auth/refresh", Map.of("refreshToken", refreshToken)).getResponse().getStatus());
        MvcResult relogin = login(doctor.getEmail(), PASSWORD);
        assertEquals(400, relogin.getResponse().getStatus());
        assertEquals("Account is inactive", message(relogin));
        assertEquals(0, count("SELECT COUNT(*) FROM refresh_tokens WHERE system_id = 'DOC-00001' AND NOT is_revoked"));

        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'USER_DEACTIVATE' AND resource_type = 'ACCOUNT' "
                + "AND system_id = 'ADM-00001' AND actor_role = 'ADMIN' AND target_system_id = 'DOC-00001' "
                + "AND request_path = '/api/admin/users/DOC-00001/deactivate'"));
        assertEquals(0, count("SELECT COUNT(*) FROM audit_log WHERE COALESCE(error_message, '') LIKE '%practice%'"),
                "the reason is never written to the audit log");
        assertEquals(List.of(new UserDeactivatedEvent("DOC-00001", "DOCTOR", "ADM-00001", "ADMIN")),
                events.stream(UserDeactivatedEvent.class).toList());
        assertEquals(List.of(new DoctorVerificationDecidedEvent("DOC-00001", "ADM-00001", "SUSPENDED",
                "Left the practice on 30 November")), events.stream(DoctorVerificationDecidedEvent.class).toList());
        assertEquals(1, events.stream(UserLoggedOutEvent.class).count());

        // A repeat is a no-op: the stored reason stays, nothing is written or published again.
        JsonNode repeat = json(doPost("/api/admin/users/DOC-00001/deactivate", admin, Map.of("reason", "Another reason")));
        assertEquals("Account is already inactive.", repeat.path("message").asText());
        assertEquals("Left the practice on 30 November", repeat.path("deactivationReason").asText());
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'USER_DEACTIVATE'"));
        assertEquals(1, events.stream(UserDeactivatedEvent.class).count());

        // Reactivation restores the approval, clears the reason and lets the doctor sign in again.
        JsonNode activated = json(doPost("/api/admin/users/DOC-00001/activate", admin, null));
        assertEquals("Account reactivated. Cancelled appointments and revoked caregiver access were not restored.",
                activated.path("message").asText());
        assertTrue(activated.path("isActive").asBoolean());
        assertEquals("APPROVED", activated.path("verificationStatus").asText());
        assertTrue(activated.path("deactivationReason").isNull());
        assertTrue(activated.path("doctor").path("rejectionReason").isNull());
        assertTrue(activated.path("doctor").path("isActive").asBoolean());
        assertEquals(200, login(doctor.getEmail(), PASSWORD).getResponse().getStatus());
        assertEquals(List.of(new UserReactivatedEvent("DOC-00001", "DOCTOR", "ADM-00001")),
                events.stream(UserReactivatedEvent.class).toList());
        assertEquals(2, events.stream(DoctorVerificationDecidedEvent.class).count());
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'USER_ACTIVATE' AND target_system_id = 'DOC-00001'"));

        JsonNode again = json(doPost("/api/admin/users/DOC-00001/activate", admin, null));
        assertEquals("Account is already active.", again.path("message").asText());
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'USER_ACTIVATE'"));
    }

    @Test
    void deactivate_needsAReason_andProtectsTheCallerAndTheLastAdministrator() throws Exception {
        MvcResult noBody = doPost("/api/admin/users/PAT-00001/deactivate", admin, null);
        assertEquals(400, noBody.getResponse().getStatus());
        assertEquals("Malformed request body", message(noBody));
        MvcResult blank = doPost("/api/admin/users/PAT-00001/deactivate", admin, Map.of("reason", "   "));
        assertEquals(400, blank.getResponse().getStatus());
        assertEquals("VALIDATION_FAILED", json(blank).path("code").asText());
        assertEquals("reason: Reason is required", message(blank));
        assertEquals("reason: Reason must be at most 500 characters",
                message(doPost("/api/admin/users/PAT-00001/deactivate", admin, Map.of("reason", "x".repeat(501)))));
        assertEquals("User not found",
                message(doPost("/api/admin/users/PAT-99999/deactivate", admin, Map.of("reason", "Gone"))));

        MvcResult self = doPost("/api/admin/users/ADM-00001/deactivate", admin, Map.of("reason", "Leaving"));
        assertEquals(409, self.getResponse().getStatus());
        assertEquals("You cannot deactivate your own account", message(self));

        // A second administrator can be deactivated while another one stays active.
        User second = createAndSaveUser(8, "ADM-00009", "second.admin@example.com", "ADMIN");
        assertEquals(200, doPost("/api/admin/users/ADM-00009/deactivate", admin, Map.of("reason", "Rotation"))
                .getResponse().getStatus());

        // The last active administrator is never deactivated (reachable when the caller's own row is already
        // inactive, for example after a concurrent deactivation).
        jdbc.update("UPDATE users SET is_active = TRUE WHERE system_id = 'ADM-00009'");
        jdbc.update("UPDATE users SET is_active = FALSE WHERE system_id = 'ADM-00001'");
        authenticateAs(admin);
        try {
            ConflictException last = assertThrows(ConflictException.class,
                    () -> userAdminService.deactivate(second.getSystemId(), "Last one"));
            assertEquals("Cannot deactivate the last active administrator", last.getMessage());
        } finally {
            clearAuthentication();
        }
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "SELECT is_active FROM users WHERE system_id = 'ADM-00009'", Boolean.class));

        // A deactivated patient keeps their appointments (no clean-up here) and is reported with the count.
        jdbc.update("UPDATE users SET is_active = TRUE WHERE system_id = 'ADM-00001'");
        appointment(patient, doctor, LocalDateTime.now().plusDays(1), "SCHEDULED");
        JsonNode pat = json(doPost("/api/admin/users/PAT-00001/deactivate", admin, Map.of("reason", "Duplicate account")));
        assertEquals(1, pat.path("futureActiveAppointments").asInt());
        assertTrue(pat.path("verificationStatus").isNull());
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'USER_DEACTIVATE' "
                + "AND target_system_id = 'PAT-00001' AND patient_system_id = 'PAT-00001'"));
    }

    @Test
    void activate_reopensAClosedAccount_butNeverAnAnonymisedOneOrAnUnverifiedClinician() throws Exception {
        jdbc.update("UPDATE users SET is_active = FALSE, closed_at = NOW(), closure_reason = 'Moving abroad' "
                + "WHERE system_id = 'PAT-00002'");
        MvcResult closedLogin = login(otherPatient.getEmail(), PASSWORD);
        assertEquals(403, closedLogin.getResponse().getStatus());
        assertEquals("ACCOUNT_CLOSED", json(closedLogin).path("code").asText());
        JsonNode listed = json(doGet("/api/admin/users/PAT-00002", admin));
        assertFalse(listed.path("closedAt").isNull());
        assertEquals("Moving abroad", listed.path("closureReason").asText());
        assertFalse(listed.path("anonymised").asBoolean());

        JsonNode reopened = json(doPost("/api/admin/users/PAT-00002/activate", admin, null));
        assertTrue(reopened.path("isActive").asBoolean());
        assertTrue(reopened.path("closedAt").isNull());
        assertTrue(reopened.path("closureReason").isNull());
        assertEquals(200, login(otherPatient.getEmail(), PASSWORD).getResponse().getStatus());

        // Anonymised: closed, no email, no password.
        jdbc.update("UPDATE users SET is_active = FALSE, closed_at = NOW(), email = NULL, password_hash = NULL, "
                + "first_name = 'Deleted', last_name = 'User', closure_reason = 'Account deleted on request' "
                + "WHERE system_id = 'CGV-00002'");
        JsonNode anonymisedRow = json(doGet("/api/admin/users/CGV-00002", admin));
        assertTrue(anonymisedRow.path("anonymised").asBoolean());
        assertTrue(anonymisedRow.path("email").isNull());
        assertFalse(anonymisedRow.path("hasPassword").asBoolean());
        MvcResult anonymised = doPost("/api/admin/users/CGV-00002/activate", admin, null);
        assertEquals(409, anonymised.getResponse().getStatus());
        assertEquals("ACCOUNT_CLOSED", json(anonymised).path("code").asText());
        assertEquals("This account was deleted on request and cannot be reactivated", message(anonymised));

        // A pending clinician is approved through verification, never through activation.
        jdbc.update("UPDATE users SET is_active = FALSE WHERE system_id = 'DOC-00002'");
        jdbc.update("UPDATE doctors SET is_active = FALSE, verification_status = 'PENDING' WHERE system_id = 'DOC-00002'");
        MvcResult pending = doPost("/api/admin/users/DOC-00002/activate", admin, null);
        assertEquals(409, pending.getResponse().getStatus());
        assertEquals("DOCTOR_NOT_VERIFIED", json(pending).path("code").asText());
        assertEquals("This clinician is not verified yet. Approve the account from the clinician verification queue.",
                message(pending));
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
                "SELECT is_active FROM users WHERE system_id = 'DOC-00002'", Boolean.class));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'USER_ACTIVATE'"));
    }

    // ==================== SESSIONS AND PASSWORD RESET ====================

    @Test
    void forcedSignOut_revokesEveryRefreshToken_andKeepsTheAccountActive() throws Exception {
        JsonNode first = json(login(patient.getEmail(), PASSWORD));
        login(patient.getEmail(), PASSWORD);
        assertEquals(2, json(doGet("/api/admin/users/PAT-00001", admin)).path("activeSessions").asInt());

        JsonNode result = json(doPost("/api/admin/users/PAT-00001/sessions/revoke", admin, null));
        assertEquals("The user has been signed out everywhere.", result.path("message").asText());
        assertEquals(0, result.path("activeSessions").asInt());
        assertTrue(result.path("isActive").asBoolean());
        assertEquals(401, postJson("/api/auth/refresh",
                Map.of("refreshToken", first.path("tokens").path("refreshToken").asText())).getResponse().getStatus());
        // The access token keeps working until it expires: the account is still active.
        assertEquals(200, bearer("/api/users/me", first.path("tokens").path("accessToken").asText())
                .getResponse().getStatus());

        // Safe to repeat; audited every time.
        assertEquals(200, doPost("/api/admin/users/PAT-00001/sessions/revoke", admin, null).getResponse().getStatus());
        assertEquals(2, count("SELECT COUNT(*) FROM audit_log WHERE action = 'SESSIONS_REVOKE' "
                + "AND target_system_id = 'PAT-00001' AND system_id = 'ADM-00001'"));
        assertEquals(404, doPost("/api/admin/users/PAT-99999/sessions/revoke", admin, null).getResponse().getStatus());
    }

    @Test
    void passwordReset_publishesTheEvent_andRefusesClosedAccountsAndAccountsWithoutEmail() throws Exception {
        JsonNode result = json(doPost("/api/admin/users/PAT-00001/password-reset", admin, null));
        assertEquals("A link to set a new password was sent to the account's email address.",
                result.path("message").asText());
        assertEquals(List.of(new AdminPasswordResetRequestedEvent("PAT-00001", "ADM-00001")),
                events.stream(AdminPasswordResetRequestedEvent.class).toList());
        // The request sets nothing: no forced change, sessions untouched.
        assertFalse(result.path("mustChangePassword").asBoolean());

        jdbc.update("UPDATE users SET closed_at = NOW(), is_active = FALSE WHERE system_id = 'PAT-00002'");
        MvcResult closed = doPost("/api/admin/users/PAT-00002/password-reset", admin, null);
        assertEquals(409, closed.getResponse().getStatus());
        assertEquals("This account is closed", message(closed));

        jdbc.update("UPDATE users SET email = NULL, password_hash = NULL WHERE system_id = 'CGV-00002'");
        MvcResult noEmail = doPost("/api/admin/users/CGV-00002/password-reset", admin, null);
        assertEquals(409, noEmail.getResponse().getStatus());
        assertEquals("This account has no email address", message(noEmail));
        assertEquals("User not found", message(doPost("/api/admin/users/PAT-99999/password-reset", admin, null)));
        assertEquals(1, events.stream(AdminPasswordResetRequestedEvent.class).count());
    }

    // ==================== CLINICIAN VERIFICATION ====================

    @Test
    void aRegisteredDoctor_cannotSignIn_untilApproved_andAdministratorsAreTold() throws Exception {
        assertEquals(201, register("gate.doctor@example.com", "MDPCZ-2019-04417").getResponse().getStatus());
        String doc = "DOC-00003";

        assertEquals(1, count("SELECT COUNT(*) FROM notifications WHERE system_id = 'ADM-00001' AND type = 'SYSTEM' "
                + "AND category = 'SYSTEM' AND target = 'ADMIN_CLINICIAN_QUEUE' AND title = 'New clinician registration' "
                + "AND action_url = '/dashboard/admin?section=clinician-queue' AND dedupe_key = 'DOCTOR_REGISTERED:" + doc + "'"));

        MvcResult pendingLogin = login("gate.doctor@example.com", NEW_PASSWORD);
        assertEquals(403, pendingLogin.getResponse().getStatus());
        assertEquals("ACCOUNT_PENDING_VERIFICATION", json(pendingLogin).path("code").asText());

        JsonNode queue = json(doGet("/api/admin/doctors/pending", admin));
        assertEquals(1, queue.path("totalElements").asInt());
        JsonNode row = queue.path("content").get(0);
        assertEquals(doc, row.path("doctorSystemId").asText());
        assertEquals("PENDING", row.path("verificationStatus").asText());
        assertEquals("UNVERIFIED_MANUAL_REVIEW", row.path("registryStatus").asText());
        assertEquals("MDPCZ-2019-04417", row.path("licenseNo").asText());
        assertFalse(row.path("isActive").asBoolean());
        assertTrue(row.path("verifiedAt").isNull());
        assertTrue(row.path("bio").isMissingNode());
        assertEquals("PENDING", json(doGet("/api/admin/users/" + doc, admin)).path("verificationStatus").asText());

        JsonNode approved = json(doPost("/api/admin/doctors/" + doc + "/verify", admin, null));
        assertEquals("APPROVED", approved.path("verificationStatus").asText());
        assertTrue(approved.path("isActive").asBoolean());
        assertEquals("ADM-00001", approved.path("verifiedBySystemId").asText());
        assertNotNull(approved.path("verifiedAt").textValue());
        assertEquals(200, login("gate.doctor@example.com", NEW_PASSWORD).getResponse().getStatus());
        assertEquals(0, json(doGet("/api/admin/doctors/pending", admin)).path("totalElements").asInt());

        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'DOCTOR_VERIFY' AND resource_type = 'ACCOUNT' "
                + "AND target_system_id = '" + doc + "' AND system_id = 'ADM-00001'"));
        assertEquals(1, count("SELECT COUNT(*) FROM outbound_messages WHERE template_id = 'account-clinician-approved' "
                + "AND recipient_address = 'gate.doctor@example.com' AND body LIKE '%/auth/login/web/login-web%' "
                + "AND dedupe_key LIKE 'CLINICIAN_APPROVED:" + doc + ":%'"));
        assertEquals(1, count("SELECT COUNT(*) FROM notifications WHERE system_id = '" + doc + "' AND type = 'SYSTEM' "
                + "AND title = 'Your clinician account is approved' AND action_url IS NULL"));
        assertEquals(List.of(new DoctorVerificationDecidedEvent(doc, "ADM-00001", "APPROVED", null)),
                events.stream(DoctorVerificationDecidedEvent.class).toList());
        assertEquals(0, events.stream(UserReactivatedEvent.class).count());

        // Approving again is a no-op.
        assertEquals("APPROVED", json(doPost("/api/admin/doctors/" + doc + "/verify", admin, null))
                .path("verificationStatus").asText());
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'DOCTOR_VERIFY'"));
        assertEquals(1, count("SELECT COUNT(*) FROM outbound_messages WHERE template_id = 'account-clinician-approved'"));

        // Not a clinician account.
        MvcResult notDoctor = doPost("/api/admin/doctors/PAT-00001/verify", admin, null);
        assertEquals(404, notDoctor.getResponse().getStatus());
        assertEquals("Doctor not found", message(notDoctor));
    }

    @Test
    void rejection_thenLicenceCorrection_thenApproval() throws Exception {
        register("rejected.doctor@example.com", "WRONG-1");
        String doc = "DOC-00003";
        String reason = "The licence number could not be found in the council register.";

        JsonNode rejected = json(doPost("/api/admin/doctors/" + doc + "/reject", admin, Map.of("reason", " " + reason)));
        assertEquals("REJECTED", rejected.path("verificationStatus").asText());
        assertEquals(reason, rejected.path("rejectionReason").asText());
        assertFalse(rejected.path("isActive").asBoolean());
        MvcResult rejectedLogin = login("rejected.doctor@example.com", NEW_PASSWORD);
        assertEquals(403, rejectedLogin.getResponse().getStatus());
        assertEquals("ACCOUNT_REJECTED", json(rejectedLogin).path("code").asText());
        assertEquals(1, count("SELECT COUNT(*) FROM outbound_messages WHERE template_id = 'account-clinician-rejected' "
                + "AND recipient_address = 'rejected.doctor@example.com' AND body LIKE '%council register%'"));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'DOCTOR_REJECT' AND target_system_id = '" + doc + "'"));
        assertEquals(0, count("SELECT COUNT(*) FROM notifications WHERE system_id = '" + doc + "'"));

        // A repeat keeps the first reason and sends nothing.
        JsonNode repeat = json(doPost("/api/admin/doctors/" + doc + "/reject", admin, Map.of("reason", "Other")));
        assertEquals(reason, repeat.path("rejectionReason").asText());
        assertEquals(1, count("SELECT COUNT(*) FROM outbound_messages WHERE template_id = 'account-clinician-rejected'"));

        JsonNode corrected = json(doPut("/api/admin/doctors/" + doc + "/licence", admin, Map.of(
                "licenseNo", " MDPCZ-2019-04471 ", "licensingBody", "Medical and Dental Practitioners Council of Zimbabwe",
                "licenseCountry", "zw", "specialization", "Cardiology")));
        assertEquals("REJECTED", corrected.path("verificationStatus").asText(), "a licence edit never changes the status");
        assertEquals("MDPCZ-2019-04471", corrected.path("licenseNo").asText());
        assertEquals("ZW", corrected.path("licenseCountry").asText());
        assertEquals("Cardiology", corrected.path("specialization").asText());
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'LICENCE_UPDATE' AND resource_type = 'LICENCE' "
                + "AND target_system_id = '" + doc + "'"));

        JsonNode rejectedList = json(doGet("/api/admin/doctors?status=rejected", admin));
        assertEquals(1, rejectedList.path("totalElements").asInt());

        JsonNode approved = json(doPost("/api/admin/doctors/" + doc + "/verify", admin, null));
        assertEquals("APPROVED", approved.path("verificationStatus").asText());
        assertTrue(approved.path("rejectionReason").isNull());
        assertEquals(200, login("rejected.doctor@example.com", NEW_PASSWORD).getResponse().getStatus());

        // An approved clinician cannot be rejected.
        MvcResult illegal = doPost("/api/admin/doctors/" + doc + "/reject", admin, Map.of("reason", "Late"));
        assertEquals(409, illegal.getResponse().getStatus());
        assertEquals("Only a pending clinician can be rejected. Deactivate the account to suspend an approved clinician.",
                message(illegal));
        assertEquals("reason: Reason is required",
                message(doPost("/api/admin/doctors/" + doc + "/reject", admin, Map.of("reason", ""))));
    }

    @Test
    void approvalNeedsALicenceNumber_andLicenceNumbersAreUnique() throws Exception {
        register("nolicence.doctor@example.com", null);
        String doc = "DOC-00003";

        MvcResult noLicence = doPost("/api/admin/doctors/" + doc + "/verify", admin, null);
        assertEquals(409, noLicence.getResponse().getStatus());
        assertEquals("LICENCE_REQUIRED", json(noLicence).path("code").asText());
        assertEquals("A licence number is required before this clinician can be approved.", message(noLicence));
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT verification_status FROM doctors WHERE system_id = ?", String.class, doc));

        jdbc.update("UPDATE doctors SET license_no = 'MDPCZ-777' WHERE system_id = 'DOC-00002'");
        MvcResult duplicate = doPut("/api/admin/doctors/" + doc + "/licence", admin, Map.of("licenseNo", "mdpcz-777"));
        assertEquals(409, duplicate.getResponse().getStatus());
        assertEquals("Licence number already registered", message(duplicate));
        // The doctor's own number in another case is not a duplicate.
        assertEquals(200, doPut("/api/admin/doctors/DOC-00002/licence", admin, Map.of("licenseNo", "Mdpcz-777"))
                .getResponse().getStatus());

        MvcResult badCountry = doPut("/api/admin/doctors/" + doc + "/licence", admin,
                Map.of("licenseNo", "MDPCZ-888", "licenseCountry", "ZWE"));
        assertEquals(400, badCountry.getResponse().getStatus());
        assertEquals("licenseCountry: Licence country must be a two-letter country code", message(badCountry));
        assertEquals("licenseNo: Licence number is required",
                message(doPut("/api/admin/doctors/" + doc + "/licence", admin, Map.of("licensingBody", "Council"))));
        assertEquals("Doctor not found",
                message(doPut("/api/admin/doctors/CGV-00001/licence", admin, Map.of("licenseNo", "MDPCZ-999"))));

        assertEquals(200, doPut("/api/admin/doctors/" + doc + "/licence", admin, Map.of("licenseNo", "MDPCZ-888"))
                .getResponse().getStatus());
        assertEquals("APPROVED", json(doPost("/api/admin/doctors/" + doc + "/verify", admin, null))
                .path("verificationStatus").asText());

        // A closed clinician account is re-opened through activation, not approval.
        jdbc.update("UPDATE doctors SET verification_status = 'SUSPENDED', is_active = FALSE WHERE system_id = ?", doc);
        jdbc.update("UPDATE users SET is_active = FALSE, closed_at = NOW() WHERE system_id = ?", doc);
        MvcResult closed = doPost("/api/admin/doctors/" + doc + "/verify", admin, null);
        assertEquals(409, closed.getResponse().getStatus());
        assertEquals("ACCOUNT_CLOSED", json(closed).path("code").asText());
    }

    @Test
    void clinicianLists_filterByStatus_pageOldestFirst_andRejectUnknownStatuses() throws Exception {
        register("first.pending@example.com", "LIC-1");
        register("second.pending@example.com", "LIC-2");

        JsonNode pending = json(doGet("/api/admin/doctors/pending?size=1&status=APPROVED", admin));
        assertEquals(2, pending.path("totalElements").asInt(), "status is ignored on the pending queue");
        assertEquals(1, pending.path("size").asInt());
        assertEquals("DOC-00003", pending.path("content").get(0).path("doctorSystemId").asText(), "oldest first");

        assertEquals(4, json(doGet("/api/admin/doctors", admin)).path("totalElements").asInt());
        assertEquals(2, json(doGet("/api/admin/doctors?status=Approved", admin)).path("totalElements").asInt());
        assertEquals(0, json(doGet("/api/admin/doctors?status=SUSPENDED", admin)).path("totalElements").asInt());
        assertEquals(100, json(doGet("/api/admin/doctors?size=5000", admin)).path("size").asInt());
        assertEquals("Invalid status. Must be PENDING, APPROVED, REJECTED or SUSPENDED",
                message(doGet("/api/admin/doctors?status=retired", admin)));
        assertEquals("Invalid value for parameter 'size'", message(doGet("/api/admin/doctors/pending?size=big", admin)));
    }

    // ==================== AUDIT LOG ====================

    @Test
    void auditSearch_filtersByEveryField_namesTheActor_andAuditsEveryRead() throws Exception {
        doPost("/api/admin/users/PAT-00002/deactivate", admin, Map.of("reason", "Duplicate"));
        doPost("/api/admin/users/PAT-00001/sessions/revoke", admin, null);
        login(patient.getEmail(), "wrong-password-1");

        JsonNode byActor = json(doGet("/api/admin/audit-logs?actor=adm-00001&resourceType=account", admin));
        assertEquals(3, byActor.path("totalElements").asInt());
        JsonNode newest = byActor.path("content").get(0);
        assertEquals("SESSIONS_REVOKE", newest.path("action").asText());
        assertEquals("ADMIN User7", newest.path("actorName").asText());
        assertEquals("ADMIN", newest.path("actorRole").asText());
        assertEquals("PAT-00001", newest.path("targetSystemId").asText());
        assertEquals("POST", newest.path("httpMethod").asText());
        for (String field : List.of("auditId", "systemId", "actorName", "actorRole", "action", "resourceType",
                "resourceId", "patientSystemId", "targetSystemId", "httpMethod", "requestPath", "status", "ipAddress",
                "userAgent", "errorMessage", "timestamp")) {
            assertTrue(newest.has(field), "missing field " + field);
        }

        // The old parameter names still work.
        assertEquals(3, json(doGet("/api/admin/audit-logs?systemId=ADM-00001&resourceType=ACCOUNT", admin))
                .path("totalElements").asInt());
        assertEquals(1, json(doGet("/api/admin/audit-logs?patientSystemId=PAT-00002&action=USER_DEACTIVATE", admin))
                .path("totalElements").asInt());
        assertEquals(2, json(doGet("/api/admin/audit-logs?patient=pat-00002", admin)).path("totalElements").asInt());

        JsonNode failures = json(doGet("/api/admin/audit-logs?status=failure", admin));
        assertEquals(1, failures.path("totalElements").asInt());
        assertTrue(failures.path("content").get(0).path("systemId").isNull());
        assertTrue(failures.path("content").get(0).path("actorName").isNull());

        String today = LocalDate.now().toString();
        String tomorrow = LocalDate.now().plusDays(1).toString();
        assertEquals(3, json(doGet("/api/admin/audit-logs?resourceType=ACCOUNT&from=" + today + "&to=" + today, admin))
                .path("totalElements").asInt());
        assertEquals(0, json(doGet("/api/admin/audit-logs?resourceType=ACCOUNT&from=" + tomorrow, admin))
                .path("totalElements").asInt());
        assertEquals(3, json(doGet("/api/admin/audit-logs?resourceType=ACCOUNT&from=" + today + "T00:00&to="
                + tomorrow + "T00:00:00", admin)).path("totalElements").asInt());

        assertEquals("Invalid status. Must be SUCCESS or FAILURE", message(doGet("/api/admin/audit-logs?status=ok", admin)));
        assertEquals("Invalid value for parameter 'from'", message(doGet("/api/admin/audit-logs?from=yesterday", admin)));
        assertEquals("Invalid value for parameter 'to'", message(doGet("/api/admin/audit-logs?to=2026-13-01", admin)));
        assertEquals("'from' must not be after 'to'",
                message(doGet("/api/admin/audit-logs?from=" + tomorrow + "&to=" + today, admin)));
        assertEquals(100, json(doGet("/api/admin/audit-logs?size=1000", admin)).path("size").asInt());
        assertEquals(50, json(doGet("/api/admin/audit-logs", admin)).path("size").asInt());

        // Every successful read wrote one READ AUDIT_LOG row (failed requests wrote none).
        long reads = count("SELECT COUNT(*) FROM audit_log WHERE action = 'READ' AND resource_type = 'AUDIT_LOG' "
                + "AND system_id = 'ADM-00001' AND target_system_id IS NULL");
        assertEquals(10, reads);
        JsonNode top = json(doGet("/api/admin/audit-logs?size=1", admin)).path("content").get(0);
        assertEquals("READ", top.path("action").asText(), "the caller's own read is the newest row");
        assertEquals("AUDIT_LOG", top.path("resourceType").asText());

        long id = top.path("auditId").asLong();
        JsonNode one = json(doGet("/api/admin/audit-logs/" + id, admin));
        assertEquals(id, one.path("auditId").asLong());
        assertEquals("ADMIN User7", one.path("actorName").asText());
        MvcResult missing = doGet("/api/admin/audit-logs/999999", admin);
        assertEquals(404, missing.getResponse().getStatus());
        assertEquals("Audit log entry not found", message(missing));
        assertEquals("Invalid value for parameter 'auditId'", message(doGet("/api/admin/audit-logs/abc", admin)));
    }

    @Test
    void userAuditLogs_listRowsWhereTheAccountIsActorTargetOrPatient() throws Exception {
        login(patient.getEmail(), PASSWORD);
        doPost("/api/admin/users/PAT-00001/sessions/revoke", admin, null);
        doPost("/api/admin/users/PAT-00002/sessions/revoke", admin, null);

        JsonNode page = json(doGet("/api/admin/users/PAT-00001/audit-logs", admin));
        List<String> actions = new ArrayList<>();
        page.path("content").forEach(r -> actions.add(r.path("action").asText()));
        assertEquals(List.of("SESSIONS_REVOKE", "LOGIN"), actions);
        assertEquals(20, page.path("size").asInt());
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'READ' AND resource_type = 'AUDIT_LOG' "
                + "AND request_path = '/api/admin/users/PAT-00001/audit-logs'"));

        assertEquals(0, json(doGet("/api/admin/users/CGV-00002/audit-logs", admin)).path("totalElements").asInt());
        MvcResult missing = doGet("/api/admin/users/PAT-99999/audit-logs", admin);
        assertEquals(404, missing.getResponse().getStatus());
        assertEquals("User not found", message(missing));
    }

    // ==================== HELPERS ====================

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String method, String url) {
        return switch (method) {
            case "GET" -> get(url);
            case "POST" -> post(url);
            case "PUT" -> put(url);
            default -> throw new IllegalArgumentException(method);
        };
    }

    private MvcResult postJson(String path, Object body) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))).andReturn();
    }

    private MvcResult bearer(String path, String accessToken) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", "Bearer " + accessToken)).andReturn();
    }

    private MvcResult login(String email, String password) throws Exception {
        return postJson("/api/auth/login", Map.of("email", email, "password", password));
    }

    private MvcResult register(String email, String licenseNo) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("firstName", "Tendai");
        body.put("lastName", "Moyo");
        body.put("email", email);
        body.put("password", NEW_PASSWORD);
        body.put("role", "doctor");
        if (licenseNo != null) {
            body.put("licenseNo", licenseNo);
            body.put("specialization", "Cardiology");
        }
        MvcResult result = postJson("/api/auth/register", body);
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return result;
    }

    private void appointment(User forPatient, User withDoctor, LocalDateTime at, String status) {
        appointmentRepository.saveAndFlush(Appointment.builder()
                .patientSystemId(forPatient.getSystemId()).doctorSystemId(withDoctor.getSystemId())
                .scheduledAt(at.withNano(0)).type("IN_PERSON").status(status).build());
    }

    private MvcResult search(String q) throws Exception {
        return call(get("/api/admin/users").param("q", q), admin, null);
    }

    private List<String> systemIds(MvcResult result) throws Exception {
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        List<String> ids = new ArrayList<>();
        json(result).path("content").forEach(r -> ids.add(r.path("systemId").asText()));
        return ids;
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
