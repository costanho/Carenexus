package com.carenexus.api.common.service;

import com.carenexus.api.common.model.AuditLog;
import com.carenexus.api.common.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    /**
     * Log successful login
     */
    public void logLogin(Integer userId, String ipAddress, String userAgent) {
        AuditLog log = AuditLog.builder()
                .userId(userId)
                .action("LOGIN")
                .status("SUCCESS")
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .build();
        auditLogRepository.save(log);
    }

    /**
     * Log failed login attempt
     */
    public void logFailedLogin(String email, String ipAddress, String userAgent, String reason) {
        AuditLog log = AuditLog.builder()
                .action("LOGIN")
                .status("FAILURE")
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .errorMessage(reason)
                .build();
        auditLogRepository.save(log);
    }

    /**
     * Log logout
     */
    public void logLogout(Integer userId, String ipAddress) {
        AuditLog log = AuditLog.builder()
                .userId(userId)
                .action("LOGOUT")
                .status("SUCCESS")
                .ipAddress(ipAddress)
                .build();
        auditLogRepository.save(log);
    }

    /**
     * Log resource access (READ)
     */
    public void logResourceAccess(Integer userId, String resourceType, Integer resourceId, String ipAddress) {
        AuditLog log = AuditLog.builder()
                .userId(userId)
                .action("READ")
                .resourceType(resourceType)
                .resourceId(resourceId)
                .status("SUCCESS")
                .ipAddress(ipAddress)
                .build();
        auditLogRepository.save(log);
    }

    /**
     * Log resource modification (CREATE, UPDATE, DELETE)
     */
    public void logResourceModification(Integer userId, String action, String resourceType, Integer resourceId, String ipAddress) {
        AuditLog log = AuditLog.builder()
                .userId(userId)
                .action(action)  // CREATE, UPDATE, DELETE
                .resourceType(resourceType)
                .resourceId(resourceId)
                .status("SUCCESS")
                .ipAddress(ipAddress)
                .build();
        auditLogRepository.save(log);
    }

    /**
     * Log access denied
     */
    public void logAccessDenied(Integer userId, String resourceType, Integer resourceId, String ipAddress, String reason) {
        AuditLog log = AuditLog.builder()
                .userId(userId)
                .action("ACCESS_DENIED")
                .resourceType(resourceType)
                .resourceId(resourceId)
                .status("FAILURE")
                .ipAddress(ipAddress)
                .errorMessage(reason)
                .build();
        auditLogRepository.save(log);
    }
}
