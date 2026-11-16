package com.carenexus.api.billing.method.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Batch lookups for the administrator's payment rows, with plain SQL so this module does not depend on entities
 * owned by other modules. Each call is one query whatever the number of ids, and joins the caller's transaction.
 */
@Repository("paymentMethodLookupQueries")
@RequiredArgsConstructor
public class PaymentLookupQueries {

    private final NamedParameterJdbcTemplate jdbc;

    /** "First Last" per system id; ids without a user row (or with an empty name) are absent from the map. */
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

    /** Masked display label per saved payment method id, deleted methods included. */
    public Map<Integer, String> methodLabelsOf(Collection<Integer> methodIds) {
        Map<Integer, String> labels = new HashMap<>();
        if (methodIds == null || methodIds.isEmpty()) {
            return labels;
        }
        jdbc.query("SELECT method_id, display_label FROM payment_methods WHERE method_id IN (:ids)",
                new MapSqlParameterSource("ids", methodIds),
                rs -> {
                    String label = rs.getString("display_label");
                    if (label != null) {
                        labels.put(rs.getInt("method_id"), label);
                    }
                });
        return labels;
    }

    /** Invoice number per invoice id. */
    public Map<Integer, String> invoiceNumbersOf(Collection<Integer> invoiceIds) {
        Map<Integer, String> numbers = new HashMap<>();
        if (invoiceIds == null || invoiceIds.isEmpty()) {
            return numbers;
        }
        jdbc.query("SELECT invoice_id, invoice_no FROM invoices WHERE invoice_id IN (:ids)",
                new MapSqlParameterSource("ids", invoiceIds),
                rs -> {
                    numbers.put(rs.getInt("invoice_id"), rs.getString("invoice_no"));
                });
        return numbers;
    }

    static String fullName(String first, String last) {
        String f = first == null ? "" : first.trim();
        String l = last == null ? "" : last.trim();
        String name = (f + " " + l).trim();
        return name.isEmpty() ? null : name;
    }
}
