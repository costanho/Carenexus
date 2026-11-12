package com.carenexus.api.health;

import com.carenexus.api.BaseIntegrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V47 against databases in the states it can meet, each in its own fresh database inside the shared test container
 * (same Flyway settings as application.yml):
 * <ul>
 *   <li>rows of the V1 shape (integer patient_id): system ids backfilled, severity derived from the abnormal flag,
 *       legacy keys kept but optional, the new rules enforced for new rows;</li>
 *   <li>a symptom log whose duration the new CHECK would refuse: the migration still succeeds and keeps it;</li>
 *   <li>the developer seed: the demo vitals and symptom log, consistent with the reference ranges, idempotent.</li>
 * </ul>
 */
class HealthMigrationTest extends BaseIntegrationTest {

    private static final String V47 = "47";
    private static final Pattern VERSIONED = Pattern.compile("^V(\\d+(?:[._]\\d+)*)__.+\\.sql$");

    @Autowired
    private PostgreSQLContainer<?> postgres;

    @Test
    void rowsOfTheV1Shape_areRekeyedOnSystemIds_andKeepTheirLegacyKeys() throws Exception {
        String database = createDatabase("legacy");
        try {
            assertTrue(migrate(database, lastVersionBeforeV47(), "classpath:db/migration").success);
            long patientId;
            long metricNormal;
            long metricAbnormal;
            long symptomId;
            try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
                assertEquals(0L, scalarLong(statement, "SELECT COUNT(*) FROM information_schema.columns WHERE table_name IN "
                        + "('health_metrics', 'symptom_logs') AND column_name = 'patient_system_id'"));
                scalarLong(statement, userInsert("Legacy", "Patient", "legacy.patient@example.com", "PATIENT", "PAT-00001"));
                patientId = scalarLong(statement, "INSERT INTO patients (system_id) VALUES ('PAT-00001') RETURNING patient_id");
                metricNormal = scalarLong(statement, "INSERT INTO health_metrics (patient_id, type, value, unit) "
                        + "VALUES (" + patientId + ", 'HEART_RATE', 72, 'bpm') RETURNING metric_id");
                metricAbnormal = scalarLong(statement, "INSERT INTO health_metrics (patient_id, type, value, unit, is_abnormal, source, device_id) "
                        + "VALUES (" + patientId + ", 'BP_SYSTOLIC', 165, 'mmHg', TRUE, 'DEVICE', 'cuff-9') RETURNING metric_id");
                symptomId = scalarLong(statement, "INSERT INTO symptom_logs (patient_id, symptoms, severity, duration_days) "
                        + "VALUES (" + patientId + ", '[\"Cough\"]'::jsonb, 'MODERATE', 3) RETURNING symptom_id");
            }

            assertTrue(migrate(database, V47, "classpath:db/migration").success);

            try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
                assertEquals(1L, scalarLong(statement, "SELECT COUNT(*) FROM health_metrics WHERE metric_id = " + metricNormal
                        + " AND patient_system_id = 'PAT-00001' AND patient_id = " + patientId
                        + " AND severity = 'NORMAL' AND NOT is_abnormal AND recorded_by_system_id IS NULL AND reading_group_id IS NULL"));
                assertEquals(1L, scalarLong(statement, "SELECT COUNT(*) FROM health_metrics WHERE metric_id = " + metricAbnormal
                        + " AND patient_system_id = 'PAT-00001' AND severity = 'ABNORMAL' AND is_abnormal AND device_id = 'cuff-9'"));
                assertEquals(1L, scalarLong(statement, "SELECT COUNT(*) FROM symptom_logs WHERE symptom_id = " + symptomId
                        + " AND patient_system_id = 'PAT-00001' AND patient_id = " + patientId
                        + " AND NOT is_deleted AND deleted_at IS NULL AND logged_by_system_id IS NULL"));

                assertEquals(2L, scalarLong(statement, "SELECT COUNT(*) FROM information_schema.columns WHERE is_nullable = 'YES' "
                        + "AND table_name IN ('health_metrics', 'symptom_logs') AND column_name = 'patient_id'"));
                assertEquals(2L, scalarLong(statement, "SELECT COUNT(*) FROM information_schema.columns WHERE is_nullable = 'NO' "
                        + "AND table_name IN ('health_metrics', 'symptom_logs') AND column_name = 'patient_system_id'"));
                assertEquals(3L, scalarLong(statement, "SELECT COUNT(*) FROM pg_indexes WHERE indexname IN "
                        + "('idx_hm_patient_type_recorded', 'idx_hm_patient_recorded', 'idx_sl_patient_logged')"));

                // New rows: system ids only; severity and the abnormal flag must agree; durations are bounded
                statement.execute("INSERT INTO health_metrics (patient_system_id, type, value, unit, severity, is_abnormal) "
                        + "VALUES ('PAT-00001', 'SPO2', 85, '%', 'CRITICAL', TRUE)");
                assertRefused(connection, "health_metrics_abnormal_matches_severity_check",
                        "INSERT INTO health_metrics (patient_system_id, type, value, unit, severity, is_abnormal) "
                                + "VALUES ('PAT-00001', 'SPO2', 85, '%', 'CRITICAL', FALSE)");
                assertRefused(connection, "health_metrics_severity_check",
                        "INSERT INTO health_metrics (patient_system_id, type, value, unit, severity, is_abnormal) "
                                + "VALUES ('PAT-00001', 'SPO2', 85, '%', 'SEVERE', TRUE)");
                assertRefused(connection, "health_metrics_patient_system_id_fkey",
                        "INSERT INTO health_metrics (patient_system_id, type, value, unit) VALUES ('PAT-09999', 'STEPS', 1, 'steps')");
                assertRefused(connection, "symptom_logs_duration_days_check",
                        "INSERT INTO symptom_logs (patient_system_id, symptoms, duration_days) VALUES ('PAT-00001', '[]'::jsonb, 3651)");
                assertRefused(connection, "patient_system_id",
                        "INSERT INTO symptom_logs (patient_id, symptoms) VALUES (" + patientId + ", '[]'::jsonb)");
            }
            assertEquals(0, migrate(database, V47, "classpath:db/migration").migrationsExecuted);
        } finally {
            dropDatabase(database);
        }
    }

    @Test
    void aDurationTheNewRuleRefuses_isKept_andTheCheckIsSkipped() throws Exception {
        String database = createDatabase("awkward");
        try {
            assertTrue(migrate(database, lastVersionBeforeV47(), "classpath:db/migration").success);
            try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
                scalarLong(statement, userInsert("Awkward", "Patient", "awkward.patient@example.com", "PATIENT", "PAT-00001"));
                long patientId = scalarLong(statement, "INSERT INTO patients (system_id) VALUES ('PAT-00001') RETURNING patient_id");
                statement.execute("INSERT INTO symptom_logs (patient_id, symptoms, duration_days) "
                        + "VALUES (" + patientId + ", '[\"Back pain\"]'::jsonb, 5000)");
            }
            assertTrue(migrate(database, V47, "classpath:db/migration").success);
            try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
                assertEquals(1L, scalarLong(statement, "SELECT COUNT(*) FROM symptom_logs WHERE duration_days = 5000 "
                        + "AND patient_system_id = 'PAT-00001'"));
                assertEquals(0L, scalarLong(statement, "SELECT COUNT(*) FROM pg_constraint "
                        + "WHERE conname = 'symptom_logs_duration_days_check'"));
            }
        } finally {
            dropDatabase(database);
        }
    }

    @Test
    void devSeed_createsTheDemoReadingsAndSymptomLog_andIsIdempotent() throws Exception {
        String database = createDatabase("seed");
        try {
            assertTrue(migrate(database, null, "classpath:db/migration", "classpath:db/seed").success);
            String seed = new ClassPathResource("db/seed/R__dev_seed_47_health.sql").getContentAsString(StandardCharsets.UTF_8);
            try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
                assertDemoData(statement);
                statement.execute(seed);
                assertDemoData(statement);

                // Nothing at all when the demo users are missing
                statement.execute("DELETE FROM health_metrics");
                statement.execute("DELETE FROM symptom_logs");
                statement.execute("UPDATE users SET email = 'renamed.' || email WHERE email = 'linda.moyo@carenexus.com'");
                statement.execute(seed);
                assertEquals(0L, scalarLong(statement, "SELECT (SELECT COUNT(*) FROM health_metrics) + (SELECT COUNT(*) FROM symptom_logs)"));
            }
        } finally {
            dropDatabase(database);
        }
    }

    private static void assertDemoData(Statement statement) throws SQLException {
        String patient = "(SELECT system_id FROM users WHERE email = 'linda.moyo@carenexus.com')";
        String doctor = "(SELECT system_id FROM users WHERE email = 'james.banda@carenexus.com')";
        assertEquals(14L, scalarLong(statement, "SELECT COUNT(*) FROM health_metrics WHERE patient_system_id = " + patient
                + " AND source = 'MANUAL' AND reading_group_id LIKE '00470000-%'"));
        assertEquals(12L, scalarLong(statement, "SELECT COUNT(*) FROM health_metrics WHERE recorded_by_system_id = " + patient));
        assertEquals(2L, scalarLong(statement, "SELECT COUNT(*) FROM health_metrics WHERE recorded_by_system_id = " + doctor
                + " AND type IN ('WEIGHT', 'HEIGHT')"));
        // Units are the one unit of each type and severities follow the default adult ranges
        assertEquals(0L, scalarLong(statement, "SELECT COUNT(*) FROM health_metrics WHERE NOT ("
                + "(type IN ('BP_SYSTOLIC', 'BP_DIASTOLIC') AND unit = 'mmHg') OR (type = 'HEART_RATE' AND unit = 'bpm') "
                + "OR (type = 'WEIGHT' AND unit = 'kg') OR (type = 'HEIGHT' AND unit = 'cm'))"));
        assertEquals(2L, scalarLong(statement, "SELECT COUNT(*) FROM health_metrics WHERE severity = 'ABNORMAL' AND is_abnormal"));
        assertEquals(2L, scalarLong(statement, "SELECT COUNT(*) FROM health_metrics WHERE severity = 'ABNORMAL' AND "
                + "((type = 'BP_SYSTOLIC' AND value > 139) OR (type = 'BP_DIASTOLIC' AND value > 89))"));
        assertEquals(0L, scalarLong(statement, "SELECT COUNT(*) FROM health_metrics WHERE recorded_at > LOCALTIMESTAMP"));
        BigDecimal bmi = scalarDecimal(statement, "SELECT ROUND(w.value / ((h.value / 100) * (h.value / 100)), 1) "
                + "FROM health_metrics w, health_metrics h WHERE w.type = 'WEIGHT' AND h.type = 'HEIGHT'");
        assertEquals(0, new BigDecimal("24.2").compareTo(bmi));
        assertEquals(1L, scalarLong(statement, "SELECT COUNT(*) FROM symptom_logs WHERE patient_system_id = " + patient
                + " AND logged_by_system_id = " + patient + " AND severity = 'MILD' AND NOT is_deleted "
                + "AND jsonb_typeof(symptoms) = 'array' AND source = 'PATIENT_REPORTED'"));
    }

    // ==================== HELPERS ====================

    private static String userInsert(String firstName, String lastName, String email, String role, String systemId) {
        return "INSERT INTO users (first_name, last_name, email, role, system_id) VALUES ('" + firstName + "', '"
                + lastName + "', '" + email + "', '" + role + "', '" + systemId + "') RETURNING user_id";
    }

    private static void assertRefused(Connection connection, String expected, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            SQLException refused = assertThrows(SQLException.class, () -> statement.execute(sql));
            assertTrue(String.valueOf(refused.getMessage()).contains(expected),
                    "expected '" + expected + "' in: " + refused.getMessage());
        }
    }

    /** The highest schema version below V47 on the classpath (Flyway needs an existing version as its target). */
    private static String lastVersionBeforeV47() throws Exception {
        MigrationVersion v47 = MigrationVersion.fromVersion(V47);
        MigrationVersion last = null;
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/*.sql")) {
            Matcher matcher = resource.getFilename() == null ? null : VERSIONED.matcher(resource.getFilename());
            if (matcher != null && matcher.matches()) {
                MigrationVersion version = MigrationVersion.fromVersion(matcher.group(1).replace('_', '.'));
                if (version.compareTo(v47) < 0 && (last == null || version.compareTo(last) > 0)) {
                    last = version;
                }
            }
        }
        assertNotNull(last, "no schema migration below V47 found on the classpath");
        return last.getVersion();
    }

    /** Same settings as spring.flyway in application.yml; {@code target} null = every migration. */
    private MigrateResult migrate(String database, String target, String... locations) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(jdbcUrl(database), postgres.getUsername(), postgres.getPassword())
                .locations(locations)
                .baselineOnMigrate(true)
                .baselineVersion("0");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load().migrate();
    }

    private String createDatabase(String label) throws Exception {
        String database = "health_" + label + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

    private Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(database), postgres.getUsername(), postgres.getPassword());
    }

    private String jdbcUrl(String database) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
                + "/" + database;
    }

    private static long scalarLong(Statement statement, String sql) throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private static BigDecimal scalarDecimal(Statement statement, String sql) throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getBigDecimal(1);
        }
    }
}
