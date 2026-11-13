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

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

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
    private static final Set<String> STATUSES = Set.of("SUCCESS", "FAILURE");
    /** Upper snake case, fits audit_log.action (VARCHAR 50). */
    private static final Pattern ACTION_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{1,49}$");
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
     * Record a successful multi-row read: one READ row per distinct patient in the result (no row for an empty
     * result). Use it for lists that span patients (a doctor's lists, admin lists); a list scoped to one patient
     * is audited with {@link #logClinicalAccess} and that patient's id.
     *
     * @param resourceType the service's RESOURCE constant
     * @param rows         the rows returned to the caller
     * @param patientOf    extracts the patient system id of a row (rows yielding null are skipped)
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    public <T> void logListRead(String resourceType, Collection<T> rows, Function<T, String> patientOf) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        rows.stream()
                .filter(Objects::nonNull)
                .map(patientOf)
                .filter(Objects::nonNull)
                .distinct()
                .forEach(patient -> logClinicalAccess("READ", resourceType, null, patient));
    }

    // ==================== ACCOUNT AND SECURITY EVENTS ====================

    /**
     * Record an account or security event: password reset or change, email verification, MFA enrolment,
     * clinician verification, account closure, role change, settings update, ...
     *
     * A SUCCESS row joins the caller's transaction (it disappears if the change rolls back); a FAILURE row is
     * written in its own transaction so it survives the caller's rollback. Never pass secrets, tokens or message
     * text in any argument.
     *
     * @param actorSystemId  who did it; null when nobody is signed in (for example a reset link used anonymously)
     * @param action         upper snake case, for example PASSWORD_RESET, EMAIL_VERIFIED, MFA_ENABLED, DOCTOR_VERIFY,
     *                       ACCOUNT_CLOSE, ROLE_CHANGE, SETTINGS_UPDATE
     * @param resourceType   for example ACCOUNT, USER_SETTINGS, DOCTOR_PROFILE, SYSTEM_SETTING
     * @param targetSystemId the account the event is about (may equal the actor); null for system-wide events
     * @param status         SUCCESS or FAILURE (null means SUCCESS)
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    public void logAccountEvent(String actorSystemId, String action, String resourceType, String targetSystemId, String status) {
        String normalizedAction = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        if (!ACTION_PATTERN.matcher(normalizedAction).matches()) {
            throw new IllegalArgumentException("Unsupported audit action: " + action);
        }
        String normalizedStatus = status == null || status.isBlank() ? "SUCCESS" : status.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(normalizedStatus)) {
            throw new IllegalArgumentException("Unsupported audit status: " + status);
        }

        AuditLog entry = withRequestContext(AuditLog.builder()
                .systemId(actorSystemId)
                .actorRole(roleOf(actorSystemId))
                .action(normalizedAction)
                .resourceType(resourceType == null ? null : truncate(resourceType.trim().toUpperCase(Locale.ROOT), 50))
                .targetSystemId(targetSystemId)
                // Lets "who touched this patient's account" be answered from the patient index as well.
                .patientSystemId(targetSystemId != null && targetSystemId.startsWith("PAT-") ? targetSystemId : null)
                .status(normalizedStatus))
                .build();

        boolean failure = "FAILURE".equals(normalizedStatus);
        boolean writableTx = TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        if (transactionManager == null || (writableTx && !failure)) {
            auditLogRepository.save(entry);
            return;
        }
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNew.executeWithoutResult(tx -> auditLogRepository.save(entry));
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

    /**
     * Role recorded for the actor of an account event: the caller's role when the actor is the signed-in caller,
     * otherwise derived from the system id prefix (jobs, anonymous token flows acting for an account).
     */
    private String roleOf(String actorSystemId) {
        if (actorSystemId == null) {
            return null;
        }
        if (actorSystemId.equals(userContext.getCurrentUserSystemId()) && userContext.getCurrentUserRole() != null) {
            return userContext.getCurrentUserRole();
        }
        if (actorSystemId.startsWith("PAT-")) return "PATIENT";
        if (actorSystemId.startsWith("DOC-")) return "DOCTOR";
        if (actorSystemId.startsWith("CGV-")) return "CAREGIVER";
        if (actorSystemId.startsWith("ADM-")) return "ADMIN";
        return null;
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
