package com.carenexus.api.careteam;

import com.carenexus.api.BaseIntegrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.PostgreSQLContainer;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V42 against data that existed before it: each test migrates a fresh database to V41, inserts legacy care_team rows
 * by hand, then applies V42 and checks the primary backfill, that roles are never rewritten, the new columns and
 * CHECK, and that both partial unique indexes are created (or skipped with a notice when old rows already break
 * them, instead of failing the migration).
 */
class CareTeamMigrationTest extends BaseIntegrationTest {

    @Autowired
    private PostgreSQLContainer<?> postgres;

    @Test
    void v42_backfillsOnePrimaryPerPatient_keepsRoles_andCreatesTheIndexes() throws Exception {
        String database = createDatabase();
        try {
            migrate(database, versionBeforeV42());
            try (Connection connection = connect(database); Statement st = connection.createStatement()) {
                insertUsers(st);
                // PAT-00001: two active PRIMARY_DOCTOR rows (the older one becomes primary) and an inactive older one
                insertRow(st, 1, "PAT-00001", "DOC-00001", "PRIMARY_DOCTOR", false, true, "2026-01-02 09:00");
                insertRow(st, 2, "PAT-00001", "DOC-00002", "PRIMARY_DOCTOR", false, true, "2026-01-03 09:00");
                insertRow(st, 3, "PAT-00001", "DOC-00003", "PRIMARY_DOCTOR", false, false, "2025-12-01 09:00");
                // PAT-00002: already has a primary (a SPECIALIST): its PRIMARY_DOCTOR row is left alone
                insertRow(st, 4, "PAT-00002", "DOC-00001", "SPECIALIST", true, true, "2026-01-01 09:00");
                insertRow(st, 5, "PAT-00002", "DOC-00002", "PRIMARY_DOCTOR", false, true, "2025-11-01 09:00");
                // PAT-00003: only a legacy caregiver row
                insertRow(st, 6, "PAT-00003", "CGV-00001", "CAREGIVER", false, true, "2026-01-01 09:00");
                st.execute("SELECT setval(pg_get_serial_sequence('care_team', 'care_team_id'), 100)");
            }

            migrate(database, "42");
            try (Connection connection = connect(database); Statement st = connection.createStatement()) {
                assertEquals("t", scalar(st, "SELECT is_primary FROM care_team WHERE care_team_id = 1"));
                assertEquals("f", scalar(st, "SELECT is_primary FROM care_team WHERE care_team_id = 2"));
                assertEquals("f", scalar(st, "SELECT is_primary FROM care_team WHERE care_team_id = 3"), "inactive rows are skipped");
                assertEquals("t", scalar(st, "SELECT is_primary FROM care_team WHERE care_team_id = 4"));
                assertEquals("f", scalar(st, "SELECT is_primary FROM care_team WHERE care_team_id = 5"));
                assertEquals("f", scalar(st, "SELECT is_primary FROM care_team WHERE care_team_id = 6"));
                assertEquals("4", scalar(st, "SELECT COUNT(*) FROM care_team WHERE role = 'PRIMARY_DOCTOR'"), "roles are not rewritten");
                assertEquals("1", scalar(st, "SELECT COUNT(*) FROM care_team WHERE role = 'CAREGIVER'"));

                assertEquals("1", scalar(st, "SELECT COUNT(*) FROM pg_indexes WHERE indexname = 'ux_care_team_active_provider'"));
                assertEquals("1", scalar(st, "SELECT COUNT(*) FROM pg_indexes WHERE indexname = 'ux_care_team_active_primary'"));
                assertEquals("YES", scalar(st, "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_name = 'care_team' AND column_name = 'provider_system_id'"));
                assertEquals("3", scalar(st, "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'care_team' "
                        + "AND column_name IN ('provider_name', 'provider_phone', 'provider_organisation')"));

                // an external provider needs a name
                st.executeUpdate("INSERT INTO care_team (patient_system_id, provider_name, role, joined_date, assigned_at) "
                        + "VALUES ('PAT-00003', 'Greenwood Pharmacy', 'PHARMACY', CURRENT_DATE, now())");
                assertThrows(SQLException.class, () -> st.executeUpdate("INSERT INTO care_team (patient_system_id, role, "
                        + "joined_date, assigned_at) VALUES ('PAT-00003', 'LAB', CURRENT_DATE, now())"));
                assertThrows(SQLException.class, () -> st.executeUpdate("INSERT INTO care_team (patient_system_id, "
                        + "provider_name, role, joined_date, assigned_at) VALUES ('PAT-00003', '  ', 'LAB', CURRENT_DATE, now())"));
                // one active row per provider account, one active primary per patient
                assertThrows(SQLException.class, () -> st.executeUpdate("INSERT INTO care_team (patient_system_id, "
                        + "provider_system_id, role, joined_date, assigned_at) VALUES ('PAT-00001', 'DOC-00002', 'NURSE', "
                        + "CURRENT_DATE, now())"));
                assertThrows(SQLException.class, () -> st.executeUpdate("UPDATE care_team SET is_primary = TRUE "
                        + "WHERE care_team_id = 2"));
                // the V10 role CHECK is unchanged
                assertThrows(SQLException.class, () -> st.executeUpdate("INSERT INTO care_team (patient_system_id, "
                        + "provider_name, role, joined_date, assigned_at) VALUES ('PAT-00003', 'X', 'SURGEON', CURRENT_DATE, now())"));
            }
        } finally {
            dropDatabase(database);
        }
    }

    @Test
    void v42_skipsAnIndexThatOldRowsAlreadyBreak_insteadOfFailing() throws Exception {
        String database = createDatabase();
        try {
            migrate(database, versionBeforeV42());
            try (Connection connection = connect(database); Statement st = connection.createStatement()) {
                insertUsers(st);
                insertRow(st, 1, "PAT-00001", "DOC-00001", "DOCTOR", true, true, "2026-01-02 09:00");
                insertRow(st, 2, "PAT-00001", "DOC-00001", "SPECIALIST", true, true, "2026-01-03 09:00");
            }
            migrate(database, "42");
            try (Connection connection = connect(database); Statement st = connection.createStatement()) {
                assertEquals("0", scalar(st, "SELECT COUNT(*) FROM pg_indexes WHERE indexname = 'ux_care_team_active_provider'"));
                assertEquals("0", scalar(st, "SELECT COUNT(*) FROM pg_indexes WHERE indexname = 'ux_care_team_active_primary'"));
                assertEquals("2", scalar(st, "SELECT COUNT(*) FROM care_team WHERE is_active"), "nothing is deleted");
                assertTrue(Integer.parseInt(scalar(st, "SELECT COUNT(*) FROM pg_constraint "
                        + "WHERE conname = 'care_team_provider_present_check'")) == 1);
            }
        } finally {
            dropDatabase(database);
        }
    }

    // ==================== HELPERS ====================

    private static void insertUsers(Statement st) throws SQLException {
        String[][] users = {
                {"PAT-00001", "PATIENT"}, {"PAT-00002", "PATIENT"}, {"PAT-00003", "PATIENT"},
                {"DOC-00001", "DOCTOR"}, {"DOC-00002", "DOCTOR"}, {"DOC-00003", "DOCTOR"}, {"CGV-00001", "CAREGIVER"}};
        for (String[] user : users) {
            st.executeUpdate("INSERT INTO users (first_name, last_name, email, role, system_id) VALUES ('Test', '"
                    + user[0] + "', '" + user[0].toLowerCase() + "@example.com', '" + user[1] + "', '" + user[0] + "')");
            if ("PATIENT".equals(user[1])) {
                // care_team.patient_system_id references the patient profile (V12)
                st.executeUpdate("INSERT INTO patients (system_id) VALUES ('" + user[0] + "')");
            }
        }
    }

    private static void insertRow(Statement st, int id, String patient, String provider, String role, boolean primary,
                                  boolean active, String assignedAt) throws SQLException {
        st.executeUpdate("INSERT INTO care_team (care_team_id, patient_system_id, provider_system_id, role, joined_date, "
                + "is_primary, is_active, assigned_at) VALUES (" + id + ", '" + patient + "', '" + provider + "', '" + role
                + "', DATE '" + assignedAt.substring(0, 10) + "', " + primary + ", " + active + ", TIMESTAMP '"
                + assignedAt + "')");
    }

    /**
     * The highest schema version below 42 on the classpath (versions are allocated per slice, so the one right
     * before V42 is whatever the merged waves contain).
     */
    private static String versionBeforeV42() throws Exception {
        Pattern versioned = Pattern.compile("^V(\\d+(?:[._]\\d+)*)__.+\\.sql$");
        MigrationVersion v42 = MigrationVersion.fromVersion("42");
        MigrationVersion best = null;
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/*.sql")) {
            Matcher matcher = resource.getFilename() == null ? null : versioned.matcher(resource.getFilename());
            if (matcher == null || !matcher.matches()) {
                continue;
            }
            MigrationVersion version = MigrationVersion.fromVersion(matcher.group(1).replace('_', '.'));
            if (version.compareTo(v42) < 0 && (best == null || version.compareTo(best) > 0)) {
                best = version;
            }
        }
        String bestText = best == null ? null : best.getVersion();
        assertTrue(bestText != null, "no schema migration below V42");
        return bestText;
    }

    /** Same settings as spring.flyway in application.yml, stopping at {@code target}. */
    private void migrate(String database, String target) {
        Flyway.configure()
                .dataSource(jdbcUrl(database), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .target(target)
                .load()
                .migrate();
    }

    private String createDatabase() throws Exception {
        String database = "care_team_v42_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection connection = connect(postgres.getDatabaseName()); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        return database;
    }

    private void dropDatabase(String database) throws Exception {
        try (Connection connection = connect(postgres.getDatabaseName()); Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }

    private Connection connect(String database) throws Exception {
        return DriverManager.getConnection(jdbcUrl(database), postgres.getUsername(), postgres.getPassword());
    }

    private String jdbcUrl(String database) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
                + "/" + database;
    }

    private static String scalar(Statement statement, String sql) throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }
}
