package com.carenexus.api.auth.config;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.auth.service.SystemIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Set;

/**
 * Startup checks for account safety.
 *
 * 1. Outside the local/docker/test profiles, refuse to start if the demo accounts from db/seed
 *    (whose passwords are published in the repo) exist and are active.
 * 2. Optional first-admin bootstrap: admins cannot self-register, so when
 *    CARENEXUS_BOOTSTRAP_ADMIN_EMAIL and CARENEXUS_BOOTSTRAP_ADMIN_PASSWORD are set and no active
 *    ADMIN exists yet, one is created. Remove the variables after the first start.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuthStartupChecks implements ApplicationRunner {

    /** bcrypt hashes from db/seed/V2__insert_test_users.sql; their plaintext passwords are in that file. */
    private static final Set<String> SEEDED_KNOWN_PASSWORD_HASHES = Set.of(
            "$2b$10$GC24Dy1u5cDmzMLY0QPVbOfcREu2NbJEelAbyUjfHDCklqm4pMXom",
            "$2b$10$KFGgid72AwXdTFlvU/.nje3.G0eRo7ZQ9rbW7g5Hl/JSqo/H8b2Pe",
            "$2b$10$WpZdSmf53alPwAbmSqH8euxFQ0IZ4fiAvYopkUK0vH9iwM.KePlFe",
            "$2b$10$XT8Ku.QbMgPyBSh.SDzeHuDFF.b83NLI6UPBg3oelQ/jFhINptiXu"
    );
    private static final String SEEDED_ADMIN_EMAIL = "admin@carenexus.com";

    private final Environment environment;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SystemIdGenerator systemIdGenerator;

    @Value("${carenexus.bootstrap-admin.email:${CARENEXUS_BOOTSTRAP_ADMIN_EMAIL:}}")
    private String bootstrapAdminEmail;

    @Value("${carenexus.bootstrap-admin.password:${CARENEXUS_BOOTSTRAP_ADMIN_PASSWORD:}}")
    private String bootstrapAdminPassword;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        boolean devProfile = environment.acceptsProfiles(Profiles.of("local", "docker", "test"));
        if (!devProfile) {
            rejectActiveDemoAccounts();
        }
        bootstrapAdminIfConfigured();
    }

    private void rejectActiveDemoAccounts() {
        if (userRepository.existsByEmailIgnoreCaseAndIsActiveTrue(SEEDED_ADMIN_EMAIL)
                || userRepository.existsByIsActiveTrueAndPasswordHashIn(SEEDED_KNOWN_PASSWORD_HASHES)) {
            throw new IllegalStateException(
                    "Active demo accounts from db/seed (published passwords, e.g. " + SEEDED_ADMIN_EMAIL + ") exist in this "
                            + "database. Deactivate or delete them before running outside the local/docker profiles.");
        }
    }

    private void bootstrapAdminIfConfigured() {
        if (bootstrapAdminEmail == null || bootstrapAdminEmail.isBlank()
                || bootstrapAdminPassword == null || bootstrapAdminPassword.isBlank()) {
            return;
        }
        if (userRepository.existsByRoleAndIsActiveTrue("ADMIN")) {
            log.info("Bootstrap admin variables are set but an active ADMIN already exists; ignoring them. Remove them.");
            return;
        }
        String email = bootstrapAdminEmail.trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalStateException("Cannot bootstrap admin: a user with that email already exists");
        }
        if (bootstrapAdminPassword.length() < 12) {
            throw new IllegalStateException("Bootstrap admin password must be at least 12 characters");
        }
        User admin = User.builder()
                .firstName("Admin")
                .lastName("User")
                .email(email)
                .passwordHash(passwordEncoder.encode(bootstrapAdminPassword))
                .role("ADMIN")
                .systemId(systemIdGenerator.generateSystemId("ADMIN"))
                .isActive(true)
                .build();
        userRepository.save(admin);
        log.warn("Bootstrap ADMIN account {} created. Remove CARENEXUS_BOOTSTRAP_ADMIN_* from the environment.", admin.getSystemId());
    }
}
