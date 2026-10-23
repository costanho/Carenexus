package com.carenexus.api.auth.config;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Collections;

public class CustomUserDetails implements UserDetails {

    private final Integer userId;
    private final String systemId;
    private final String email;
    private final String role;

    public CustomUserDetails(Integer userId, String systemId, String email, String role) {
        this.userId = userId;
        this.systemId = systemId;
        this.email = email;
        this.role = role;
    }

    public Integer getUserId() {
        return userId;
    }

    public String getSystemId() {
        return systemId;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // Convert role to Spring Authority (e.g., "DOCTOR" -> "ROLE_DOCTOR")
        return Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public String getPassword() {
        return null;  // Not needed for JWT auth
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
