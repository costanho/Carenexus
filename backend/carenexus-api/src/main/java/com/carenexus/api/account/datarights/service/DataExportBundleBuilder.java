package com.carenexus.api.account.datarights.service;

import com.carenexus.api.auth.model.User;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Assembles the JSON bundle of a user's own data (API contract section 3.1) with read-only native queries.
 *
 * <ul>
 *   <li>Every query is a fixed SELECT on a table named in this class, filtered to the caller; the only parameter is
 *       the caller's system id. Nothing from a request ends up in the SQL text.</li>
 *   <li>Rows are objects keyed by the database column names, values as stored (timestamps as ISO strings, JSON
 *       columns embedded as JSON). Secret columns (password and token hashes, secrets, recovery codes) and internal
 *       storage references are never included.</li>
 *   <li>Clinical sections contain only rows in which the caller is the patient: a clinician's, caregiver's or
 *       administrator's export never contains patients' records. They hold only the rows the API shows the patient:
 *       no unfinished consultations, never-issued invoices, reminders addressed to a doctor or soft-deleted rows.</li>
 *   <li>Tables added by other modules appear as extra sections only when they exist ({@code to_regclass}), and are
 *       filtered on the owner column they actually have ({@code system_id}, {@code patient_system_id}, or the
 *       legacy {@code patient_id} through the caller's {@code patients} row).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class DataExportBundleBuilder {

    public static final int EXPORT_VERSION = 1;

    /** Columns never exported: secrets and internal storage references. */
    private static final Pattern SECRET_COLUMN = Pattern.compile(
            "^(password_hash|result_ref|storage_key|file_key|file_url)$|_hash$|secret|recovery_code|(^|_)token(_|$)|_token$");

    private static final String PATIENT = "PATIENT";

    /** How an optional table is tied to its owner. */
    private enum Owner {
        /** {@code system_id = caller} (any role). */
        ACCOUNT,
        /** {@code patient_system_id = caller}, or legacy {@code patient_id} of the caller's patients row (patients only). */
        PATIENT
    }

    private record OptionalSection(String key, String table, Owner owner) {
    }

    /** Sections added when their table exists, in output order. */
    private static final List<OptionalSection> OPTIONAL_SECTIONS = List.of(
            new OptionalSection("legalAcceptances", "legal_acceptances", Owner.ACCOUNT),
            new OptionalSection("documents", "documents", Owner.PATIENT),
            new OptionalSection("referrals", "referrals", Owner.PATIENT),
            new OptionalSection("carePlans", "care_plans", Owner.PATIENT),
            new OptionalSection("healthMetrics", "health_metrics", Owner.PATIENT),
            new OptionalSection("symptomLogs", "symptom_logs", Owner.PATIENT),
            new OptionalSection("medicationSchedules", "medication_schedules", Owner.PATIENT),
            new OptionalSection("reminders", "reminders", Owner.PATIENT),
            new OptionalSection("invoices", "invoices", Owner.PATIENT),
            new OptionalSection("payments", "payments", Owner.PATIENT),
            new OptionalSection("consentRecords", "consent_records", Owner.PATIENT));

    /** An extra condition on a patient-owned table, valid only when the table has {@code columns}. */
    private record Visibility(String condition, Set<String> columns) {
    }

    /**
     * What the API never shows a patient, so the export leaves it out too: unfinished (DRAFT, IN_PROGRESS)
     * consultations (ConsultationService FINAL_STATUSES), never-issued invoices (InvoiceService.visibleToPatient) and
     * reminders addressed to someone else, such as a doctor's review reminder (ReminderRepository). Soft-deleted rows
     * ({@code is_deleted}) are left out of every patient-owned table, see {@link #patientVisibility}.
     */
    private static final Map<String, Visibility> PATIENT_VISIBILITY = Map.of(
            "consultations", new Visibility("status IN ('COMPLETED', 'CANCELLED')", Set.of("status")),
            "invoices", new Visibility("status <> 'DRAFT' AND (status <> 'CANCELLED' OR issued_at IS NOT NULL)",
                    Set.of("status", "issued_at")),
            "reminders", new Visibility("recipient_system_id = patient_system_id",
                    Set.of("recipient_system_id", "patient_system_id")));

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /**
     * Builds the bundle of {@code user}'s own data.
     *
     * @param generatedAt the moment the export was requested
     * @return UTF-8 JSON
     */
    public byte[] build(User user, Instant generatedAt) {
        String systemId = user.getSystemId();
        boolean patient = PATIENT.equals(user.getRole());

        Map<String, Object> sections = new LinkedHashMap<>();
        sections.put("user", one("SELECT * FROM users WHERE system_id = ?", systemId));
        sections.put("profile", profile(user));
        sections.put("settings", one("SELECT * FROM user_settings WHERE system_id = ?", systemId));
        sections.put("emergencyContacts", patient
                ? many("SELECT * FROM emergency_contacts WHERE patient_system_id = ? ORDER BY 1", systemId)
                : List.of());
        sections.put("appointments", patientRows(patient, "appointments", systemId));
        sections.put("consultations", patientRows(patient, "consultations", systemId));
        sections.put("prescriptions", patientRows(patient, "prescriptions", systemId));
        sections.put("medicalRecords", patientRows(patient, "medical_records", systemId));
        sections.put("labResults", patientRows(patient, "lab_results", systemId));
        sections.put("imagingResults", patientRows(patient, "imaging_results", systemId));
        sections.put("caregiverGrants", many("SELECT * FROM dependent_access "
                + "WHERE patient_system_id = ? OR caregiver_system_id = ? ORDER BY 1", systemId, systemId));
        sections.put("messagesSent", many("SELECT * FROM messages WHERE sender_system_id = ? ORDER BY 1", systemId));
        sections.put("notifications", many("SELECT * FROM notifications WHERE system_id = ? ORDER BY 1", systemId));
        sections.put("auditLog", many("SELECT * FROM audit_log WHERE system_id = ? ORDER BY 1", systemId));
        for (OptionalSection section : OPTIONAL_SECTIONS) {
            if (tableExists(section.table())) {
                sections.put(section.key(), optionalRows(section, patient, systemId));
            }
        }

        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("exportVersion", EXPORT_VERSION);
        bundle.put("generatedAt", generatedAt.toString());
        bundle.put("systemId", systemId);
        bundle.put("role", user.getRole());
        bundle.put("sections", sections);
        try {
            return objectMapper.writeValueAsBytes(bundle);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Data export could not be serialised", e);
        }
    }

    // ==================== SECTIONS ====================

    private Map<String, Object> profile(User user) {
        String table = switch (user.getRole() == null ? "" : user.getRole()) {
            case "PATIENT" -> "patients";
            case "DOCTOR" -> "doctors";
            case "CAREGIVER" -> "caregivers";
            default -> null;
        };
        return table == null ? null : one("SELECT * FROM " + table + " WHERE system_id = ?", user.getSystemId());
    }

    /** Rows of a clinical table in which the caller is the patient; empty for every other role. */
    private List<Map<String, Object>> patientRows(boolean patient, String table, String systemId) {
        if (!patient) {
            return List.of();
        }
        String visible = patientVisibility(table, columnsOf(table));
        if (visible == null) {
            return List.of();
        }
        return many("SELECT * FROM " + table + " WHERE patient_system_id = ?" + visible + " ORDER BY 1", systemId);
    }

    /**
     * The conditions (each prefixed with {@code " AND "}) that keep a patient's export to the rows the API shows that
     * patient: the table's {@link #PATIENT_VISIBILITY} rule and, when the table has {@code is_deleted}, live rows only.
     * Empty when nothing is hidden; null when the rule needs a column the table lacks, so the section is exported empty
     * rather than unfiltered.
     */
    static String patientVisibility(String table, Set<String> columns) {
        StringBuilder sql = new StringBuilder();
        Visibility rule = PATIENT_VISIBILITY.get(table);
        if (rule != null) {
            if (!columns.containsAll(rule.columns())) {
                return null;
            }
            sql.append(" AND (").append(rule.condition()).append(')');
        }
        if (columns.contains("is_deleted")) {
            sql.append(" AND is_deleted = FALSE");
        }
        return sql.toString();
    }

    private List<Map<String, Object>> optionalRows(OptionalSection section, boolean patient, String systemId) {
        Set<String> columns = columnsOf(section.table());
        if (section.owner() == Owner.ACCOUNT) {
            if (columns.contains("system_id")) {
                return many("SELECT * FROM " + section.table() + " WHERE system_id = ? ORDER BY 1", systemId);
            }
            return List.of();
        }
        if (!patient) {
            return List.of();
        }
        String visible = patientVisibility(section.table(), columns);
        if (visible == null) {
            return List.of();
        }
        if (columns.contains("patient_system_id")) {
            return many("SELECT * FROM " + section.table() + " WHERE patient_system_id = ?" + visible + " ORDER BY 1",
                    systemId);
        }
        if (columns.contains("patient_id")) {
            return many("SELECT * FROM " + section.table()
                    + " WHERE patient_id IN (SELECT p.patient_id FROM patients p WHERE p.system_id = ?)" + visible
                    + " ORDER BY 1", systemId);
        }
        // No owner column this module knows: export nothing rather than someone else's rows.
        return List.of();
    }

    // ==================== QUERIES ====================

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = many(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<Map<String, Object>> many(String sql, Object... args) {
        return jdbc.query(sql, rowMapper(), args);
    }

    private boolean tableExists(String table) {
        Boolean exists = jdbc.queryForObject("SELECT to_regclass(CAST(? AS text)) IS NOT NULL", Boolean.class, table);
        return Boolean.TRUE.equals(exists);
    }

    private Set<String> columnsOf(String table) {
        return new HashSet<>(jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = current_schema() AND table_name = ?", String.class, table));
    }

    private RowMapper<Map<String, Object>> rowMapper() {
        return (rs, rowNum) -> {
            ResultSetMetaData meta = rs.getMetaData();
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                String column = meta.getColumnLabel(i);
                if (isSecret(column)) {
                    continue;
                }
                row.put(column, value(rs, i, meta.getColumnTypeName(i)));
            }
            return row;
        };
    }

    static boolean isSecret(String column) {
        return column != null && SECRET_COLUMN.matcher(column.toLowerCase(Locale.ROOT)).find();
    }

    private Object value(ResultSet rs, int index, String typeName) throws SQLException {
        Object raw = rs.getObject(index);
        if (raw == null) {
            return null;
        }
        String type = typeName == null ? "" : typeName.toLowerCase(Locale.ROOT);
        switch (type) {
            case "timestamp":
                return ((Timestamp) raw).toLocalDateTime().toString();
            case "timestamptz":
                return rs.getObject(index, OffsetDateTime.class).toInstant().toString();
            case "date":
                return rs.getObject(index, LocalDate.class).toString();
            case "time":
                return rs.getObject(index, LocalTime.class).toString();
            case "json":
            case "jsonb":
                return json(rs.getString(index));
            default:
                break;
        }
        if (raw instanceof Array array) {
            Object elements = array.getArray();
            List<Object> list = new ArrayList<>();
            if (elements instanceof Object[] items) {
                for (Object item : items) {
                    list.add(item == null || item instanceof Number || item instanceof Boolean ? item : item.toString());
                }
            }
            return list;
        }
        if (raw instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        if (raw instanceof Number || raw instanceof Boolean || raw instanceof String) {
            return raw;
        }
        return raw.toString();
    }

    private Object json(String text) {
        if (text == null) {
            return null;
        }
        try {
            return objectMapper.readTree(text);
        } catch (JsonProcessingException e) {
            return text;
        }
    }
}
