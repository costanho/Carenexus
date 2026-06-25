package com.auth.server;

import com.auth.server.config.JwtUtil;
import com.auth.server.config.PostgresTestContainerConfig;
import com.auth.server.model.User;
import com.auth.server.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
public class BaseIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JwtUtil jwtUtil;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected RefreshTokenRepository refreshTokenRepository;

    @Autowired
    protected CareTeamRepository careTeamRepository;

    @Autowired
    protected DependentAccessRepository dependentAccessRepository;

    @Autowired
    protected AuditLogRepository auditLogRepository;

    /**
     * Generate JWT token for testing
     */
    protected String generateToken(Integer userId, String email, String role) {
        return jwtUtil.generateToken(userId, email, role);
    }

    /**
     * Create and save a user
     */
    protected User createAndSaveUser(Integer userId, String email, String role) {
        User user = new User();
        user.setUserId(userId);
        user.setFirstName(role);
        user.setLastName("User" + userId);
        user.setEmail(email);
        user.setPhone("+1234567890" + userId);
        user.setPasswordHash(passwordEncoder.encode("password123"));
        user.setRole(role);
        user.setIsActive(true);
        user.setCreatedAt(java.time.LocalDateTime.now());
        user.setUpdatedAt(java.time.LocalDateTime.now());
        return userRepository.save(user);
    }

    /**
     * Get Bearer token header value
     */
    protected String bearerToken(String token) {
        return "Bearer " + token;
    }
}
