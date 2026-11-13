package com.carenexus.api.billing.payment.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Batch reads of tables the payment module does not own, for the response mapping: user names (users) and saved
 * method labels (payment_methods, also soft-deleted rows). Read-only native SQL that joins the caller's transaction.
 */
@Repository("paymentModuleLookupQueries")
@RequiredArgsConstructor
public class PaymentLookupQueries {

    private final NamedParameterJdbcTemplate jdbc;

    /** "First Last" by system id; ids without a user row are absent. */
    public Map<String, String> namesOf(Collection<String> systemIds) {
        Map<String, String> names = new HashMap<>();
        if (systemIds == null || systemIds.isEmpty()) {
            return names;
        }
        jdbc.query("SELECT system_id, first_name, last_name FROM users WHERE system_id IN (:ids)",
                new MapSqlParameterSource("ids", systemIds),
                rs -> {
                    String name = fullName(rs.getString("first_name"), rs.getString("last_name"));
                    if (name != null) {
                        names.put(rs.getString("system_id"), name);
                    }
                });
        return names;
    }

    /** Masked display label by method id, whatever the method's state; ids without a row are absent. */
    public Map<Integer, String> methodLabelsOf(Collection<Integer> methodIds) {
        Map<Integer, String> labels = new HashMap<>();
        if (methodIds == null || methodIds.isEmpty()) {
            return labels;
        }
        jdbc.query("SELECT method_id, display_label FROM payment_methods WHERE method_id IN (:ids)",
                new MapSqlParameterSource("ids", methodIds),
                rs -> {
                    String label = rs.getString("display_label");
                    if (label != null && !label.isBlank()) {
                        labels.put(rs.getInt("method_id"), label);
                    }
                });
        return labels;
    }

    private static String fullName(String first, String last) {
        String name = ((first == null ? "" : first.trim()) + " " + (last == null ? "" : last.trim())).trim();
        return name.isEmpty() ? null : name;
    }
}
