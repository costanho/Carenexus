package com.carenexus.api.common.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "audit_id")
    private Long auditId;  // BIGINT since V14

    @Column(name = "system_id", length = 20)
    private String systemId;

    @Column(name = "action", nullable = false, length = 50)
    private String action;  // "LOGIN", "READ", "CREATE", "UPDATE", "DELETE", "ACCESS_DENIED"

    @Column(name = "resource_type", length = 50)
    private String resourceType;  // "PATIENT", "MEDICAL_RECORD", "PRESCRIPTION", etc.

    @Column(name = "resource_id")
    private Integer resourceId;

    @Column(name = "patient_system_id", length = 20)
    private String patientSystemId;  // patient whose data was touched (clinical audit), null otherwise

    /** Account an account/security event is about (AuditService.logAccountEvent); null for clinical rows. V23. */
    @Column(name = "target_system_id", length = 20)
    private String targetSystemId;

    @Column(name = "actor_role", length = 20)
    private String actorRole;  // role of the caller at the time of the action

    @Column(name = "http_method", length = 10)
    private String httpMethod;

    @Column(name = "request_path", length = 255)
    private String requestPath;

    @Column(name = "status", nullable = false, length = 20)
    private String status;  // "SUCCESS", "FAILURE"

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;

    @PrePersist
    protected void onCreate() {
        if (timestamp == null) {
            timestamp = LocalDateTime.now();
        }
    }
}
