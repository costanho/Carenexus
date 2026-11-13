package com.carenexus.api.common.service;

import com.carenexus.api.common.config.UserContext;
import com.carenexus.api.common.model.AuditLog;
import com.carenexus.api.common.repository.AuditLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AuditService (repository mocked; no database needed). The REQUIRES_NEW behaviour itself is
 * declared with @Transactional and asserted here by reflection; it was verified end-to-end against PostgreSQL
 * (a failed login row survives the login rollback).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class AuditServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private UserContext userContext;

    @Mock
    private PlatformTransactionManager transactionManager;

    @InjectMocks
    private AuditService auditService;

    @BeforeEach
    void setUp() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/lab-results/patient/PAT-00001");
        request.setRemoteAddr("203.0.113.42");
        request.addHeader("User-Agent", "JUnit");
        request.addHeader("Authorization", "Bearer secret-token");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(userContext.getCurrentUserSystemId()).thenReturn("DOC-00001");
        when(userContext.getCurrentUserRole()).thenReturn("DOCTOR");
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private AuditLog saved() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    public void logLogin_keepsCallerIpAndAddsRequestContext() {
        auditService.logLogin("PAT-00001", "192.168.1.100", "Mozilla/5.0");

        AuditLog log = saved();
        assertEquals("PAT-00001", log.getSystemId());
        assertEquals("LOGIN", log.getAction());
        assertEquals("SUCCESS", log.getStatus());
        assertEquals("192.168.1.100", log.getIpAddress());
        assertEquals("Mozilla/5.0", log.getUserAgent());
        assertEquals("GET", log.getHttpMethod());
    }

    @Test
    public void logFailedLogin_hasNoSystemIdAndDoesNotStoreEmail() {
        auditService.logFailedLogin("linda@example.com", "192.168.1.100", "Mozilla/5.0", "Invalid password");

        AuditLog log = saved();
        assertNull(log.getSystemId());
        assertEquals("FAILURE", log.getStatus());
        assertEquals("Invalid password", log.getErrorMessage());
        assertFalse(String.valueOf(log.getErrorMessage()).contains("linda@example.com"));
    }

    @Test
    public void logAccessDenied_fillsUnknownIpFromRequest() {
        auditService.logAccessDenied("PAT-00001", "MEDICAL_RECORD", 9, "PAT-00002", "0.0.0.0", "Patient accessing other patient's data");

        AuditLog log = saved();
        assertEquals("ACCESS_DENIED", log.getAction());
        assertEquals("FAILURE", log.getStatus());
        assertEquals("PAT-00002", log.getPatientSystemId());
        assertEquals("203.0.113.42", log.getIpAddress());
        assertEquals("/api/lab-results/patient/PAT-00001", log.getRequestPath());
    }

    @Test
    public void logClinicalAccess_recordsActorPatientAndRequest_withoutHeadersOrBody() {
        auditService.logClinicalAccess("read", "LAB_RESULT", null, "PAT-00001");

        AuditLog log = saved();
        assertEquals("DOC-00001", log.getSystemId());
        assertEquals("DOCTOR", log.getActorRole());
        assertEquals("READ", log.getAction());
        assertEquals("LAB_RESULT", log.getResourceType());
        assertNull(log.getResourceId());
        assertEquals("PAT-00001", log.getPatientSystemId());
        assertEquals("SUCCESS", log.getStatus());
        assertEquals("203.0.113.42", log.getIpAddress());
        assertEquals("JUnit", log.getUserAgent());
        assertEquals("GET", log.getHttpMethod());
        assertNull(log.getErrorMessage());
    }

    @Test
    public void logClinicalAccess_outsideWritableTransaction_usesOwnTransaction() {
        auditService.logClinicalAccess("CREATE", "PRESCRIPTION", 5, "PAT-00001");

        verify(transactionManager).getTransaction(argThatRequiresNew());
        verify(transactionManager).commit(any());
        assertEquals(5, saved().getResourceId());
    }

    @Test
    public void logClinicalAccess_rejectsUnknownAction() {
        assertThrows(IllegalArgumentException.class,
                () -> auditService.logClinicalAccess("EXPORT", "LAB_RESULT", 1, "PAT-00001"));
        verifyNoInteractions(auditLogRepository);
    }

    @Test
    public void logClinicalFailure_truncatesLongReasons() {
        auditService.logClinicalFailure("UPDATE", "MEDICAL_RECORD", 3, "PAT-00001", "x".repeat(800));

        AuditLog log = saved();
        assertEquals("FAILURE", log.getStatus());
        assertEquals(500, log.getErrorMessage().length());
    }

    @Test
    public void worksWithoutHttpRequest() {
        RequestContextHolder.resetRequestAttributes();
        auditService.logLogout("PAT-00001", "10.0.0.1");

        AuditLog log = saved();
        assertEquals("10.0.0.1", log.getIpAddress());
        assertNull(log.getRequestPath());
    }

    @Test
    public void failureAndDenialMethods_runInTheirOwnTransaction() throws Exception {
        assertRequiresNew(AuditService.class.getMethod("logFailedLogin", String.class, String.class, String.class, String.class));
        assertRequiresNew(AuditService.class.getMethod("logAccessDenied", String.class, String.class, Integer.class, String.class, String.class));
        assertRequiresNew(AuditService.class.getMethod("logAccessDenied", String.class, String.class, Integer.class, String.class, String.class, String.class));
        assertRequiresNew(AuditService.class.getMethod("logClinicalFailure", String.class, String.class, Integer.class, String.class, String.class));
    }

    private static void assertRequiresNew(Method method) {
        Transactional tx = method.getAnnotation(Transactional.class);
        assertNotNull(tx, method.getName() + " must be @Transactional");
        assertEquals(Propagation.REQUIRES_NEW, tx.propagation(), method.getName());
    }

    private static TransactionDefinition argThatRequiresNew() {
        return org.mockito.ArgumentMatchers.argThat(def ->
                def != null && def.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ==================== LIST READS ====================

    private java.util.List<AuditLog> allSaved(int times) {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(times)).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    public void logListRead_writesOneReadRowPerDistinctPatient() {
        record Row(String patient) { }
        auditService.logListRead("PRESCRIPTION",
                java.util.List.of(new Row("PAT-00001"), new Row("PAT-00002"), new Row("PAT-00001"), new Row(null)),
                Row::patient);

        java.util.List<AuditLog> rows = allSaved(2);
        assertEquals(java.util.List.of("PAT-00001", "PAT-00002"),
                rows.stream().map(AuditLog::getPatientSystemId).toList());
        for (AuditLog row : rows) {
            assertEquals("READ", row.getAction());
            assertEquals("PRESCRIPTION", row.getResourceType());
            assertNull(row.getResourceId());
            assertEquals("SUCCESS", row.getStatus());
            assertEquals("DOC-00001", row.getSystemId());
            assertEquals("DOCTOR", row.getActorRole());
        }
    }

    @Test
    public void logListRead_emptyOrNull_writesNothing() {
        auditService.logListRead("PRESCRIPTION", java.util.List.<String>of(), s -> s);
        auditService.logListRead("PRESCRIPTION", null, (String s) -> s);
        verify(auditLogRepository, never()).save(any());
    }

    // ==================== ACCOUNT EVENTS ====================

    @Test
    public void logAccountEvent_recordsActorTargetActionAndRequestContext() {
        when(userContext.getCurrentUserSystemId()).thenReturn("ADM-00001");
        when(userContext.getCurrentUserRole()).thenReturn("ADMIN");

        auditService.logAccountEvent("ADM-00001", "doctor_verify", "doctor_profile", "DOC-00007", null);

        AuditLog log = saved();
        assertEquals("ADM-00001", log.getSystemId());
        assertEquals("ADMIN", log.getActorRole());
        assertEquals("DOCTOR_VERIFY", log.getAction());
        assertEquals("DOCTOR_PROFILE", log.getResourceType());
        assertEquals("DOC-00007", log.getTargetSystemId());
        assertNull(log.getPatientSystemId(), "the target is not a patient");
        assertEquals("SUCCESS", log.getStatus());
        assertEquals("203.0.113.42", log.getIpAddress());
        assertEquals("/api/lab-results/patient/PAT-00001", log.getRequestPath());
    }

    @Test
    public void logAccountEvent_patientTarget_isAlsoIndexedAsThePatient_andActorRoleComesFromTheIdWhenNotTheCaller() {
        // the signed-in caller (setUp) is DOC-00001; the actor here is the patient acting through a reset link
        auditService.logAccountEvent("PAT-00009", "PASSWORD_RESET", "ACCOUNT", "PAT-00009", "SUCCESS");

        AuditLog log = saved();
        assertEquals("PAT-00009", log.getSystemId());
        assertEquals("PATIENT", log.getActorRole());
        assertEquals("PAT-00009", log.getTargetSystemId());
        assertEquals("PAT-00009", log.getPatientSystemId());
    }

    @Test
    public void logAccountEvent_anonymousActorAndFailure() {
        auditService.logAccountEvent(null, "PASSWORD_RESET", "ACCOUNT", null, "failure");

        AuditLog log = saved();
        assertNull(log.getSystemId());
        assertNull(log.getActorRole());
        assertNull(log.getTargetSystemId());
        assertEquals("FAILURE", log.getStatus());
        // a failure row is written in its own transaction so it survives the caller's rollback
        verify(transactionManager).getTransaction(argThat(def ->
                def.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    @Test
    public void logAccountEvent_rejectsMalformedActionsAndStatuses() {
        assertThrows(IllegalArgumentException.class,
                () -> auditService.logAccountEvent("ADM-00001", "password reset!", "ACCOUNT", "PAT-00001", "SUCCESS"));
        assertThrows(IllegalArgumentException.class,
                () -> auditService.logAccountEvent("ADM-00001", null, "ACCOUNT", "PAT-00001", "SUCCESS"));
        assertThrows(IllegalArgumentException.class,
                () -> auditService.logAccountEvent("ADM-00001", "ROLE_CHANGE", "ACCOUNT", "PAT-00001", "MAYBE"));
        verify(auditLogRepository, never()).save(any());
    }
}
