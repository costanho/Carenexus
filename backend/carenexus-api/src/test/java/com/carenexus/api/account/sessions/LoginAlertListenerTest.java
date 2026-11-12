package com.carenexus.api.account.sessions;

import com.carenexus.api.account.sessions.SessionFixtures.Session;
import com.carenexus.api.auth.support.AuthApi;
import com.carenexus.api.common.event.UserLoggedInEvent;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The login alert: one unread SYSTEM notification (category LOGIN_ALERT) when an account is signed in to from a
 * device signature none of its earlier stored sessions had, and only while the loginAlerts setting is on.
 *
 * Sign-ins go through the real login endpoint, so the listener runs after the sign-in really committed.
 */
class LoginAlertListenerTest extends CommittedIntegrationTest {

    private static final String TITLE = "New sign-in to your account";

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private SessionFixtures sessions;

    @BeforeEach
    void setUpSessions() {
        sessions = new SessionFixtures(new AuthApi(mockMvc, objectMapper));
    }

    @Test
    void firstSignInAndSameDevice_raiseNothing() throws Exception {
        sessions.signIn(patient, "Web", "Chrome on macOS");
        sessions.signIn(patient, "Web", "Chrome on macOS");
        // Comparison ignores case and surrounding white space
        sessions.signIn(patient, " WEB ", "chrome ON macos ");

        assertEquals(0, loginAlerts(patient.getSystemId()).size());
    }

    @Test
    void newDevice_raisesExactlyOneUnreadAlert_pointingAtTheSecuritySettings() throws Exception {
        sessions.signIn(patient, "Web", "Chrome on macOS");
        Session firefox = sessions.signIn(patient, "Web", "Firefox on Windows");

        List<Map<String, Object>> alerts = loginAlerts(patient.getSystemId());
        assertEquals(1, alerts.size());
        Map<String, Object> alert = alerts.get(0);
        assertEquals("SYSTEM", alert.get("type"));
        assertEquals("LOGIN_ALERT", alert.get("category"));
        assertEquals("NORMAL", alert.get("priority"));
        assertEquals(TITLE, alert.get("title"));
        assertEquals("Your account was signed in to on a new device (Web). If this was not you, change your password.",
                alert.get("message"));
        assertEquals("ACCOUNT_SECURITY", alert.get("target"));
        assertEquals("/dashboard/patient?section=profile-settings&tab=security", alert.get("action_url"));
        assertEquals("SESSION", alert.get("resource_type"));
        assertEquals(null, alert.get("resource_id"));
        assertEquals(null, alert.get("actor_system_id"));
        assertEquals(null, alert.get("patient_system_id"));
        assertEquals(null, alert.get("expires_at"));
        assertEquals(Boolean.FALSE, alert.get("is_read"));
        assertEquals("LOGIN_ALERT:" + firefox.sessionId(), alert.get("dedupe_key"));

        // Signing in from that browser again is a known device now
        sessions.signIn(patient, "Web", "Firefox on Windows");
        assertEquals(1, loginAlerts(patient.getSystemId()).size());
    }

    @Test
    void deviceLabel_isOnlyTheKnownDeviceType_neverTheFreeTextOrAddress() throws Exception {
        sessions.signIn(doctor, "Web", "Chrome on macOS");
        sessions.signIn(doctor, "ios", "Dr Amara's iPhone");
        sessions.signIn(doctor, "SmartFridge", "Kitchen");

        List<Map<String, Object>> alerts = loginAlerts(doctor.getSystemId());
        assertEquals(2, alerts.size());
        assertEquals("Your account was signed in to on a new device (iOS). If this was not you, change your password.",
                alerts.get(0).get("message"));
        assertEquals("Your account was signed in to on a new device. If this was not you, change your password.",
                alerts.get(1).get("message"));
        assertEquals("/dashboard/doctor?section=profile&tab=security", alerts.get(0).get("action_url"));
        alerts.forEach(alert -> assertTrue(!String.valueOf(alert.get("message")).contains("iPhone")
                && !String.valueOf(alert.get("message")).contains("Kitchen")));
    }

    @Test
    void signInsWithoutDeviceFields_neverAlert_andAreNotKnownDevices() throws Exception {
        sessions.signIn(caregiver, null, null);
        sessions.signIn(caregiver, null, null);
        // The first sign-in that sends device fields: earlier sessions have no signature, so it is a first sign-in
        sessions.signIn(caregiver, "Web", "Chrome on macOS");
        sessions.signIn(caregiver, null, null);

        assertEquals(0, loginAlerts(caregiver.getSystemId()).size());

        sessions.signIn(caregiver, "Android", null);
        List<Map<String, Object>> alerts = loginAlerts(caregiver.getSystemId());
        assertEquals(1, alerts.size());
        assertEquals("/dashboard/proxy?section=profile-settings&tab=security", alerts.get(0).get("action_url"));
    }

    @Test
    void endedSessionsStillCountAsKnownDevices() throws Exception {
        Session chrome = sessions.signIn(admin, "Web", "Chrome on macOS");
        new AuthApi(mockMvc, objectMapper).logoutWithToken(chrome.refreshToken());

        sessions.signIn(admin, "Web", "Chrome on macOS");

        assertEquals(0, loginAlerts(admin.getSystemId()).size());
    }

    @Test
    void noAlert_whenLoginAlertsAreSwitchedOff() throws Exception {
        jdbc.update("INSERT INTO user_settings (system_id, login_alerts) VALUES (?, FALSE)", otherPatient.getSystemId());

        sessions.signIn(otherPatient, "Web", "Chrome on macOS");
        sessions.signIn(otherPatient, "Web", "Firefox on Windows");

        assertEquals(0, loginAlerts(otherPatient.getSystemId()).size());

        // Switched back on: the next new device alerts again
        jdbc.update("UPDATE user_settings SET login_alerts = TRUE WHERE system_id = ?", otherPatient.getSystemId());
        sessions.signIn(otherPatient, "iOS", "iPhone");
        assertEquals(1, loginAlerts(otherPatient.getSystemId()).size());
    }

    @Test
    void alertsGoOnlyToTheAccountThatSignedIn() throws Exception {
        grantApprovedAccess(caregiver, patient);
        sessions.signIn(patient, "Web", "Chrome on macOS");
        sessions.signIn(patient, "Web", "Firefox on Windows");

        assertEquals(1, loginAlerts(patient.getSystemId()).size());
        Integer others = jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE category = 'LOGIN_ALERT' "
                + "AND system_id <> ?", Integer.class, patient.getSystemId());
        assertEquals(0, others);
    }

    @Test
    void sameEventTwice_raisesOneAlertPerSession() throws Exception {
        sessions.signIn(otherDoctor, "Web", "Chrome on macOS");
        sessions.signIn(otherDoctor, "Web", "Firefox on Windows");
        assertEquals(1, loginAlerts(otherDoctor.getSystemId()).size());

        // A redelivered event for the same sign-in is dropped by the dedupe key
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> eventPublisher.publishEvent(
                new UserLoggedInEvent(otherDoctor.getSystemId(), "DOCTOR", "127.0.0.1", "Web", "Firefox on Windows")));

        assertEquals(1, loginAlerts(otherDoctor.getSystemId()).size());
    }

    @Test
    void eventForAnAccountWithoutStoredSessions_raisesNothingAndDoesNotFail() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> eventPublisher.publishEvent(
                new UserLoggedInEvent(otherCaregiver.getSystemId(), "CAREGIVER", "127.0.0.1", "Web", "Chrome")));

        assertEquals(0, loginAlerts(otherCaregiver.getSystemId()).size());
    }

    private List<Map<String, Object>> loginAlerts(String systemId) {
        return jdbc.queryForList("SELECT * FROM notifications WHERE system_id = ? AND category = 'LOGIN_ALERT' "
                + "ORDER BY notification_id", systemId);
    }
}
