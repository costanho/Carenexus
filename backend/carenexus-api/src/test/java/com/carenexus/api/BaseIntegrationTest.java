package com.carenexus.api;

import com.carenexus.api.auth.config.JwtUtil;
import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.auth.repository.RefreshTokenRepository;
import com.carenexus.api.core.repository.CareTeamRepository;
import com.carenexus.api.core.repository.DependentAccessRepository;
import com.carenexus.api.common.repository.AuditLogRepository;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.config.PostgresTestContainerConfig;
import com.carenexus.api.common.config.FakeCallProviderConfig;
import com.carenexus.api.core.model.Caregiver;
import com.carenexus.api.core.model.Doctor;
import com.carenexus.api.core.model.Patient;
import com.carenexus.api.core.repository.CaregiverRepository;
import com.carenexus.api.core.repository.DoctorRepository;
import com.carenexus.api.core.repository.PatientRepository;
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
@Import({PostgresTestContainerConfig.class, FakeCallProviderConfig.class})
public class BaseIntegrationTest {

    static {
        // The application's main() pins the JVM zone to the clinic zone; tests do not run main().
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Africa/Harare"));
    }

    /** BCrypt is deliberately slow; hash the shared test password once instead of once per user. */
    private static String cachedPasswordHash;

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

    @Autowired
    protected PatientRepository patientRepository;

    @Autowired
    protected DoctorRepository doctorRepository;

    @Autowired
    protected CaregiverRepository caregiverRepository;

    /**
     * Generate JWT token for testing
     */
    protected String generateToken(Integer userId, String systemId, String email, String role) {
        return jwtUtil.generateToken(userId, systemId, email, role);
    }

    /**
     * Create and save a user plus its profile row (patients / doctors / caregivers), flushed immediately.
     * The schema comes from the real Flyway migrations, so foreign keys to users(system_id) and to the
     * profile tables are enforced. userId is only used to build unique test values; the database assigns
     * the real user_id (read it from the returned user).
     */
    protected User createAndSaveUser(Integer userId, String systemId, String email, String role) {
        User user = new User();
        user.setSystemId(systemId);
        user.setFirstName(role);
        user.setLastName("User" + userId);
        user.setEmail(email);
        user.setPhone("+1234567890" + userId);
        if (cachedPasswordHash == null) {
            cachedPasswordHash = passwordEncoder.encode("password123");
        }
        user.setPasswordHash(cachedPasswordHash);
        user.setRole(role);
        user.setIsActive(true);
        user.setCreatedAt(java.time.LocalDateTime.now());
        user.setUpdatedAt(java.time.LocalDateTime.now());
        User saved = userRepository.saveAndFlush(user);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        switch (role) {
            case "PATIENT" -> patientRepository.saveAndFlush(Patient.builder()
                    .systemId(systemId).healthStatus("STABLE").createdAt(now).build());
            case "DOCTOR" -> doctorRepository.saveAndFlush(Doctor.builder()
                    .systemId(systemId).specialization("General Practice").isActive(true).createdAt(now).build());
            case "CAREGIVER" -> caregiverRepository.saveAndFlush(Caregiver.builder()
                    .systemId(systemId).createdAt(now).build());
            default -> { }
        }
        return saved;
    }

    /**
     * Get Bearer token header value
     */
    protected String bearerToken(String token) {
        return "Bearer " + token;
    }
}
