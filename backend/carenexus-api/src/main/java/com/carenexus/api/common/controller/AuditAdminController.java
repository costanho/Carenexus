package com.carenexus.api.common.controller;

import com.carenexus.api.auth.controller.UserAdminController.AdminPage;
import com.carenexus.api.common.annotation.RequireAdmin;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.model.AuditLog;
import com.carenexus.api.common.repository.AuditLogRepository;
import com.carenexus.api.common.service.AuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;

/**
 * Read-only audit log access for administrators (ADMIN only; audit rows are never changed through the API).
 *
 * - GET /api/admin/audit-logs?systemId=&patientSystemId=&action=&resourceType=&page=&size=
 *   newest first. systemId = the actor, patientSystemId = the patient whose data was touched.
 * - GET /api/admin/audit-logs/{auditId}
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AuditAdminController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AuditLogRepository auditLogRepository;
    private final AuthorizationService authorizationService;

    @GetMapping("/audit-logs")
    @RequireAdmin(allowFacilityAdmin = false)
    public ResponseEntity<AdminPage<AuditLog>> getAuditLogs(
            @RequestParam(required = false) String systemId,
            @RequestParam(required = false) String patientSystemId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        authorizationService.requireAdmin();

        AuditLog probe = new AuditLog();
        probe.setSystemId(blankToNull(systemId));
        probe.setPatientSystemId(blankToNull(patientSystemId));
        probe.setAction(upperOrNull(action));
        probe.setResourceType(upperOrNull(resourceType));

        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "timestamp").and(Sort.by(Sort.Direction.DESC, "auditId")));
        return ResponseEntity.ok(AdminPage.of(auditLogRepository.findAll(Example.of(probe), pageRequest)));
    }

    @GetMapping("/audit-logs/{auditId}")
    @RequireAdmin(allowFacilityAdmin = false)
    public ResponseEntity<AuditLog> getAuditLogEntry(@PathVariable Long auditId) {
        authorizationService.requireAdmin();
        return ResponseEntity.ok(auditLogRepository.findById(auditId)
                .orElseThrow(() -> new ResourceNotFoundException("Audit log entry not found")));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String upperOrNull(String value) {
        String trimmed = blankToNull(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }
}
