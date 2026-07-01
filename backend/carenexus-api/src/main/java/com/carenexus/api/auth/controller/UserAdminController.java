package com.carenexus.api.auth.controller;

import com.carenexus.api.common.annotation.RequireAdmin;
import com.carenexus.api.common.config.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class UserAdminController {

    private final UserContext userContext;

    /**
     * GET /api/admin/users
     * Only ADMIN can list all users
     */
    @GetMapping("/users")
    @RequireAdmin(allowFacilityAdmin = false)
    public ResponseEntity<?> getAllUsers() {
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
        return ResponseEntity.ok().body("User " + userId + " deactivated by admin " + adminId);
    }

    /**
     * POST /api/admin/roles/{userId}/{newRole}
     * Only ADMIN can change user roles
     */
    @PostMapping("/roles/{userId}/{newRole}")
    @RequireAdmin(allowFacilityAdmin = false)
    public ResponseEntity<?> changeUserRole(
            @PathVariable Integer userId,
            @PathVariable String newRole) {
        Integer adminId = userContext.getCurrentUserId();
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
}
