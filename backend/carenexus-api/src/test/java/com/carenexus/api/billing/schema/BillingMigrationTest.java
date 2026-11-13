package com.carenexus.api.billing.schema;

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
 * V49 against databases in the states it can meet, each in its own fresh database inside the shared test
 * container (same Flyway settings as application.yml):
 * <ul>
 *   <li>an empty schema: every constraint ends up validated, numbering starts at 1, the clinic fees are there;</li>
 *   <li>billing rows written in the V1 shape (integer patient_id / user_id): the system ids are backfilled, the
 *       legacy keys stay but are no longer required, and nothing is lost;</li>
 *   <li>V1 rows the new rules would refuse (a CRYPTO method, a payment without a method or amount): the migration
 *       still succeeds, keeps the rows and enforces the rules for every new row;</li>
 *   <li>the developer seed: the three demo invoices, idempotent.</li>
 * </ul>
 */
public class BillingMigrationTest extends BaseIntegrationTest {

    private static final String V49 = "49";
    private static final Pattern VERSIONED = Pattern.compile("^V(\\d+(?:[._]\\d+)*)__.+\\.sql$");

    private static final String BILLING_TABLES =
            "('payment_methods'::regclass, 'invoices'::regclass, 'payments'::regclass, 'insurance_claims'::regclass)";

    @Autowired
    private PostgreSQLContainer<?> postgres;

    @Test
    void emptyDatabase_endsWithValidatedConstraints_numberingFromOne_andTheClinicFees() throws Exception {
        String database = createDatabase("empty");
        try {
            assertTrue(migrate(database, V49, "classpath:db/migration").success);
            try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
                assertEquals(0L, scalarLong(statement, "SELECT COUNT(*) FROM pg_constraint WHERE contype = 'c' "
                        + "AND NOT convalidated AND conrelid IN " + BILLING_TABLES));
                assertEquals("NO", scalarString(statement, "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_name = 'payments' AND column_name = 'method'"));
                assertEquals(1L, scalarLong(statement, "SELECT nextval('invoice_no_seq')"));
                assertEquals(3L, scalarLong(statement, "SELECT COUNT(*) FROM billing_fee_schedule "
                        + "WHERE doctor_system_id IS NULL AND is_active AND currency = 'KES'"));
                assertEquals(0, new BigDecimal("1500.00").compareTo(scalarDecimal(statement,
                        "SELECT amount FROM billing_fee_schedule WHERE appointment_type = 'VIDEO'")));
                assertEquals(0, new BigDecimal("2000.00").compareTo(scalarDecimal(statement,
                        "SELECT amount FROM billing_fee_schedule WHERE appointment_type = 'IN_PERSON'")));
                assertEquals(0, new BigDecimal("1000.00").compareTo(scalarDecimal(statement,
                        "SELECT amount FROM billing_fee_schedule WHERE appointment_type = 'PHONE'")));
                // The schema-only path creates no billing rows.
                assertEquals(0L, scalarLong(statement, "SELECT (SELECT COUNT(*) FROM invoices) + (SELECT COUNT(*) FROM payments) "
                        + "+ (SELECT COUNT(*) FROM insurance_claims) + (SELECT COUNT(*) FROM payment_methods) "
                        + "+ (SELECT COUNT(*) FROM payment_events)"));
                // The V3 views still work: V49 touched nothing they read.
                assertEquals(0L, scalarLong(statement, "SELECT COUNT(*) FROM v_patient_dashboard"));
                assertEquals(0L, scalarLong(statement, "SELECT COUNT(*) FROM v_doctor_workload"));
            }
        } finally {
            dropDatabase(database);
        }
    }

    @Test
    void rowsOfTheV1Shape_getTheirSystemIdsBackfilled_andKeepTheirLegacyKeys() throws Exception {
        String database = createDatabase("legacy");
        try {
            assertTrue(migrate(database, lastVersionBeforeV49(), "classpath:db/migration").success);

            long patientId;
            long otherPatientId;
            long patientUserId;
            long cardMethodId;
            long mpesaMethodId;
            long paidInvoiceId;
            long draftInvoiceId;
            long sentInvoiceId;
            try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
                // Before V49 the billing tables have the V1 shape: no system id columns at all.
                assertEquals(0L, scalarLong(statement, "SELECT COUNT(*) FROM information_schema.columns WHERE table_name IN "
                        + "('invoices', 'payments', 'insurance_claims', 'payment_methods') "
                        + "AND column_name IN ('patient_system_id', 'system_id')"));

                patientUserId = scalarLong(statement, userInsert("Legacy", "Patient", "legacy.patient@example.com", "PATIENT", "PAT-00001"));
                patientId = scalarLong(statement, "INSERT INTO patients (system_id) VALUES ('PAT-00001') RETURNING patient_id");
                scalarLong(statement, userInsert("Other", "Patient", "legacy.other@example.com", "PATIENT", "PAT-00002"));
                otherPatientId = scalarLong(statement, "INSERT INTO patients (system_id) VALUES ('PAT-00002') RETURNING patient_id");
                scalarLong(statement, userInsert("Legacy", "Doctor", "legacy.doctor@example.com", "DOCTOR", "DOC-00001"));
                statement.execute("INSERT INTO doctors (system_id) VALUES ('DOC-00001')");
                scalarLong(statement, userInsert("Second", "Doctor", "legacy.doctor2@example.com", "DOCTOR", "DOC-00002"));
                statement.execute("INSERT INTO doctors (system_id) VALUES ('DOC-00002')");
                long appointmentId = scalarLong(statement, "INSERT INTO appointments (patient_system_id, doctor_system_id, scheduled_at, status) "
                        + "VALUES ('PAT-00001', 'DOC-00001', TIMESTAMP '2025-06-01 09:00:00', 'COMPLETED') RETURNING appointment_id");
                long secondAppointmentId = scalarLong(statement, "INSERT INTO appointments (patient_system_id, doctor_system_id, scheduled_at, status) "
                        + "VALUES ('PAT-00001', 'DOC-00002', TIMESTAMP '2025-06-03 09:00:00', 'COMPLETED') RETURNING appointment_id");
                long consultationId = scalarLong(statement, "INSERT INTO consultations (appointment_id, patient_system_id, doctor_system_id, status) "
                        + "VALUES (" + secondAppointmentId + ", 'PAT-00001', 'DOC-00002', 'COMPLETED') RETURNING consultation_id");

                // Two default methods for one user (V1 had no rule), the older one a card with a gateway token.
                cardMethodId = scalarLong(statement, "INSERT INTO payment_methods (user_id, type, provider, last_four, token, is_default) "
                        + "VALUES (" + patientUserId + ", 'CARD', 'Visa', '4242', 'tok_legacy_card', TRUE) RETURNING method_id");
                mpesaMethodId = scalarLong(statement, "INSERT INTO payment_methods (user_id, type, is_default) "
                        + "VALUES (" + patientUserId + ", 'MPESA', TRUE) RETURNING method_id");

                paidInvoiceId = scalarLong(statement, "INSERT INTO invoices (patient_id, appointment_id, invoice_no, subtotal, total, status, "
                        + "created_at, paid_at) VALUES (" + patientId + ", " + appointmentId + ", 'INV-2025-000007', 100.00, 100.00, 'PAID', "
                        + "TIMESTAMP '2025-06-01 10:00:00', TIMESTAMP '2025-06-02 11:30:00') RETURNING invoice_id");
                draftInvoiceId = scalarLong(statement, "INSERT INTO invoices (patient_id, consultation_id, invoice_no, status, created_at) "
                        + "VALUES (" + patientId + ", " + consultationId + ", 'LEGACY-0001', 'DRAFT', TIMESTAMP '2025-06-03 10:00:00') RETURNING invoice_id");
                sentInvoiceId = scalarLong(statement, "INSERT INTO invoices (patient_id, invoice_no, subtotal, total, status, created_at) "
                        + "VALUES (" + otherPatientId + ", 'INV-2025-000003', 50.00, 50.00, 'SENT', TIMESTAMP '2025-06-04 10:00:00') RETURNING invoice_id");

                statement.execute("INSERT INTO payments (patient_id, invoice_id, payment_method_id, amount, status, created_at, paid_at) "
                        + "VALUES (" + patientId + ", " + paidInvoiceId + ", " + cardMethodId + ", 100.00, 'COMPLETED', "
                        + "TIMESTAMP '2025-06-02 11:29:00', TIMESTAMP '2025-06-02 11:30:00')");
                statement.execute("INSERT INTO insurance_claims (patient_id, invoice_id, insurance_provider, policy_number, claim_amount, "
                        + "status, submitted_at) VALUES (" + otherPatientId + ", " + sentInvoiceId + ", 'AAR', 'P-9', 50.00, 'SUBMITTED', "
                        + "TIMESTAMP '2025-06-05 08:00:00')");
            }

            assertTrue(migrate(database, V49, "classpath:db/migration").success);

            try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
                // ---- invoices ----
                assertEquals("PAT-00001", scalarString(statement, "SELECT patient_system_id FROM invoices WHERE invoice_id = " + paidInvoiceId));
                assertEquals("PAT-00001", scalarString(statement, "SELECT patient_system_id FROM invoices WHERE invoice_id = " + draftInvoiceId));
                assertEquals("PAT-00002", scalarString(statement, "SELECT patient_system_id FROM invoices WHERE invoice_id = " + sentInvoiceId));
                assertEquals(patientId, scalarLong(statement, "SELECT patient_id FROM invoices WHERE invoice_id = " + paidInvoiceId),
                        "the legacy key keeps its value");
                // The issuing doctor comes from the linked visit; an invoice without a visit has none.
                assertEquals("DOC-00001", scalarString(statement, "SELECT doctor_system_id FROM invoices WHERE invoice_id = " + paidInvoiceId));
                assertEquals("DOC-00002", scalarString(statement, "SELECT doctor_system_id FROM invoices WHERE invoice_id = " + draftInvoiceId));
                assertNull(scalarString(statement, "SELECT doctor_system_id FROM invoices WHERE invoice_id = " + sentInvoiceId));
                // Issued invoices get their creation time as issue time; a PAID one is paid in full; a DRAFT was never issued.
                assertEquals(1L, scalarLong(statement, "SELECT COUNT(*) FROM invoices WHERE invoice_id = " + paidInvoiceId
                        + " AND issued_at = created_at AND amount_paid = total AND updated_at = paid_at"));
                assertEquals(1L, scalarLong(statement, "SELECT COUNT(*) FROM invoices WHERE invoice_id = " + sentInvoiceId
                        + " AND issued_at = created_at AND amount_paid = 0 AND updated_at = created_at"));
                assertEquals(1L, scalarLong(statement, "SELECT COUNT(*) FROM invoices WHERE invoice_id = " + draftInvoiceId
                        + " AND issued_at IS NULL AND amount_paid = 0 AND currency = 'KES' AND cancel_reason IS NULL"));
                // Numbering continues after the highest INV-yyyy-nnnnnn number already used.
                assertEquals(8L, scalarLong(statement, "SELECT nextval('invoice_no_seq')"));

                // ---- payments ----
                assertEquals(1L, scalarLong(statement, "SELECT COUNT(*) FROM payments WHERE patient_system_id = 'PAT-00001' "
                        + "AND patient_id = " + patientId + " AND method = 'CARD' AND refunded_amount = 0 AND updated_at = paid_at"));
                assertEquals("NO", scalarString(statement, "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_name = 'payments' AND column_name = 'method'"));

                // ---- insurance_claims ----
                assertEquals(1L, scalarLong(statement, "SELECT COUNT(*) FROM insurance_claims WHERE patient_system_id = 'PAT-00002' "
                        + "AND patient_id = " + otherPatientId + " AND document_ids = '[]'::jsonb AND claim_no IS NULL "
                        + "AND updated_at = submitted_at"));

                // ---- payment_methods ----
                assertEquals(2L, scalarLong(statement, "SELECT COUNT(*) FROM payment_methods WHERE system_id = 'PAT-00001' "
                        + "AND user_id = " + patientUserId + " AND is_active AND NOT is_deleted"));
                assertEquals("tok_legacy_card", scalarString(statement, "SELECT provider_token FROM payment_methods WHERE method_id = " + cardMethodId));
                // Of the two defaults the newer one stays the default.
                assertEquals(mpesaMethodId, scalarLong(statement, "SELECT method_id FROM payment_methods WHERE system_id = 'PAT-00001' AND is_default"));

                // ---- shape after V49 ----
                assertEquals(4L, scalarLong(statement, "SELECT COUNT(*) FROM information_schema.columns WHERE is_nullable = 'YES' AND ("
                        + "(table_name IN ('invoices', 'payments', 'insurance_claims') AND column_name = 'patient_id') "
                        + "OR (table_name = 'payment_methods' AND column_name = 'user_id'))"), "legacy keys are kept and optional");
                assertEquals(4L, scalarLong(statement, "SELECT COUNT(*) FROM information_schema.columns WHERE is_nullable = 'NO' AND ("
                        + "(table_name IN ('invoices', 'payments', 'insurance_claims') AND column_name = 'patient_system_id') "
                        + "OR (table_name = 'payment_methods' AND column_name = 'system_id'))"), "system ids are required");
                assertEquals(0L, scalarLong(statement, "SELECT COUNT(*) FROM pg_constraint WHERE contype = 'c' "
                        + "AND NOT convalidated AND conrelid IN " + BILLING_TABLES), "clean V1 rows satisfy every new CHECK");

                // New rows no longer carry the integer keys, and the visit rule holds against the old rows.
                statement.execute("INSERT INTO invoices (patient_system_id, invoice_no, status) VALUES ('PAT-00002', 'INV-2026-000008', 'PARTIALLY_PAID')");
                statement.execute("INSERT INTO payment_methods (system_id, type) VALUES ('PAT-00002', 'BANK_TRANSFER')");
                assertRefused(connection, "ux_invoices_appointment_active", "INSERT INTO invoices (patient_system_id, appointment_id, invoice_no) "
                        + "SELECT 'PAT-00001', appointment_id, 'INV-2026-000009' FROM invoices WHERE invoice_id = " + paidInvoiceId);
                assertRefused(connection, "ux_insurance_claims_open", "INSERT INTO insurance_claims (patient_system_id, invoice_id, "
                        + "insurance_provider, policy_number, claim_amount) VALUES ('PAT-00002', " + sentInvoiceId + ", 'AAR', 'P-9', 10.00)");
                assertRefused(connection, "ux_payment_methods_default",
                        "INSERT INTO payment_methods (system_id, type, is_default) VALUES ('PAT-00001', 'CASH', TRUE)");
            }

            // V49 is not applied twice, and a second run changes nothing.
            assertEquals(0, migrate(database, V49, "classpath:db/migration").migrationsExecuted);
        } finally {
            dropDatabase(database);
        }
    }

    @Test
    void v1RowsTheNewRulesRefuse_areKept_andTheRulesApplyToEveryNewRow() throws Exception {
        String database = createDatabase("awkward");
        try {
            assertTrue(migrate(database, lastVersionBeforeV49(), "classpath:db/migration").success);

            long cryptoMethodId;
            try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
                long userId = scalarLong(statement, userInsert("Legacy", "Patient", "awkward.patient@example.com", "PATIENT", "PAT-00001"));
                long patientId = scalarLong(statement, "INSERT INTO patients (system_id) VALUES ('PAT-00001') RETURNING patient_id");
                // A type V1 allowed and V49 no longer does, saved as the user's default.
                cryptoMethodId = scalarLong(statement, "INSERT INTO payment_methods (user_id, type, provider, is_default) "
                        + "VALUES (" + userId + ", 'CRYPTO', 'Wallet', TRUE) RETURNING method_id");
                // A payment through that method (no usable method to record), and one with a zero amount.
                statement.execute("INSERT INTO payments (patient_id, payment_method_id, amount, status) "
                        + "VALUES (" + patientId + ", " + cryptoMethodId + ", 25.00, 'COMPLETED')");
                statement.execute("INSERT INTO payments (patient_id, amount, status) VALUES (" + patientId + ", 0.00, 'FAILED')");
            }

            assertTrue(migrate(database, V49, "classpath:db/migration").success);

            try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
                // Nothing was deleted or invented.
                assertEquals(2L, scalarLong(statement, "SELECT COUNT(*) FROM payments WHERE patient_system_id = 'PAT-00001' AND method IS NULL"));
                assertEquals(1L, scalarLong(statement, "SELECT COUNT(*) FROM payment_methods WHERE method_id = " + cryptoMethodId
                        + " AND type = 'CRYPTO' AND system_id = 'PAT-00001' AND is_deleted AND NOT is_active AND NOT is_default"),
                        "the unsupported method is kept but retired");
                assertEquals("YES", scalarString(statement, "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_name = 'payments' AND column_name = 'method'"));

                // Exactly the three rules those rows break stay unvalidated; all the others are validated.
                assertEquals("payment_methods_type_allowed,payments_amount_positive,payments_method_allowed",
                        scalarString(statement, "SELECT string_agg(conname, ',' ORDER BY conname) FROM pg_constraint "
                                + "WHERE contype = 'c' AND NOT convalidated AND conrelid IN " + BILLING_TABLES));

                // An unvalidated CHECK still applies to every new or changed row.
                assertRefused(connection, "payment_methods_type_allowed",
                        "INSERT INTO payment_methods (system_id, type) VALUES ('PAT-00001', 'CRYPTO')");
                assertRefused(connection, "payments_method_allowed",
                        "INSERT INTO payments (patient_system_id, amount) VALUES ('PAT-00001', 10.00)");
                assertRefused(connection, "payments_amount_positive",
                        "INSERT INTO payments (patient_system_id, amount, method) VALUES ('PAT-00001', 0.00, 'CASH')");
                assertRefused(connection, "payments_method_allowed",
                        "UPDATE payments SET status = 'REFUNDED' WHERE method IS NULL AND amount > 0");
                statement.execute("INSERT INTO payments (patient_system_id, amount, method) VALUES ('PAT-00001', 10.00, 'CASH')");
                statement.execute("INSERT INTO payment_methods (system_id, type, is_default) VALUES ('PAT-00001', 'CASH', TRUE)");
            }
        } finally {
            dropDatabase(database);
        }
    }

    @Test
    void devSeed_createsTheThreeDemoInvoices_andIsIdempotent() throws Exception {
        String database = createDatabase("seed");
        try {
            // The developer path: every schema migration plus the seed folder.
            assertTrue(migrate(database, null, "classpath:db/migration", "classpath:db/seed").success);
            String seed = new ClassPathResource("db/seed/R__dev_seed_49_billing.sql").getContentAsString(StandardCharsets.UTF_8);

            try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
                assertDemoInvoices(statement);
                long sequenceBefore = scalarLong(statement, "SELECT last_value FROM invoice_no_seq");
                String otherBillingRows = "SELECT (SELECT COUNT(*) FROM invoices) + (SELECT COUNT(*) FROM payments) "
                        + "+ (SELECT COUNT(*) FROM insurance_claims) + (SELECT COUNT(*) FROM payment_methods) "
                        + "+ (SELECT COUNT(*) FROM payment_events)";
                long billingRowsBefore = scalarLong(statement, otherBillingRows);

                // Flyway re-runs a repeatable script whenever its checksum changes: a second run must change nothing.
                statement.execute(seed);
                assertDemoInvoices(statement);
                assertEquals(sequenceBefore, scalarLong(statement, "SELECT last_value FROM invoice_no_seq"),
                        "the demo numbers are fixed and never drawn from the sequence");

                assertEquals(billingRowsBefore, scalarLong(statement, otherBillingRows), "a re-run adds no billing row");

                // And nothing at all when the demo users are missing.
                statement.execute("UPDATE users SET email = 'renamed.' || email WHERE email IN "
                        + "('linda.moyo@carenexus.com', 'james.banda@carenexus.com')");
                statement.execute("DELETE FROM invoices WHERE invoice_no LIKE 'INV-2026-9000%' "
                        + "AND NOT EXISTS (SELECT 1 FROM payments p WHERE p.invoice_id = invoices.invoice_id) "
                        + "AND NOT EXISTS (SELECT 1 FROM insurance_claims c WHERE c.invoice_id = invoices.invoice_id)");
                long remaining = scalarLong(statement, "SELECT COUNT(*) FROM invoices WHERE invoice_no LIKE 'INV-2026-9000%'");
                statement.execute(seed);
                assertEquals(remaining, scalarLong(statement, "SELECT COUNT(*) FROM invoices WHERE invoice_no LIKE 'INV-2026-9000%'"));
            }
        } finally {
            dropDatabase(database);
        }
    }

    private static void assertDemoInvoices(Statement statement) throws SQLException {
        String demo = " FROM invoices i JOIN users p ON p.system_id = i.patient_system_id JOIN users d ON d.system_id = i.doctor_system_id "
                + "WHERE p.email = 'linda.moyo@carenexus.com' AND d.email = 'james.banda@carenexus.com' "
                + "AND i.created_by_system_id = i.doctor_system_id AND i.currency = 'KES' "
                + "AND i.appointment_id IS NULL AND i.consultation_id IS NULL AND i.issued_at IS NOT NULL ";
        assertEquals(3L, scalarLong(statement, "SELECT COUNT(*)" + demo + "AND i.invoice_no LIKE 'INV-2026-9000%'"));
        assertEquals(1L, scalarLong(statement, "SELECT COUNT(*)" + demo + "AND i.invoice_no = 'INV-2026-900001' "
                + "AND i.status = 'SENT' AND i.amount_paid = 0 AND i.paid_at IS NULL AND i.due_date > CURRENT_DATE"));
        assertEquals(1L, scalarLong(statement, "SELECT COUNT(*)" + demo + "AND i.invoice_no = 'INV-2026-900002' "
                + "AND i.status = 'PAID' AND i.amount_paid = i.total AND i.total > 0 AND i.paid_at IS NOT NULL"));
        assertEquals(1L, scalarLong(statement, "SELECT COUNT(*)" + demo + "AND i.invoice_no = 'INV-2026-900003' "
                + "AND i.status = 'OVERDUE' AND i.amount_paid = 0 AND i.due_date < CURRENT_DATE"));
        // Totals are consistent with the line items, which use the pinned keys.
        assertEquals(3L, scalarLong(statement, "SELECT COUNT(*) FROM invoices i WHERE i.invoice_no LIKE 'INV-2026-9000%' "
                + "AND i.total = i.subtotal - i.discount + i.tax "
                + "AND i.subtotal = (SELECT SUM((item ->> 'amount')::numeric) FROM jsonb_array_elements(i.line_items) item) "
                + "AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(i.line_items) item "
                + "WHERE NOT (item ?& ARRAY['description', 'quantity', 'unitPrice', 'amount']) "
                + "OR (item ->> 'amount')::numeric <> (item ->> 'quantity')::numeric * (item ->> 'unitPrice')::numeric)"));
    }

    // ==================== HELPERS ====================

    private static String userInsert(String firstName, String lastName, String email, String role, String systemId) {
        return "INSERT INTO users (first_name, last_name, email, role, system_id) VALUES ('" + firstName + "', '"
                + lastName + "', '" + email + "', '" + role + "', '" + systemId + "') RETURNING user_id";
    }

    /** Runs a statement the database must refuse and checks that the refusal names the expected constraint. */
    private static void assertRefused(Connection connection, String expectedConstraint, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            SQLException refused = assertThrows(SQLException.class, () -> statement.execute(sql));
            assertTrue(String.valueOf(refused.getMessage()).contains(expectedConstraint),
                    "expected '" + expectedConstraint + "' in: " + refused.getMessage());
        }
    }

    /**
     * The highest schema version below V49 that exists on the classpath: migrating to it gives the billing
     * tables exactly as V49 finds them (Flyway needs an existing version as its target).
     */
    private static String lastVersionBeforeV49() throws Exception {
        MigrationVersion v49 = MigrationVersion.fromVersion(V49);
        MigrationVersion last = null;
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/*.sql")) {
            Matcher matcher = resource.getFilename() == null ? null : VERSIONED.matcher(resource.getFilename());
            if (matcher != null && matcher.matches()) {
                MigrationVersion version = MigrationVersion.fromVersion(matcher.group(1).replace('_', '.'));
                if (version.compareTo(v49) < 0 && (last == null || version.compareTo(last) > 0)) {
                    last = version;
                }
            }
        }
        assertNotNull(last, "no schema migration below V49 found on the classpath");
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
        String database = "billing_" + label + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

    private static String scalarString(Statement statement, String sql) throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    private static BigDecimal scalarDecimal(Statement statement, String sql) throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getBigDecimal(1);
        }
    }
}
