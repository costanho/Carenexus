package com.carenexus.api.common.migration;

import com.carenexus.api.BaseIntegrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Evidence for both Flyway paths:
 * <ul>
 *   <li>production: {@code classpath:db/migration} alone migrates an empty database;</li>
 *   <li>development: {@code classpath:db/migration} + {@code classpath:db/seed} migrates an empty database;</li>
 *   <li>the versions of the two folders never overlap, and no new versioned seed file was added (new dev data
 *       must be a repeatable {@code R__dev_seed_*.sql}, see db/seed/README.md).</li>
 * </ul>
 * Each path runs against its own fresh database inside the shared test container, with the same Flyway settings
 * as application.yml.
 */
public class MigrationPathsTest extends BaseIntegrationTest {

    private static final Pattern VERSIONED = Pattern.compile("^V(\\d+(?:[._]\\d+)*)__.+\\.sql$");
    /** The versioned seed files that existed before the "repeatable only" rule. Do not extend this set. */
    private static final Set<String> LEGACY_SEED_VERSIONS = Set.of("2", "4", "5", "6", "7", "8", "20", "21");

    @Autowired
    private PostgreSQLContainer<?> postgres;

    // ==================== FILE RULES ====================

    @Test
    void migrationAndSeedVersions_areDisjoint() throws Exception {
        Set<String> schema = versions("classpath:db/migration/*.sql");
        Set<String> seed = versions("classpath:db/seed/*.sql");
        assertFalse(schema.isEmpty(), "no schema migrations found on the classpath");
        assertFalse(seed.isEmpty(), "no seed migrations found on the classpath");

        Set<String> both = new TreeSet<>(schema);
        both.retainAll(seed);
        assertTrue(both.isEmpty(), "versions used in both db/migration and db/seed: " + both);
        assertTrue(schema.contains("23"), "V23 (system id sequences) is missing from db/migration");
    }

    @Test
    void noNewVersionedSeedFiles_newDevDataIsRepeatable() throws Exception {
        Set<String> seed = versions("classpath:db/seed/*.sql");
        Set<String> unexpected = new TreeSet<>(seed);
        unexpected.removeAll(LEGACY_SEED_VERSIONS);
        assertTrue(unexpected.isEmpty(), "new versioned seed files " + unexpected
                + ": dev-only data must be a repeatable R__dev_seed_<NN>_<slice>.sql (db/seed/README.md)");

        for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath:db/seed/*.sql")) {
            String name = resource.getFilename();
            assertTrue(name != null && (VERSIONED.matcher(name).matches() || name.startsWith("R__dev_seed_")),
                    "seed file with an unexpected name: " + name);
        }
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/*.sql")) {
            String name = resource.getFilename();
            assertTrue(name != null && VERSIONED.matcher(name).matches(),
                    "db/migration holds versioned schema migrations only: " + name);
        }
    }

    // ==================== BOTH PATHS MIGRATE AN EMPTY DATABASE ====================

    @Test
    void schemaOnly_migratesAnEmptyDatabase() throws Exception {
        String database = createDatabase("schema_only");
        try {
            MigrateResult result = migrate(database, "classpath:db/migration");
            assertTrue(result.success);
            assertEquals(versions("classpath:db/migration/*.sql").size(), result.migrationsExecuted);
            assertEquals(0, repeatables("classpath:db/migration/*.sql"));

            try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
                // No users yet: every system id sequence hands out 1 first.
                for (String sequence : new String[]{"system_id_pat_seq", "system_id_doc_seq", "system_id_cgv_seq", "system_id_adm_seq"}) {
                    assertEquals(1L, scalarLong(statement, "SELECT nextval('" + sequence + "')"), sequence);
                }
                assertEquals(0L, scalarLong(statement, "SELECT COUNT(*) FROM users"), "schema-only path must not create users");
                assertEquals(1L, scalarLong(statement, "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = 'idempotency_keys'"));
                assertEquals(2L, scalarLong(statement, "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = 'dependent_access' AND column_name IN ('can_view_billing', 'can_manage_billing')"));
                assertEquals(5L, scalarLong(statement, "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = 'users' AND column_name IN ('email_verified_at', 'must_change_password', "
                        + "'password_changed_at', 'closed_at', 'closure_reason')"));
                assertEquals(6L, scalarLong(statement, "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = 'doctors' AND column_name IN ('verification_status', 'verified_by_system_id', "
                        + "'verified_at', 'rejection_reason', 'licensing_body', 'license_country')"));
            }
        } finally {
            dropDatabase(database);
        }
    }

    @Test
    void schemaPlusSeed_migratesAnEmptyDatabase_andBackfillsTheNewColumns() throws Exception {
        String database = createDatabase("with_seed");
        try {
            MigrateResult result = migrate(database, "classpath:db/migration", "classpath:db/seed");
            assertTrue(result.success);
            // every versioned file of both folders, plus the repeatable dev seeds
            assertEquals(versions("classpath:db/migration/*.sql").size() + versions("classpath:db/seed/*.sql").size()
                            + repeatables("classpath:db/seed/*.sql"),
                    result.migrationsExecuted);

            try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
                assertTrue(scalarLong(statement, "SELECT COUNT(*) FROM users") > 0, "the seed creates demo users");

                // Each sequence continues after the highest seeded id of its prefix.
                for (String[] pair : new String[][]{{"PAT", "system_id_pat_seq"}, {"DOC", "system_id_doc_seq"},
                        {"CGV", "system_id_cgv_seq"}, {"ADM", "system_id_adm_seq"}}) {
                    long max = scalarLong(statement, "SELECT COALESCE(MAX(CAST(SUBSTRING(system_id FROM 5) AS BIGINT)), 0) "
                            + "FROM users WHERE system_id LIKE '" + pair[0] + "-%'");
                    assertEquals(max + 1, scalarLong(statement, "SELECT nextval('" + pair[1] + "')"), pair[1]);
                }

                // Backfills: existing accounts count as verified; doctors that were active are APPROVED, the rest PENDING.
                assertEquals(0L, scalarLong(statement, "SELECT COUNT(*) FROM users WHERE email_verified_at IS NULL"));
                assertEquals(0L, scalarLong(statement, "SELECT COUNT(*) FROM users WHERE must_change_password"));
                assertEquals(0L, scalarLong(statement,
                        "SELECT COUNT(*) FROM doctors WHERE is_active AND verification_status <> 'APPROVED'"));
                assertEquals(0L, scalarLong(statement,
                        "SELECT COUNT(*) FROM doctors WHERE NOT is_active AND verification_status <> 'PENDING'"));
                assertTrue(scalarLong(statement, "SELECT COUNT(*) FROM doctors WHERE verification_status = 'APPROVED'") > 0);
                // Billing access is never implied for grants that existed before the flags.
                assertEquals(0L, scalarLong(statement,
                        "SELECT COUNT(*) FROM dependent_access WHERE can_view_billing OR can_manage_billing"));
            }
        } finally {
            dropDatabase(database);
        }
    }

    // ==================== HELPERS ====================

    private static Set<String> versions(String locationPattern) throws Exception {
        Set<String> versions = new TreeSet<>();
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources(locationPattern)) {
            String name = resource.getFilename();
            Matcher matcher = name == null ? null : VERSIONED.matcher(name);
            if (matcher != null && matcher.matches()) {
                // "23_1" and "23.1" are the same Flyway version
                assertTrue(versions.add(matcher.group(1).replace('_', '.')),
                        "duplicate version " + matcher.group(1) + " in " + locationPattern);
            }
        }
        return versions;
    }

    private static int repeatables(String locationPattern) throws Exception {
        int count = 0;
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources(locationPattern)) {
            if (resource.getFilename() != null && resource.getFilename().startsWith("R__")) {
                count++;
            }
        }
        return count;
    }

    /** Same settings as spring.flyway in application.yml. */
    private MigrateResult migrate(String database, String... locations) {
        return Flyway.configure()
                .dataSource(jdbcUrl(database), postgres.getUsername(), postgres.getPassword())
                .locations(locations)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    private String createDatabase(String label) throws Exception {
        String database = "migration_" + label + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

    private static long scalarLong(Statement statement, String sql) throws Exception {
        try (ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }
}
