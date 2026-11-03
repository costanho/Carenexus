package com.carenexus.api.auth.config;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.repository.UserRepository;
import io.jsonwebtoken.Claims;
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
import java.util.Optional;

/**
 * Authenticates requests carrying "Authorization: Bearer <jwt>".
 *
 * The token only proves who the caller was at login time. The principal (role, ids) is built from
 * the current users row, so deactivated/deleted users and role changes take effect immediately.
 * When the token is missing, invalid, expired or belongs to an inactive user, the request simply
 * continues unauthenticated: public endpoints still work and protected ones get a 401 from the
 * entry point configured in SecurityConfig.
 *
 * Never log the token or the Authorization header here.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        try {
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7).trim();
                Optional<Claims> claims = jwtUtil.parse(token);
                if (claims.isPresent()) {
                    String systemId = claims.get().get("systemId", String.class);
                    Optional<User> user = systemId == null
                            ? Optional.empty()
                            : userRepository.findBySystemId(systemId).filter(u -> Boolean.TRUE.equals(u.getIsActive()));

                    if (user.isPresent()) {
                        authenticate(request, user.get());
                    } else {
                        logger.debug("JWT rejected: user missing or inactive");
                    }
                }
            }
        } catch (Exception e) {
            SecurityContextHolder.clearContext();
            logger.warn("JWT authentication failed: " + e.getClass().getSimpleName());
        }

        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, User user) {
        CustomUserDetails userDetails = new CustomUserDetails(
                user.getUserId(), user.getSystemId(), user.getEmail(), user.getRole());

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // Controllers read these via @RequestAttribute("systemId") / ("userRole") / ("userId")
        request.setAttribute("userId", user.getUserId());
        request.setAttribute("systemId", user.getSystemId());
        request.setAttribute("userRole", user.getRole());
    }
}
