package com.auth.server.controller;

import com.auth.server.annotation.RequireAdmin;
import com.auth.server.annotation.RequireRole;
import com.auth.server.config.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final UserContext userContext;

    /**
     * GET /api/admin/users
     * Only ADMIN can list all users
     */
    @GetMapping("/users")
    @RequireAdmin(allowFacilityAdmin = false)
    public ResponseEntity<?> getAllUsers() {
        // Only ADMIN role can access
        return ResponseEntity.ok().body("All users in system");
    }

    /**
     * POST /api/admin/users/{userId}/deactivate
     * Only ADMIN can deactivate users
     */
    @PostMapping("/users/{userId}/deactivate")
    @RequireAdmin(allowFacilityAdmin = false)
    public ResponseEntity<?> deactivateUser(@PathVariable Integer userId) {
        Integer adminId = userContext.getCurrentUserId();
        // Deactivate user and log action
        return ResponseEntity.ok().body("User " + userId + " deactivated by admin " + adminId);
    }

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
     * POST /api/admin/roles/{userId}/{newRole}
     * Only ADMIN can change user roles
     * Critical permission - requires logging
     */
    @PostMapping("/roles/{userId}/{newRole}")
    @RequireAdmin(allowFacilityAdmin = false)
    public ResponseEntity<?> changeUserRole(
            @PathVariable Integer userId,
            @PathVariable String newRole) {
        Integer adminId = userContext.getCurrentUserId();
        // Change role and log this critical action
        return ResponseEntity.ok().body("User " + userId + " role changed to " + newRole + " by admin " + adminId);
    }

    /**
     * POST /api/admin/facility-admins/{userId}
     * Only ADMIN can promote to FACILITY_ADMIN
     */
    @PostMapping("/facility-admins/{userId}")
    @RequireAdmin(allowFacilityAdmin = false)
    public ResponseEntity<?> promoteFacilityAdmin(@PathVariable Integer userId) {
        Integer adminId = userContext.getCurrentUserId();
        return ResponseEntity.ok().body("User " + userId + " promoted to FACILITY_ADMIN by admin " + adminId);
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
