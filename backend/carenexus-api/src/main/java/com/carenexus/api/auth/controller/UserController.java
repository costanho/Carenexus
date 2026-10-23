package com.carenexus.api.auth.controller;

import com.carenexus.api.auth.dto.response.AuthResponse;
import com.carenexus.api.auth.service.AuthService;
import com.carenexus.api.common.config.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final AuthService authService;
    private final UserContext userContext;

    /**
     * GET /api/users/me
     * Get current authenticated user information
     */
    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser() {
        String systemId = userContext.getCurrentUserSystemId();
        if (systemId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Not authenticated");
        }
        AuthResponse.AuthUser user = authService.getCurrentUserBySystemId(systemId);
        return ResponseEntity.ok(user);
    }
}
