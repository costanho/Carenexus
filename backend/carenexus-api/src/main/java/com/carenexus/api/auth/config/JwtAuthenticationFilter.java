package com.carenexus.api.auth.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        try {
            // Extract JWT token from Authorization header
            String authHeader = request.getHeader("Authorization");
            String token = null;

            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                token = authHeader.substring(7);
            }

            // If token exists, validate it
            if (token != null && jwtUtil.validateToken(token)) {
                // Extract user details from token
                Integer userId = jwtUtil.getUserIdFromToken(token);
                String email = jwtUtil.getEmailFromToken(token);
                String role = jwtUtil.getRoleFromToken(token);

                // Create CustomUserDetails
                CustomUserDetails userDetails = new CustomUserDetails(userId, email, role);

                // Create authentication token
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());

                // Set authentication in Spring Security context
                SecurityContextHolder.getContext().setAuthentication(authentication);

                // Add userId to request attributes so controllers can access it
                request.setAttribute("userId", userId);
                request.setAttribute("userRole", role);
            }

        } catch (Exception e) {
            logger.error("JWT authentication failed: " + e.getMessage());
        }

        // Continue with filter chain
        filterChain.doFilter(request, response);
    }
}
