package com.carenexus.api.auth.service;

import com.carenexus.api.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Allocates system ids ({@code PAT-00001}, {@code DOC-00001}, {@code CGV-00001}, {@code ADM-00001}).
 *
 * The numeric part comes from one database sequence per role prefix (V23), so concurrent registrations can never
 * be given the same id. Sequence values are not rolled back with the transaction: a failed registration leaves a
 * gap, which is fine. A number whose id already exists (inserted explicitly by a seed script or a fixture) is
 * skipped.
 */
@Service
@RequiredArgsConstructor
public class SystemIdGenerator {

    /** Upper bound on consecutive already-used numbers skipped before giving up. */
    static final int MAX_SKIPPED_IDS = 10_000;

    private final UserRepository userRepository;

    /** Same as {@link #generateSystemId(String)}. */
    public String generate(String role) {
        return generateSystemId(role);
    }

    public String generateSystemId(String role) {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("Role cannot be null or empty");
        }
        String prefix = getRolePrefix(role.trim().toUpperCase(Locale.ROOT));
        String sequenceName = "system_id_" + prefix.toLowerCase(Locale.ROOT) + "_seq";

        for (int attempt = 0; attempt < MAX_SKIPPED_IDS; attempt++) {
            long next = userRepository.nextSystemIdSequenceValue(sequenceName);
            String systemId = prefix + "-" + String.format("%05d", next);
            if (!userRepository.existsBySystemId(systemId)) {
                return systemId;
            }
        }
        throw new IllegalStateException("Could not allocate a free " + prefix + " system id");
    }

    private String getRolePrefix(String role) {
        return switch (role) {
            case "PATIENT" -> "PAT";
            case "DOCTOR" -> "DOC";
            case "CAREGIVER" -> "CGV";
            case "ADMIN" -> "ADM";
            default -> throw new IllegalArgumentException("Invalid role: " + role);
        };
    }
}
