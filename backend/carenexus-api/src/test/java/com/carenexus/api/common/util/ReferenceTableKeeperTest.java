package com.carenexus.api.common.util;

import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The mechanism CommittedIntegrationTest uses to keep migration-shipped reference rows across its TRUNCATE ...
 * CASCADE, proven on a scratch table that (like billing_fee_schedule) points at users.
 */
public class ReferenceTableKeeperTest extends CommittedIntegrationTest {

    private static final String TABLE = "zz_keeper_fixture";

    @AfterEach
    void dropScratchTables() {
        jdbc.execute("DROP TABLE IF EXISTS " + ReferenceTableKeeper.SNAPSHOT_PREFIX + TABLE);
        jdbc.execute("DROP TABLE IF EXISTS " + TABLE);
    }

    private void createShippedTable() {
        jdbc.execute("CREATE TABLE " + TABLE + " ("
                + "fee_id SERIAL PRIMARY KEY, "
                + "code VARCHAR(40) NOT NULL UNIQUE, "
                + "owner_system_id VARCHAR(20) REFERENCES users(system_id), "
                + "code_upper VARCHAR(40) GENERATED ALWAYS AS (UPPER(code)) STORED)");
        // What a migration would ship: default rows that belong to nobody.
        jdbc.update("INSERT INTO " + TABLE + " (code) VALUES ('default-video'), ('default-in-person')");
    }

    private List<String> codes() {
        return jdbc.queryForList("SELECT code FROM " + TABLE + " ORDER BY fee_id", String.class);
    }

    @Test
    void shippedRowsSurviveTruncateCascade_testRowsDoNot_andIdsContinue() {
        createShippedTable();
        ReferenceTableKeeper keeper = new ReferenceTableKeeper(jdbc, List.of(TABLE));
        keeper.snapshotIfAbsent();

        // A test adds its own row pointing at a fixture user.
        jdbc.update("INSERT INTO " + TABLE + " (code, owner_system_id) VALUES ('doctor-special', ?)", doctor.getSystemId());
        assertEquals(3, codes().size());

        // What CommittedIntegrationTest does between tests: users is truncated, CASCADE empties the table too.
        jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
        assertTrue(codes().isEmpty(), "CASCADE empties a reference table that points at users");

        keeper.restore();
        assertEquals(List.of("default-video", "default-in-person"), codes());
        assertEquals(List.of("DEFAULT-VIDEO", "DEFAULT-IN-PERSON"),
                jdbc.queryForList("SELECT code_upper FROM " + TABLE + " ORDER BY fee_id", String.class));

        // The id sequence continues after the restored rows.
        jdbc.update("INSERT INTO " + TABLE + " (code) VALUES ('added-later')");
        assertEquals(3, jdbc.queryForObject("SELECT fee_id FROM " + TABLE + " WHERE code = 'added-later'", Integer.class));

        // Restoring again is idempotent and drops the row added after the snapshot.
        keeper.restore();
        assertEquals(List.of("default-video", "default-in-person"), codes());
    }

    @Test
    void snapshotIsTakenOnce_andMissingTablesAreIgnored() {
        createShippedTable();
        ReferenceTableKeeper keeper = new ReferenceTableKeeper(jdbc, List.of("zz_no_such_reference_table", TABLE));
        keeper.snapshotIfAbsent();
        jdbc.update("INSERT INTO " + TABLE + " (code) VALUES ('later')");
        keeper.snapshotIfAbsent();   // must not re-snapshot the modified table

        keeper.restore();
        assertEquals(List.of("default-video", "default-in-person"), codes());
        assertTrue(ReferenceTableKeeper.isSnapshotTable(ReferenceTableKeeper.SNAPSHOT_PREFIX + TABLE));
        assertFalse(ReferenceTableKeeper.isSnapshotTable("users"));
    }

    @Test
    void emptyShippedTable_restoresToEmpty_andIdsStartAtOne() {
        jdbc.execute("CREATE TABLE " + TABLE + " (fee_id SERIAL PRIMARY KEY, code VARCHAR(40) NOT NULL)");
        ReferenceTableKeeper keeper = new ReferenceTableKeeper(jdbc, List.of(TABLE));
        keeper.snapshotIfAbsent();
        jdbc.update("INSERT INTO " + TABLE + " (code) VALUES ('test-row')");

        keeper.restore();
        assertTrue(codes().isEmpty());
        jdbc.update("INSERT INTO " + TABLE + " (code) VALUES ('first')");
        assertEquals(1, jdbc.queryForObject("SELECT fee_id FROM " + TABLE, Integer.class));
    }

    @Test
    void onlyPlainTableNamesAreAccepted() {
        assertThrows(IllegalArgumentException.class, () -> new ReferenceTableKeeper(jdbc, List.of("users; DROP TABLE users")));
    }

    @Test
    void theBaseClassDeclaresTheReferenceTablesOfThePlan() {
        // system_settings ships no rows in a migration, so it must be truncated like every other table:
        // keeping it would leak a setting changed by one release-gate test into the next.
        assertEquals(List.of("legal_documents", "billing_fee_schedule"), REFERENCE_TABLES);
    }
}
