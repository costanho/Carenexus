package com.carenexus.api.lifecycle;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.core.model.Appointment;
import com.carenexus.api.core.repository.AppointmentRepository;
import com.carenexus.api.lifecycle.service.AccountDeactivationEffectsService;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Fixtures for the account clean-up tests. Runs without a test transaction (CommittedIntegrationTest), so the
 * AFTER_COMMIT lifecycle listener really runs after the deactivating request committed, as in production.
 */
public abstract class AccountLifecycleTestSupport extends CommittedIntegrationTest {

    protected static final String PASSWORD = "password123";
    protected static final String THREADS = "/api/communications/threads";
    protected static final String EFFECTS = AccountDeactivationEffectsService.AUDIT_ACTION;

    private static final String FAIL_FUNCTION = "lifecycle_test_fail_on_change";

    @Autowired
    protected AppointmentRepository appointmentRepository;

    /** Drops the failure triggers of {@link #failWhenChanged}: TRUNCATE keeps triggers, so every test removes them. */
    @AfterEach
    protected void dropFailureTriggers() {
        jdbc.execute("DROP TRIGGER IF EXISTS lifecycle_test_fail ON appointments");
        jdbc.execute("DROP TRIGGER IF EXISTS lifecycle_test_fail ON dependent_access");
        jdbc.execute("DROP FUNCTION IF EXISTS " + FAIL_FUNCTION + "()");
    }

    /**
     * Makes every UPDATE of the row with this primary key fail with a database error, so one unit of the clean-up
     * throws while the others succeed. {@code table} is {@code appointments} or {@code dependent_access}.
     */
    protected void failWhenChanged(String table, String idColumn, int id) {
        jdbc.execute("CREATE OR REPLACE FUNCTION " + FAIL_FUNCTION + "() RETURNS trigger AS $$ BEGIN "
                + "IF NEW." + idColumn + " = " + id + " THEN RAISE EXCEPTION 'lifecycle test failure'; END IF; "
                + "RETURN NEW; END $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER lifecycle_test_fail BEFORE UPDATE ON " + table
                + " FOR EACH ROW EXECUTE FUNCTION " + FAIL_FUNCTION + "()");
    }

    protected Appointment appointment(User forPatient, User withDoctor, LocalDateTime at, String status) {
        return appointmentRepository.saveAndFlush(Appointment.builder()
                .patientSystemId(forPatient.getSystemId()).doctorSystemId(withDoctor.getSystemId())
                .scheduledAt(at.withSecond(0).withNano(0)).type("IN_PERSON").status(status).build());
    }

    protected String statusOf(Appointment appointment) {
        return jdbc.queryForObject("SELECT status FROM appointments WHERE appointment_id = ?", String.class,
                appointment.getAppointmentId());
    }

    protected void pendingReferral(User referringDoctor, User specialist, User forPatient, String status) {
        jdbc.update("INSERT INTO referrals (patient_system_id, referring_doctor_system_id, specialist_system_id, "
                        + "reason, status, priority, referral_date, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'Cardiology opinion', ?, 'ROUTINE', ?, NOW(), NOW())",
                forPatient.getSystemId(), referringDoctor.getSystemId(), specialist.getSystemId(), status,
                LocalDate.now());
    }

    /** True once be-caregiver-requests-consent (V44) created {@code caregiver_access_requests}. */
    protected boolean accessRequestsTableExists() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT to_regclass('caregiver_access_requests') IS NOT NULL", Boolean.class));
    }

    /** True once be-reminders (V54) addressed reminders to a recipient ({@code reminders.recipient_system_id}). */
    protected boolean reminderRecipientColumnExists() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM information_schema.columns "
                + "WHERE table_schema = current_schema() AND table_name = 'reminders' "
                + "AND column_name = 'recipient_system_id')", Boolean.class));
    }

    /** An active daily reminder about the patient, addressed to the recipient, due tomorrow (V54 columns). */
    protected int activeReminder(User aboutPatient, User recipient) {
        Integer id = jdbc.queryForObject("INSERT INTO reminders (patient_system_id, recipient_system_id, type, title, "
                        + "message, reminder_time, frequency, start_date, is_active, next_due_at) "
                        + "VALUES (?, ?, 'MEDICATION', 'Evening tablets', 'Take the evening dose', '20:00', 'DAILY', ?, "
                        + "TRUE, ?) RETURNING reminder_id", Integer.class,
                aboutPatient.getSystemId(), recipient.getSystemId(), LocalDate.now(),
                LocalDateTime.now().plusDays(1).withHour(20).withMinute(0).withSecond(0).withNano(0));
        return id == null ? 0 : id;
    }

    /** {@code [is_active, next_due_at IS NULL]} of a reminder. */
    protected boolean[] reminderState(int reminderId) {
        Map<String, Object> row = jdbc.queryForMap("SELECT is_active, next_due_at IS NULL AS no_next_due "
                + "FROM reminders WHERE reminder_id = ?", reminderId);
        return new boolean[]{Boolean.TRUE.equals(row.get("is_active")), Boolean.TRUE.equals(row.get("no_next_due"))};
    }

    /**
     * A caregiver access request (V44 columns) in the given status. {@code forPatient} may be null for a request
     * whose email matched no patient.
     */
    protected int accessRequest(User fromCaregiver, User forPatient, String patientEmail, String status) {
        Integer id = jdbc.queryForObject("INSERT INTO caregiver_access_requests (caregiver_system_id, "
                        + "patient_system_id, patient_email, relationship, access_level, status, created_at) "
                        + "VALUES (?, ?, ?, 'daughter', 'VIEW_ONLY', ?, NOW()) RETURNING request_id", Integer.class,
                fromCaregiver.getSystemId(), forPatient == null ? null : forPatient.getSystemId(), patientEmail,
                status);
        return id == null ? 0 : id;
    }

    protected String accessRequestStatus(int requestId) {
        return jdbc.queryForObject("SELECT status FROM caregiver_access_requests WHERE request_id = ?", String.class,
                requestId);
    }

    protected long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    /** The {@code ACCOUNT_DEACTIVATION_EFFECTS} rows about the account, oldest first. */
    protected List<Map<String, Object>> effectsRows(String targetSystemId) {
        return jdbc.queryForList("SELECT system_id, actor_role, resource_type, resource_id, patient_system_id, "
                + "target_system_id, http_method, request_path, status, ip_address, user_agent, error_message, timestamp "
                + "FROM audit_log WHERE action = ? AND target_system_id = ? ORDER BY audit_id", EFFECTS, targetSystemId);
    }

    /** The single effects row about the account. */
    protected Map<String, Object> effectsRow(String targetSystemId) {
        List<Map<String, Object>> rows = effectsRows(targetSystemId);
        assertEquals(1, rows.size(), "one " + EFFECTS + " row for " + targetSystemId + ": " + rows);
        return rows.get(0);
    }

    protected static String summary(String cause, String role, int appointments, int grants, int reminders,
                                     int accessRequests, int referrals, String failed) {
        return "cause=" + cause + "; role=" + role + "; appointmentsCancelled=" + appointments
                + "; grantsRevoked=" + grants + "; remindersDeactivated=" + reminders
                + "; accessRequestsCancelled=" + accessRequests + "; pendingReferrals=" + referrals
                + "; failed=" + failed;
    }

    protected void deactivate(User target, String reason) throws Exception {
        MvcResult result = doPost("/api/admin/users/" + target.getSystemId() + "/deactivate", admin,
                Map.of("reason", reason));
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    protected void closeOwnAccount(User user) throws Exception {
        MvcResult result = doPost("/api/users/me/close", user, Map.of("password", PASSWORD, "reason", "Moving away"));
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    protected boolean isActive(User user) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT is_active FROM users WHERE system_id = ?",
                Boolean.class, user.getSystemId()));
    }

    /** A care-team conversation about the patient, started by the doctor, with the caregiver added by the patient. */
    protected int careTeamThreadWithCaregiver(User forPatient, User withDoctor, User withCaregiver) throws Exception {
        MvcResult created = doPost(THREADS, withDoctor, Map.of(
                "patientSystemId", forPatient.getSystemId(), "type", "CARE_TEAM", "subject", "Weekly check-in",
                "memberSystemIds", List.of(), "clientThreadId", clientId()));
        assertEquals(201, created.getResponse().getStatus(), created.getResponse().getContentAsString());
        int threadId = json(created).path("threadId").asInt();
        MvcResult added = doPost(THREADS + "/" + threadId + "/members", forPatient,
                Map.of("systemIds", List.of(withCaregiver.getSystemId())));
        assertEquals(200, added.getResponse().getStatus(), added.getResponse().getContentAsString());
        return threadId;
    }

    protected String removalReason(int threadId, User member) {
        return jdbc.queryForObject("SELECT removal_reason FROM thread_members WHERE thread_id = ? AND system_id = ?",
                String.class, threadId, member.getSystemId());
    }
}
