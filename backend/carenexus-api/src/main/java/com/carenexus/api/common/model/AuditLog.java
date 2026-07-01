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
    private Long auditId;

    @Column(name = "user_id")
    private Integer userId;

    @Column(name = "action", nullable = false, length = 50)
    private String action;  // "LOGIN", "READ", "CREATE", "UPDATE", "DELETE", "ACCESS_DENIED"

    @Column(name = "resource_type", length = 50)
    private String resourceType;  // "PATIENT", "MEDICAL_RECORD", "PRESCRIPTION", etc.

    @Column(name = "resource_id")
    private Integer resourceId;

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
        timestamp = LocalDateTime.now();
    }
}
