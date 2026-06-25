package com.auth.server.repository;

import com.auth.server.model.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findByUserId(Integer userId);
    List<AuditLog> findByResourceTypeAndResourceId(String resourceType, Integer resourceId);
    List<AuditLog> findByTimestampBetween(LocalDateTime start, LocalDateTime end);
}
