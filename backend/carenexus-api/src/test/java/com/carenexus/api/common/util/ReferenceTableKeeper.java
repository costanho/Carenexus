package com.carenexus.api.common.util;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Keeps reference rows that migrations ship (legal document versions, default fee rows) alive
 * across the table truncation of committed integration tests.
 *
 * {@code TRUNCATE ... CASCADE} empties every table that has a foreign key to a truncated table, so simply leaving
 * a reference table out of the TRUNCATE list does not protect it. Instead:
 * <ol>
 *   <li>{@link #snapshotIfAbsent()} copies each reference table into {@code zz_ref_snapshot_<table>} the first time
 *       it is called in a database, when the table holds exactly what the migrations inserted;</li>
 *   <li>after the truncation, {@link #restore()} empties each reference table and refills it from its snapshot,
 *       then moves the table's id sequences past the restored rows.</li>
 * </ol>
 * So every test starts with the migration-shipped rows, and rows a test adds to a reference table do not leak into
 * the next test. Tables that do not exist (their migration is not part of this build) are skipped.
 */
public class ReferenceTableKeeper {

    public static final String SNAPSHOT_PREFIX = "zz_ref_snapshot_";

    private final JdbcTemplate jdbc;
    private final List<String> tables;

    /**
     * @param tables reference table names (fixed identifiers from code, never user input)
     */
    public ReferenceTableKeeper(JdbcTemplate jdbc, Collection<String> tables) {
        this.jdbc = jdbc;
        this.tables = List.copyOf(tables);
        for (String table : this.tables) {
            if (!table.matches("[a-z_][a-z0-9_]*")) {
                throw new IllegalArgumentException("Not a plain table name: " + table);
            }
        }
    }

    /** True for the snapshot tables this class creates (callers leave them out of their TRUNCATE). */
    public static boolean isSnapshotTable(String tableName) {
        return tableName != null && tableName.startsWith(SNAPSHOT_PREFIX);
    }

    /** Takes the snapshot of every existing reference table that has none yet. */
    public void snapshotIfAbsent() {
        for (String table : tables) {
            if (exists(table) && !exists(SNAPSHOT_PREFIX + table)) {
                jdbc.execute("CREATE TABLE " + SNAPSHOT_PREFIX + table + " AS TABLE " + table);
            }
        }
    }

    /** Puts every snapshotted reference table back to its snapshot. Call after the truncation. */
    public void restore() {
        List<String> restorable = new ArrayList<>();
        for (String table : tables) {
            if (exists(table) && exists(SNAPSHOT_PREFIX + table)) {
                restorable.add(table);
            }
        }
        if (restorable.isEmpty()) {
            return;
        }
        // Empty them together so foreign keys between reference tables do not get in the way.
        jdbc.execute("TRUNCATE TABLE " + String.join(", ", restorable) + " RESTART IDENTITY CASCADE");
        // Refill in the given order, which callers choose parent-first (a child reference table after its parent).
        for (String table : restorable) {
            List<String> columns = jdbc.queryForList(
                    "SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema = 'public' AND table_name = ? AND is_generated = 'NEVER' "
                            + "ORDER BY ordinal_position", String.class, table);
            String columnList = String.join(", ", columns.stream().map(c -> '"' + c + '"').toList());
            jdbc.execute("INSERT INTO " + table + " (" + columnList + ") OVERRIDING SYSTEM VALUE SELECT " + columnList
                    + " FROM " + SNAPSHOT_PREFIX + table);
            for (String column : columns) {
                String sequence = jdbc.queryForObject("SELECT pg_get_serial_sequence(?, ?)", String.class, table, column);
                if (sequence != null) {
                    // Next generated id = max(id) + 1 (or 1 for an empty table).
                    jdbc.queryForObject("SELECT setval(CAST(? AS regclass), COALESCE(MAX(\"" + column + "\"), 1), "
                            + "MAX(\"" + column + "\") IS NOT NULL) FROM " + table, Long.class, sequence);
                }
            }
        }
    }

    private boolean exists(String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, "public." + table));
    }
}
