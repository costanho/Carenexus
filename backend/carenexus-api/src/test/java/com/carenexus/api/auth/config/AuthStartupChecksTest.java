package com.carenexus.api.auth.config;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.auth.service.SystemIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthStartupChecks without a database: which databases are refused outside the developer profiles, and the
 * first-administrator bootstrap. The real queries run in AuthStartupChecksDatabaseTest.
 */
class AuthStartupChecksTest {

    private static final String V21_HASH = "$2a$10$bSi7sk0xJ9DYnM7StjzsW.TuQSTGqfKzn1bguCJD.g9zb19hpQnbu";

    /** Answers the two Flyway history queries from fields instead of a database. */
    private static class HistoryStub extends JdbcTemplate {
        boolean historyTableExists = true;
        List<String> appliedSeedScripts = List.of();
        String listSql;
        Object[] listArgs;

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType) {
            return requiredType.cast(historyTableExists);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
            listSql = sql;
            listArgs = args;
            return (List<T>) appliedSeedScripts;
        }
    }

    private UserRepository users;
    private PasswordEncoder encoder;
    private SystemIdGenerator ids;
    private HistoryStub history;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        encoder = mock(PasswordEncoder.class);
        ids = mock(SystemIdGenerator.class);
        history = new HistoryStub();
        when(encoder.encode(anyString())).thenReturn("encoded-password");
        when(ids.generateSystemId("ADMIN")).thenReturn("ADM-00001");
    }

    private AuthStartupChecks checks(String bootstrapEmail, String bootstrapPassword, String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new AuthStartupChecks(environment, users, encoder, ids, history, bootstrapEmail, bootstrapPassword);
    }

    // ==================== DEMO DATA ====================

    @Test
    void cleanDatabase_startsOutsideDeveloperProfiles() {
        assertThatCode(() -> checks("", "", "prod").run(null)).doesNotThrowAnyException();
        assertThatCode(() -> checks("", "").run(null)).doesNotThrowAnyException();   // no profile at all
    }

    @Test
    void appliedSeedScript_failsStartupOutsideDeveloperProfiles() {
        history.appliedSeedScripts = List.of("V2__insert_test_users.sql", "V21__dev_seed_thomas_dube_login.sql");

        assertThatThrownBy(() -> checks("", "", "prod").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("development seed data")
                .hasMessageContaining("V2__insert_test_users.sql")
                .hasMessageContaining("never a production profile");
        assertThatThrownBy(() -> checks("", "").run(null)).isInstanceOf(IllegalStateException.class);

        // The history is searched for every versioned seed script and for repeatable dev-seed scripts.
        assertThat(history.listSql).contains("flyway_schema_history").contains("success");
        assertThat(history.listArgs).hasSize(AuthStartupChecks.SEED_SCRIPTS.size() + 1);
        assertThat(Arrays.asList(history.listArgs)).containsAll(AuthStartupChecks.SEED_SCRIPTS);
        assertThat(history.listArgs[history.listArgs.length - 1]).isEqualTo(AuthStartupChecks.REPEATABLE_SEED_PATTERN);
    }

    @Test
    void activeAccountWithASeededEmail_failsStartupOutsideDeveloperProfiles() {
        when(users.existsActiveByEmailIn(anyCollection())).thenReturn(true);

        assertThatThrownBy(() -> checks("", "", "prod").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Active demo accounts");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> emails = ArgumentCaptor.forClass(Collection.class);
        verify(users).existsActiveByEmailIn(emails.capture());
        assertThat(emails.getValue()).contains("admin@carenexus.com", "thomas.dube@carenexus.com",
                "james.banda@carenexus.com", "sarah.dube@carenexus.com");
        assertThat(emails.getValue()).allMatch(email -> email.equals(email.toLowerCase()), "compared lower-case");
    }

    @Test
    void activeAccountWithASeededPasswordHash_failsStartup_includingTheV21Hash() {
        when(users.existsByIsActiveTrueAndPasswordHashIn(anyCollection())).thenReturn(true);

        assertThatThrownBy(() -> checks("", "", "prod").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Active demo accounts");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> hashes = ArgumentCaptor.forClass(Collection.class);
        verify(users).existsByIsActiveTrueAndPasswordHashIn(hashes.capture());
        assertThat(hashes.getValue()).contains(V21_HASH,
                "$2b$10$GC24Dy1u5cDmzMLY0QPVbOfcREu2NbJEelAbyUjfHDCklqm4pMXom",
                "$2b$10$KFGgid72AwXdTFlvU/.nje3.G0eRo7ZQ9rbW7g5Hl/JSqo/H8b2Pe",
                "$2b$10$WpZdSmf53alPwAbmSqH8euxFQ0IZ4fiAvYopkUK0vH9iwM.KePlFe",
                "$2b$10$XT8Ku.QbMgPyBSh.SDzeHuDFF.b83NLI6UPBg3oelQ/jFhINptiXu");
    }

    @Test
    void developerProfiles_areNotChecked() {
        history.appliedSeedScripts = List.of("V2__insert_test_users.sql");
        when(users.existsActiveByEmailIn(anyCollection())).thenReturn(true);
        when(users.existsByIsActiveTrueAndPasswordHashIn(anyCollection())).thenReturn(true);

        for (String profile : List.of("local", "docker", "test")) {
            assertThatCode(() -> checks("", "", profile).run(null)).doesNotThrowAnyException();
        }
        verify(users, never()).existsActiveByEmailIn(anyCollection());
        assertThat(history.listSql).isNull();
    }

    @Test
    void databaseWithoutAFlywayHistory_isNotSearchedForSeedScripts() {
        history.historyTableExists = false;
        history.appliedSeedScripts = List.of("V2__insert_test_users.sql");

        assertThatCode(() -> checks("", "", "prod").run(null)).doesNotThrowAnyException();
        assertThat(history.listSql).isNull();
    }

    /** The lists in AuthStartupChecks are copies of what db/seed contains; this fails when a seed file changes them. */
    @Test
    void seedLists_coverEverythingInTheSeedFolder() throws Exception {
        Resource[] scripts = new PathMatchingResourcePatternResolver().getResources("classpath:db/seed/*.sql");
        assertThat(scripts).isNotEmpty();

        Pattern hash = Pattern.compile("\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]+");
        Pattern demoEmail = Pattern.compile("[a-z0-9._-]+@carenexus\\.com");
        Set<String> versioned = new TreeSet<>();
        Set<String> hashes = new TreeSet<>();
        Set<String> emails = new TreeSet<>();
        for (Resource script : scripts) {
            String name = script.getFilename();
            if (name != null && name.startsWith("V")) {
                versioned.add(name);
            } else {
                assertThat(name).as("repeatable seed scripts follow the documented name").startsWith("R__dev_seed_");
            }
            String sql = new String(script.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Matcher hashMatcher = hash.matcher(sql);
            while (hashMatcher.find()) {
                hashes.add(hashMatcher.group());
            }
            Matcher emailMatcher = demoEmail.matcher(sql.toLowerCase());
            while (emailMatcher.find()) {
                emails.add(emailMatcher.group());
            }
        }

        assertThat(new TreeSet<>(AuthStartupChecks.SEED_SCRIPTS)).isEqualTo(versioned);
        assertThat(AuthStartupChecks.SEEDED_KNOWN_PASSWORD_HASHES).containsAll(hashes);
        assertThat(AuthStartupChecks.SEEDED_KNOWN_PASSWORD_HASHES).contains(V21_HASH);
        assertThat(AuthStartupChecks.SEEDED_DEMO_EMAILS).containsAll(emails);
    }

    // ==================== BOOTSTRAP ADMINISTRATOR ====================

    @Test
    void bootstrapAdmin_isCreatedOnce_activeWithAVerifiedEmail() {
        checks("  First.Admin@Example.org ", "a-long-bootstrap-password", "prod").run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        User admin = saved.getValue();
        assertThat(admin.getRole()).isEqualTo("ADMIN");
        assertThat(admin.getSystemId()).isEqualTo("ADM-00001");
        assertThat(admin.getEmail()).isEqualTo("first.admin@example.org");
        assertThat(admin.getPasswordHash()).isEqualTo("encoded-password");
        assertThat(admin.getIsActive()).isTrue();
        assertThat(admin.getEmailVerifiedAt()).as("otherwise the first administrator could not sign in").isNotNull();
        assertThat(admin.getMustChangePassword()).isFalse();
    }

    @Test
    void bootstrapAdmin_isIgnoredWhenAnActiveAdministratorExists() {
        when(users.existsByRoleAndIsActiveTrue("ADMIN")).thenReturn(true);

        checks("first.admin@example.org", "a-long-bootstrap-password", "prod").run(null);

        verify(users, never()).save(any(User.class));
    }

    @Test
    void bootstrapAdmin_isNotCreatedWithoutBothVariables() {
        List<String[]> incomplete = new ArrayList<>();
        incomplete.add(new String[]{"", ""});
        incomplete.add(new String[]{null, null});
        incomplete.add(new String[]{"first.admin@example.org", " "});
        incomplete.add(new String[]{" ", "a-long-bootstrap-password"});
        for (String[] pair : incomplete) {
            checks(pair[0], pair[1], "prod").run(null);
        }
        verify(users, never()).save(any(User.class));
        verify(users, never()).existsByRoleAndIsActiveTrue(anyString());
    }

    @Test
    void bootstrapAdmin_withAShortPasswordOrATakenEmail_failsStartup() {
        assertThatThrownBy(() -> checks("first.admin@example.org", "short-pass", "prod").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 12 characters");

        when(users.existsByEmailIgnoreCase("first.admin@example.org")).thenReturn(true);
        assertThatThrownBy(() -> checks("first.admin@example.org", "a-long-bootstrap-password", "prod").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");

        verify(users, never()).save(any(User.class));
    }

    @Test
    void bootstrapAdmin_alsoWorksOnADeveloperProfile() {
        checks("first.admin@example.org", "a-long-bootstrap-password", "local").run(null);

        verify(users).save(any(User.class));
    }
}
