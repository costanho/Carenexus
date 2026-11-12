package com.carenexus.api.common.service;

import com.carenexus.api.common.config.UserContext;
import com.carenexus.api.common.model.AuditLog;
import com.carenexus.api.common.repository.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Locale;
import java.util.Set;

/**
 * Writes rows to audit_log.
 *
 * Transaction rules:
 * - Success events (login, logout, clinical access) join the caller's transaction, so a business
 *   operation that rolls back does not leave a "CREATE succeeded" audit row behind. When the caller's
 *   transaction is read-only (e.g. a @Transactional(readOnly = true) service) or there is none,
 *   logClinicalAccess writes in its own transaction instead.
 * - Failure / denial events (failed login, access denied, clinical failure) run in their own transaction
 *   (REQUIRES_NEW), because the caller is about to throw and would otherwise roll the audit row back.
 *
 * Request context (IP, user agent, HTTP method, path) is read from the current request when there is one.
 * Request bodies are never logged.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AuditService {

    private static final Set<String> CLINICAL_ACTIONS = Set.of("READ", "CREATE", "UPDATE", "DELETE");
    private static final int MAX_ERROR_LENGTH = 500;

    private final AuditLogRepository auditLogRepository;
    private final UserContext userContext;
    private final PlatformTransactionManager transactionManager;

    // ==================== AUTH EVENTS ====================

    /**
     * Log successful login
     */
    public void logLogin(String userSystemId, String ipAddress, String userAgent) {
        AuditLog log = withRequestContext(AuditLog.builder()
                .systemId(userSystemId)
                .action("LOGIN")
                .status("SUCCESS")
                .ipAddress(ipAddress)
                .userAgent(userAgent))
                .build();
        auditLogRepository.save(log);
    }

    /**
     * Log failed login attempt. Runs in its own transaction so the row survives the login rollback.
     * system_id stays NULL (the account may not exist); the email is not stored.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logFailedLogin(String email, String ipAddress, String userAgent, String reason) {
        AuditLog log = withRequestContext(AuditLog.builder()
                .action("LOGIN")
                .status("FAILURE")
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .errorMessage(truncate(reason)))
                .build();
        auditLogRepository.save(log);
    }

    /**
     * Log logout
     */
    public void logLogout(String userSystemId, String ipAddress) {
        AuditLog log = withRequestContext(AuditLog.builder()
                .systemId(userSystemId)
                .action("LOGOUT")
                .status("SUCCESS")
                .ipAddress(ipAddress))
                .build();
        auditLogRepository.save(log);
    }

    // ==================== CLINICAL AUDIT ====================

    /**
     * Record a successful read or write of patient data by the current caller.
     * Call it AFTER the operation succeeded (inside the same service method / transaction).
     *
     * @param action          READ | CREATE | UPDATE | DELETE
     * @param resourceType    e.g. MEDICAL_RECORD, LAB_RESULT, IMAGING_RESULT, PRESCRIPTION, CONSULTATION,
     *                        APPOINTMENT, PROFILE, DEPENDENT_ACCESS, EMERGENCY_CONTACT
     * @param resourceId      numeric id of the row, or null for list reads
     * @param patientSystemId the patient whose data was touched (required for clinical data)
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    public void logClinicalAccess(String action, String resourceType, Integer resourceId, String patientSystemId) {
        AuditLog entry = clinicalEntry(action, resourceType, resourceId, patientSystemId, "SUCCESS", null);
        boolean writableTx = TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        if (writableTx || transactionManager == null) {
            auditLogRepository.save(entry);
            return;
        }
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNew.executeWithoutResult(status -> auditLogRepository.save(entry));
    }

    /**
     * Record a failed clinical operation (e.g. validation error after the access check passed).
     * Runs in its own transaction so it survives the caller's rollback.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logClinicalFailure(String action, String resourceType, Integer resourceId, String patientSystemId, String reason) {
        auditLogRepository.save(clinicalEntry(action, resourceType, resourceId, patientSystemId, "FAILURE", reason));
    }

    /**
     * Legacy: log resource access (READ). Prefer {@link #logClinicalAccess}.
     */
    public void logResourceAccess(String userSystemId, String resourceType, Integer resourceId, String ipAddress) {
        AuditLog log = withRequestContext(AuditLog.builder()
                .systemId(userSystemId)
                .action("READ")
                .resourceType(resourceType)
                .resourceId(resourceId)
                .status("SUCCESS")
                .ipAddress(ipAddress))
                .build();
        auditLogRepository.save(log);
    }

    /**
     * Legacy: log resource modification (CREATE, UPDATE, DELETE). Prefer {@link #logClinicalAccess}.
     */
    public void logResourceModification(String userSystemId, String action, String resourceType, Integer resourceId, String ipAddress) {
        AuditLog log = withRequestContext(AuditLog.builder()
                .systemId(userSystemId)
                .action(action)  // CREATE, UPDATE, DELETE
                .resourceType(resourceType)
                .resourceId(resourceId)
                .status("SUCCESS")
                .ipAddress(ipAddress))
                .build();
        auditLogRepository.save(log);
    }

    // ==================== DENIALS ====================

    /**
     * Log access denied. Runs in its own transaction because the caller throws right after.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logAccessDenied(String userSystemId, String resourceType, Integer resourceId, String ipAddress, String reason) {
        logAccessDenied(userSystemId, resourceType, resourceId, null, ipAddress, reason);
    }

    /**
     * Log access denied for a patient-scoped resource. Runs in its own transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logAccessDenied(String userSystemId, String resourceType, Integer resourceId,
                                String patientSystemId, String ipAddress, String reason) {
        AuditLog log = withRequestContext(AuditLog.builder()
                .systemId(userSystemId)
                .actorRole(userContext.getCurrentUserRole())
                .action("ACCESS_DENIED")
                .resourceType(resourceType)
                .resourceId(resourceId)
                .patientSystemId(patientSystemId)
                .status("FAILURE")
                .ipAddress(isUnknownIp(ipAddress) ? null : ipAddress)
                .errorMessage(truncate(reason)))
                .build();
        auditLogRepository.save(log);
    }

    // ==================== HELPERS ====================

    private AuditLog clinicalEntry(String action, String resourceType, Integer resourceId,
                                   String patientSystemId, String status, String reason) {
        String normalizedAction = action == null ? "READ" : action.toUpperCase(Locale.ROOT);
        if (!CLINICAL_ACTIONS.contains(normalizedAction)) {
            throw new IllegalArgumentException("Unsupported audit action: " + action);
        }
        return withRequestContext(AuditLog.builder()
                .systemId(userContext.getCurrentUserSystemId())
                .actorRole(userContext.getCurrentUserRole())
                .action(normalizedAction)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .patientSystemId(patientSystemId)
                .status(status)
                .errorMessage(truncate(reason)))
                .build();
    }

    /**
     * Fills ip / user agent / method / path from the current HTTP request, without overriding values
     * the caller already supplied.
     */
    private AuditLog.AuditLogBuilder withRequestContext(AuditLog.AuditLogBuilder builder) {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return builder;
        }
        AuditLog probe = builder.build();
        if (isUnknownIp(probe.getIpAddress())) {
            builder.ipAddress(truncate(request.getRemoteAddr(), 45));
        }
        if (probe.getUserAgent() == null) {
            builder.userAgent(truncate(request.getHeader("User-Agent"), 500));
        }
        builder.httpMethod(truncate(request.getMethod(), 10));
        builder.requestPath(truncate(request.getRequestURI(), 255));
        return builder;
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest();
        }
        return null;
    }

    private static boolean isUnknownIp(String ip) {
        return ip == null || ip.isBlank() || "0.0.0.0".equals(ip);
    }

    private static String truncate(String value) {
        return truncate(value, MAX_ERROR_LENGTH);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
