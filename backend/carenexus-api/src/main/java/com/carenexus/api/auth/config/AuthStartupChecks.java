package com.carenexus.api.auth.config;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.auth.service.SystemIdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Startup checks for account safety.
 *
 * 1. Outside the local/docker/test profiles, refuse to start when the database holds development seed data:
 *    <ul>
 *      <li>a script of the dev seed (db/seed) was applied to this database (flyway_schema_history);</li>
 *      <li>an active account uses one of the seeded demo email addresses;</li>
 *      <li>an active account still has one of the seeded password hashes (the working ones have their plaintext
 *          published in the repository).</li>
 *    </ul>
 *    The three checks overlap on purpose: each one catches a database the others would miss (a renamed demo
 *    account, a copied users table without the Flyway history, a new seed file nobody added to the lists).
 * 2. Optional first-admin bootstrap: admins cannot self-register, so when
 *    CARENEXUS_BOOTSTRAP_ADMIN_EMAIL and CARENEXUS_BOOTSTRAP_ADMIN_PASSWORD are set and no active
 *    ADMIN exists yet, one is created (with a verified email, so it can sign in). Remove the variables after the
 *    first start.
 *
 * The docker profile is a developer profile: it loads the seed and accepts development secrets, so it is never a
 * production profile.
 */
@Component
@Slf4j
public class AuthStartupChecks implements ApplicationRunner {

    /**
     * Every password hash the dev seed gives a demo account: the four from db/seed/V2__insert_test_users.sql and the
     * one from db/seed/V21__dev_seed_thomas_dube_login.sql (their plaintext passwords are published in those files),
     * plus the placeholder values of V4 and V6, which match no password but mark a row as seed data.
     */
    static final Set<String> SEEDED_KNOWN_PASSWORD_HASHES = Set.of(
            "$2b$10$GC24Dy1u5cDmzMLY0QPVbOfcREu2NbJEelAbyUjfHDCklqm4pMXom",
            "$2b$10$KFGgid72AwXdTFlvU/.nje3.G0eRo7ZQ9rbW7g5Hl/JSqo/H8b2Pe",
            "$2b$10$WpZdSmf53alPwAbmSqH8euxFQ0IZ4fiAvYopkUK0vH9iwM.KePlFe",
            "$2b$10$XT8Ku.QbMgPyBSh.SDzeHuDFF.b83NLI6UPBg3oelQ/jFhINptiXu",
            "$2a$10$bSi7sk0xJ9DYnM7StjzsW.TuQSTGqfKzn1bguCJD.g9zb19hpQnbu",
            "$2b$10$LjqRp8fVnZhGZ4K8qL2O3Op9QZ0pWx7hK9mN2qRsT0uVwxYzAb1wO",
            "$2b$10$OmP4vWxYzAqRsT1uVwxYzAbCdEfGhIjKlMnOpQrStUvWxYzAb2dEf",
            "$2b$10$FgHiJkLmNoPqRsT2uVwxYzAbCdEfGhIjKlMnOpQrStUvWxYzAb3cFg",
            "$2b$10$QrStUvWxYzAbCdEfGhIjKlMnOpQrStUvWxYzAbCdEfGhIjKlMnOp",
            "$2b$10$WxYzAbCdEfGhIjKlMnOpQrStUvWxYzAbCdEfGhIjKlMnOpQrStUv",
            "$2b$10$hashedpasswordhere"
    );

    static final String SEEDED_ADMIN_EMAIL = "admin@carenexus.com";

    /** Login emails of every demo user inserted by db/seed (V2, V4, V6), lower-case. */
    static final Set<String> SEEDED_DEMO_EMAILS = Set.of(
            SEEDED_ADMIN_EMAIL,
            "agnes.zimba@carenexus.com",
            "amara.osei@carenexus.com",
            "chipo.ncube@carenexus.com",
            "david.mutua@carenexus.com",
            "elias.dube@carenexus.com",
            "elizabeth.njeri@carenexus.com",
            "emeka.eze@carenexus.com",
            "fatima.alhassan@carenexus.com",
            "florence.zimba@carenexus.com",
            "grace.kipchoge@carenexus.com",
            "grace.sibanda@carenexus.com",
            "james.banda@carenexus.com",
            "james.dube@carenexus.com",
            "linda.moyo@carenexus.com",
            "michael.kariuki@carenexus.com",
            "nokuthula.mthembu@carenexus.com",
            "peter.nkosi@carenexus.com",
            "rudo.sithole@carenexus.com",
            "ruth.banda@carenexus.com",
            "samuel.omondi@carenexus.com",
            "sarah.dube@carenexus.com",
            "tafara.mutasa@carenexus.com",
            "thomas.dube@carenexus.com"
    );

    /** The versioned scripts in db/seed, as Flyway records them in flyway_schema_history.script. */
    static final List<String> SEED_SCRIPTS = List.of(
            "V2__insert_test_users.sql",
            "V4__add_more_doctors.sql",
            "V5__add_medication_schedules.sql",
            "V6__populate_mock_data.sql",
            "V7__update_appointment_dates_to_future.sql",
            "V8__add_consultations_doc_00002.sql",
            "V20__dev_seed_communication_approvals.sql",
            "V21__dev_seed_thomas_dube_login.sql"
    );

    /** Repeatable dev-seed scripts added later are named R__dev_seed_<NN>_<slice>.sql (db/seed/README.md). */
    static final String REPEATABLE_SEED_PATTERN = "R\\_\\_dev\\_seed\\_%";

    private static final Profiles DEV_PROFILES = Profiles.of("local", "docker", "test");

    private final Environment environment;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SystemIdGenerator systemIdGenerator;
    private final JdbcTemplate jdbcTemplate;
    private final String bootstrapAdminEmail;
    private final String bootstrapAdminPassword;

    public AuthStartupChecks(
            Environment environment,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            SystemIdGenerator systemIdGenerator,
            JdbcTemplate jdbcTemplate,
            @Value("${carenexus.bootstrap-admin.email:${CARENEXUS_BOOTSTRAP_ADMIN_EMAIL:}}") String bootstrapAdminEmail,
            @Value("${carenexus.bootstrap-admin.password:${CARENEXUS_BOOTSTRAP_ADMIN_PASSWORD:}}") String bootstrapAdminPassword) {
        this.environment = environment;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.systemIdGenerator = systemIdGenerator;
        this.jdbcTemplate = jdbcTemplate;
        this.bootstrapAdminEmail = bootstrapAdminEmail;
        this.bootstrapAdminPassword = bootstrapAdminPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!environment.acceptsProfiles(DEV_PROFILES)) {
            rejectAppliedSeedScripts();
            rejectActiveDemoAccounts();
        }
        bootstrapAdminIfConfigured();
    }

    /** A database that the dev seed was applied to must never serve a real environment. */
    private void rejectAppliedSeedScripts() {
        List<String> applied = appliedSeedScripts();
        if (!applied.isEmpty()) {
            throw new IllegalStateException(
                    "This database contains development seed data: Flyway applied " + applied.size() + " script(s) from "
                            + "db/seed (for example " + applied.get(0) + "). The seed holds demo accounts with published "
                            + "passwords and mock clinical data. Use a database that was migrated with db/migration only "
                            + "(a converted development database must have its demo rows purged and these scripts "
                            + "removed from flyway_schema_history). The local and docker profiles are for development "
                            + "and are never a production profile.");
        }
    }

    private List<String> appliedSeedScripts() {
        // No history table: the schema was not built by Flyway in this database, so there is nothing to compare.
        Boolean historyExists = jdbcTemplate.queryForObject(
                "SELECT to_regclass('flyway_schema_history') IS NOT NULL", Boolean.class);
        if (!Boolean.TRUE.equals(historyExists)) {
            return List.of();
        }
        String placeholders = String.join(", ", SEED_SCRIPTS.stream().map(s -> "?").toList());
        Object[] parameters = new Object[SEED_SCRIPTS.size() + 1];
        for (int i = 0; i < SEED_SCRIPTS.size(); i++) {
            parameters[i] = SEED_SCRIPTS.get(i);
        }
        parameters[SEED_SCRIPTS.size()] = REPEATABLE_SEED_PATTERN;
        return jdbcTemplate.queryForList(
                "SELECT script FROM flyway_schema_history WHERE success AND (script IN (" + placeholders + ") "
                        + "OR script LIKE ?) ORDER BY installed_rank",
                String.class, parameters);
    }

    private void rejectActiveDemoAccounts() {
        if (userRepository.existsActiveByEmailIn(SEEDED_DEMO_EMAILS)
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
                // The operator who set the variables vouches for the address; without this the first administrator
                // could not sign in where a verified email is required.
                .emailVerifiedAt(LocalDateTime.now())
                .build();
        userRepository.save(admin);
        log.warn("Bootstrap ADMIN account {} created. Remove CARENEXUS_BOOTSTRAP_ADMIN_* from the environment.", admin.getSystemId());
    }
}
