package com.carenexus.api.auth.controller;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.auth.service.AuthService;
import com.carenexus.api.common.annotation.RequireAdmin;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.core.dto.response.DoctorProfileResponse;
import com.carenexus.api.core.repository.DoctorRepository;
import com.carenexus.api.core.service.DoctorProfileService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Administrator account controls. Every endpoint is ADMIN only (@RequireAdmin, and /api/admin/** in SecurityConfig).
 *
 * - GET  /api/admin/users?role=&active=&page=&size=   paged user list (role: PATIENT|DOCTOR|CAREGIVER|ADMIN, "proxy" = CAREGIVER)
 * - GET  /api/admin/users/{systemId}                  one user
 * - POST /api/admin/users/{systemId}/activate         re-activate (for a DOCTOR this also verifies the clinician profile)
 * - POST /api/admin/users/{systemId}/deactivate       deactivate and revoke all refresh tokens
 * - GET  /api/admin/doctors/pending                   self-registered doctors awaiting verification
 * - POST /api/admin/doctors/{systemId}/verify         approve a pending doctor (activates account + profile)
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class UserAdminController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> ROLES = Set.of("PATIENT", "DOCTOR", "CAREGIVER", "ADMIN");

    private final UserRepository userRepository;
    private final DoctorRepository doctorRepository;
    private final DoctorProfileService doctorProfileService;
    private final AuthService authService;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;

    /** User as seen by an administrator. No password hash. */
    public record AdminUserResponse(Integer userId, String systemId, String firstName, String lastName,
                                    String email, String phone, String role, Boolean isActive,
                                    LocalDateTime createdAt, LocalDateTime updatedAt) {
        static AdminUserResponse of(User u) {
            return new AdminUserResponse(u.getUserId(), u.getSystemId(), u.getFirstName(), u.getLastName(),
                    u.getEmail(), u.getPhone(), u.getRole(), u.getIsActive(), u.getCreatedAt(), u.getUpdatedAt());
        }
    }

    /** Page envelope used by the admin list endpoints. */
    public record AdminPage<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
        public static <T> AdminPage<T> of(Page<T> page) {
            return new AdminPage<>(page.getContent(), page.getNumber(), page.getSize(),
                    page.getTotalElements(), page.getTotalPages());
        }
    }

    @GetMapping("/users")
    @RequireAdmin(allowFacilityAdmin = false)
    public ResponseEntity<AdminPage<AdminUserResponse>> getAllUsers(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        authorizationService.requireAdmin();

        User probe = new User();
        probe.setRole(normalizeRole(role));
        probe.setIsActive(active);
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), clampSize(size), Sort.by(Sort.Direction.ASC, "userId"));

        Page<AdminUserResponse> users = userRepository.findAll(Example.of(probe), pageRequest).map(AdminUserResponse::of);
        return ResponseEntity.ok(AdminPage.of(users));
    }

    @GetMapping("/users/{systemId}")
    @RequireAdmin(allowFacilityAdmin = false)
    public ResponseEntity<AdminUserResponse> getUser(@PathVariable String systemId) {
        authorizationService.requireAdmin();
        return ResponseEntity.ok(AdminUserResponse.of(findUser(systemId)));
    }

    @PostMapping("/users/{systemId}/activate")
    @RequireAdmin(allowFacilityAdmin = false)
    public ResponseEntity<AdminUserResponse> activateUser(@PathVariable String systemId, HttpServletRequest httpRequest) {
        authorizationService.requireAdmin();
        User user = findUser(systemId);

        if ("DOCTOR".equals(user.getRole()) && doctorRepository.findBySystemId(systemId).isPresent()) {
            doctorProfileService.activateDoctor(systemId);
        } else {
            user.setIsActive(true);
            userRepository.save(user);
        }
        auditService.logResourceModification(authorizationService.currentSystemId(), "USER_ACTIVATE", "USER",
                user.getUserId(), httpRequest.getRemoteAddr());
        return ResponseEntity.ok(AdminUserResponse.of(findUser(systemId)));
    }

    @PostMapping("/users/{systemId}/deactivate")
    @RequireAdmin(allowFacilityAdmin = false)
    public ResponseEntity<AdminUserResponse> deactivateUser(@PathVariable String systemId, HttpServletRequest httpRequest) {
        authorizationService.requireAdmin();
        User user = findUser(systemId);

        if (systemId.equals(authorizationService.currentSystemId())) {
            throw new ConflictException("You cannot deactivate your own account");
        }
        if ("ADMIN".equals(user.getRole()) && Boolean.TRUE.equals(user.getIsActive()) && countActiveAdmins() <= 1) {
            throw new ConflictException("Cannot deactivate the last active administrator");
        }

        if ("DOCTOR".equals(user.getRole()) && doctorRepository.findBySystemId(systemId).isPresent()) {
            doctorProfileService.deactivateDoctor(systemId);
        } else {
            user.setIsActive(false);
            userRepository.save(user);
        }
        // Kill the refresh tokens too; the JWT filter already rejects inactive users on every request
        authService.logout(systemId, httpRequest.getRemoteAddr());
        auditService.logResourceModification(authorizationService.currentSystemId(), "USER_DEACTIVATE", "USER",
                user.getUserId(), httpRequest.getRemoteAddr());
        return ResponseEntity.ok(AdminUserResponse.of(findUser(systemId)));
    }

    @GetMapping("/doctors/pending")
    @RequireAdmin(allowFacilityAdmin = false)
    public ResponseEntity<List<DoctorProfileResponse>> getPendingDoctors() {
        authorizationService.requireAdmin();
        return ResponseEntity.ok(doctorProfileService.getPendingDoctors());
    }

    @PostMapping("/doctors/{systemId}/verify")
    @RequireAdmin(allowFacilityAdmin = false)
    public ResponseEntity<DoctorProfileResponse> verifyDoctor(@PathVariable String systemId, HttpServletRequest httpRequest) {
        authorizationService.requireAdmin();
        User user = findUser(systemId);
        if (!"DOCTOR".equals(user.getRole())) {
            throw new RuntimeException("Only doctor accounts can be verified");
        }
        DoctorProfileResponse profile = doctorProfileService.activateDoctor(systemId);
        auditService.logResourceModification(authorizationService.currentSystemId(), "DOCTOR_VERIFY", "USER",
                user.getUserId(), httpRequest.getRemoteAddr());
        return ResponseEntity.ok(profile);
    }

    // ==================== HELPERS ====================

    private User findUser(String systemId) {
        return userRepository.findBySystemId(systemId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private long countActiveAdmins() {
        User probe = new User();
        probe.setRole("ADMIN");
        probe.setIsActive(true);
        return userRepository.count(Example.of(probe));
    }

    private static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return null;
        }
        String upper = role.trim().toUpperCase(Locale.ROOT);
        if ("PROXY".equals(upper)) {
            upper = "CAREGIVER";
        }
        if (!ROLES.contains(upper)) {
            throw new RuntimeException("Invalid role. Must be PATIENT, DOCTOR, CAREGIVER or ADMIN");
        }
        return upper;
    }

    static int clampSize(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }
}
