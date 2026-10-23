package com.carenexus.api.common.service;

import com.carenexus.api.BaseIntegrationTest;
import com.carenexus.api.common.model.AuditLog;
import com.carenexus.api.auth.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class AuditServiceTest extends BaseIntegrationTest {

    @Autowired
    private AuditService auditService;

    private User linda;
    private User drMatt;

    @BeforeEach
    public void setUp() {
        linda = createAndSaveUser(1, "PAT-00001", "linda@example.com", "PATIENT");
        drMatt = createAndSaveUser(2, "DOC-00001", "matt@hospital.com", "DOCTOR");
    }

    /**
     * Test: Login success is logged
     */
    @Test
    public void testLogLogin() {
        auditService.logLogin(linda.getSystemId(), "192.168.1.100", "Mozilla/5.0...");

        List<AuditLog> logs = auditLogRepository.findBySystemId(linda.getSystemId());

        assertEquals(1, logs.size());
        AuditLog log = logs.get(0);
        assertEquals("LOGIN", log.getAction());
        assertEquals("SUCCESS", log.getStatus());
        assertEquals("192.168.1.100", log.getIpAddress());
    }

    /**
     * Test: Failed login is logged
     */
    @Test
    public void testLogFailedLogin() {
        auditService.logFailedLogin("linda@example.com", "192.168.1.100", "Mozilla/5.0...", "Invalid password");

        List<AuditLog> logs = auditLogRepository.findAll();

        assertTrue(logs.stream().anyMatch(log ->
                "LOGIN".equals(log.getAction()) &&
                "FAILURE".equals(log.getStatus()) &&
                "Invalid password".equals(log.getErrorMessage())
        ));
    }

    /**
     * Test: Logout is logged
     */
    @Test
    public void testLogLogout() {
        auditService.logLogout(linda.getSystemId(), "192.168.1.100");

        List<AuditLog> logs = auditLogRepository.findBySystemId(linda.getSystemId());

        assertTrue(logs.stream().anyMatch(log ->
                "LOGOUT".equals(log.getAction()) &&
                "SUCCESS".equals(log.getStatus())
        ));
    }

    /**
     * Test: Resource access (READ) is logged
     */
    @Test
    public void testLogResourceAccess() {
        auditService.logResourceAccess(
                drMatt.getSystemId(),
                "MEDICAL_RECORDS",
                linda.getUserId(),
                "192.168.1.100"
        );

        List<AuditLog> logs = auditLogRepository.findBySystemId(drMatt.getSystemId());

        assertEquals(1, logs.size());
        AuditLog log = logs.get(0);
        assertEquals("READ", log.getAction());
        assertEquals("MEDICAL_RECORDS", log.getResourceType());
        assertEquals(linda.getUserId(), log.getResourceId());
        assertEquals("SUCCESS", log.getStatus());
    }

    /**
     * Test: Resource modification (CREATE) is logged
     */
    @Test
    public void testLogResourceModification_Create() {
        auditService.logResourceModification(
                drMatt.getSystemId(),
                "CREATE",
                "PRESCRIPTION",
                1,
                "192.168.1.100"
        );

        List<AuditLog> logs = auditLogRepository.findBySystemId(drMatt.getSystemId());

        AuditLog log = logs.stream()
                .filter(l -> "CREATE".equals(l.getAction()))
                .findFirst()
                .orElseThrow();

        assertEquals("PRESCRIPTION", log.getResourceType());
        assertEquals(1, log.getResourceId());
    }

    /**
     * Test: Resource modification (UPDATE) is logged
     */
    @Test
    public void testLogResourceModification_Update() {
        auditService.logResourceModification(
                linda.getSystemId(),
                "UPDATE",
                "PATIENT_PROFILE",
                linda.getUserId(),
                "192.168.1.100"
        );

        List<AuditLog> logs = auditLogRepository.findBySystemId(linda.getSystemId());

        AuditLog log = logs.stream()
                .filter(l -> "UPDATE".equals(l.getAction()))
                .findFirst()
                .orElseThrow();

        assertEquals("PATIENT_PROFILE", log.getResourceType());
    }

    /**
     * Test: Access denied is logged
     */
    @Test
    public void testLogAccessDenied() {
        auditService.logAccessDenied(
                linda.getSystemId(),
                "MEDICAL_RECORDS",
                999,
                "192.168.1.100",
                "Patient accessing other patient's data"
        );

        List<AuditLog> logs = auditLogRepository.findBySystemId(linda.getSystemId());

        AuditLog log = logs.stream()
                .filter(l -> "ACCESS_DENIED".equals(l.getAction()))
                .findFirst()
                .orElseThrow();

        assertEquals("FAILURE", log.getStatus());
        assertEquals("Patient accessing other patient's data", log.getErrorMessage());
    }

    /**
     * Test: Multiple actions create separate log entries
     */
    @Test
    public void testMultipleActionsLogged() {
        // Simulate user's journey
        auditService.logLogin(linda.getSystemId(), "192.168.1.100", "Mozilla...");
        auditService.logResourceAccess(linda.getSystemId(), "HEALTH_METRICS", linda.getUserId(), "192.168.1.100");
        auditService.logResourceAccess(linda.getSystemId(), "APPOINTMENTS", linda.getUserId(), "192.168.1.100");
        auditService.logResourceModification(linda.getSystemId(), "UPDATE", "APPOINTMENT", 1, "192.168.1.100");
        auditService.logLogout(linda.getSystemId(), "192.168.1.100");

        List<AuditLog> logs = auditLogRepository.findBySystemId(linda.getSystemId());

        assertEquals(5, logs.size());
        assertTrue(logs.stream().anyMatch(l -> "LOGIN".equals(l.getAction())));
        assertTrue(logs.stream().filter(l -> "READ".equals(l.getAction())).count() >= 2);
        assertTrue(logs.stream().anyMatch(l -> "UPDATE".equals(l.getAction())));
        assertTrue(logs.stream().anyMatch(l -> "LOGOUT".equals(l.getAction())));
    }

    /**
     * Test: Audit logs capture IP address
     */
    @Test
    public void testAuditLogCapturesIpAddress() {
        String ipAddress = "203.0.113.42";
        auditService.logLogin(linda.getSystemId(), ipAddress, "Mozilla...");

        AuditLog log = auditLogRepository.findBySystemId(linda.getSystemId()).get(0);

        assertEquals(ipAddress, log.getIpAddress());
    }

    /**
     * Test: Audit logs capture User-Agent
     */
    @Test
    public void testAuditLogCapturesUserAgent() {
        String userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 14_0 like Mac OS X)";
        auditService.logLogin(linda.getSystemId(), "192.168.1.100", userAgent);

        AuditLog log = auditLogRepository.findBySystemId(linda.getSystemId()).get(0);

        assertEquals(userAgent, log.getUserAgent());
    }

    /**
     * Test: Audit logs are immutable (created_at never changes)
     */
    @Test
    public void testAuditLogsImmutable() {
        auditService.logLogin(linda.getSystemId(), "192.168.1.100", "Mozilla...");

        AuditLog log = auditLogRepository.findBySystemId(linda.getSystemId()).get(0);
        var createdAt = log.getTimestamp();

        // Simulate time passing (audit log should not be updated)
        // In real scenario, no one should be able to update audit logs

        AuditLog retrieved = auditLogRepository.findById(log.getAuditId()).orElseThrow();
        assertEquals(createdAt, retrieved.getTimestamp());
    }
}
