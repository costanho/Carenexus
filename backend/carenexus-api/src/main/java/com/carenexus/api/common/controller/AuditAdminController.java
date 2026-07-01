package com.carenexus.api.common.controller;

import com.carenexus.api.common.annotation.RequireAdmin;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AuditAdminController {

    /**
     * GET /api/admin/audit-logs
     * Only ADMIN can view audit logs (HIPAA requirement)
     * Logs are immutable and should only be viewable by admins
     */
    @GetMapping("/audit-logs")
    @RequireAdmin(allowFacilityAdmin = false)
    public ResponseEntity<?> getAuditLogs(
            @RequestParam(required = false) Integer userId,
            @RequestParam(required = false) String action) {
        // Return filtered audit logs
        return ResponseEntity.ok().body("Audit logs");
    }

    /**
     * GET /api/admin/audit-logs/{auditId}
     * View specific audit log entry
     * Immutable - for HIPAA compliance
     */
    @GetMapping("/audit-logs/{auditId}")
    @RequireAdmin(allowFacilityAdmin = false)
    public ResponseEntity<?> getAuditLogEntry(@PathVariable Long auditId) {
        return ResponseEntity.ok().body("Audit log entry " + auditId);
    }

    /**
     * GET /api/admin/system-stats
     * System-wide statistics - only ADMIN
     */
    @GetMapping("/system-stats")
    @RequireAdmin(allowFacilityAdmin = true)  // Allow FACILITY_ADMIN to view
    public ResponseEntity<?> getSystemStats() {
        return ResponseEntity.ok().body("System statistics");
    }
}
