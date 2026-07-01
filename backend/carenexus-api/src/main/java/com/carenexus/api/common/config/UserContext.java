package com.carenexus.api.common.config;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import com.carenexus.api.auth.config.CustomUserDetails;
import org.springframework.stereotype.Component;

@Component
public class UserContext {

    /**
     * Get current authenticated user ID
     */
    public Integer getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CustomUserDetails) {
            CustomUserDetails userDetails = (CustomUserDetails) auth.getPrincipal();
            return userDetails.getUserId();
        }
        return null;
    }

    /**
     * Get current authenticated user's email
     */
    public String getCurrentUserEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CustomUserDetails) {
            CustomUserDetails userDetails = (CustomUserDetails) auth.getPrincipal();
            return userDetails.getUsername();
        }
        return null;
    }

    /**
     * Get current authenticated user's role
     */
    public String getCurrentUserRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getAuthorities() != null && !auth.getAuthorities().isEmpty()) {
            // Extract role from "ROLE_DOCTOR" -> "DOCTOR"
            String roleWithPrefix = auth.getAuthorities().iterator().next().getAuthority();
            return roleWithPrefix.replace("ROLE_", "");
        }
        return null;
    }

    /**
     * Check if user is authenticated
     */
    public boolean isAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && !(auth.getPrincipal() instanceof String);
    }

    /**
     * Check if current user has specific role
     */
    public boolean hasRole(String role) {
        String currentRole = getCurrentUserRole();
        return currentRole != null && currentRole.equals(role);
    }
}
