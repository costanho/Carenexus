package com.carenexus.api.auth.service;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SystemIdGenerator {

    private final UserRepository userRepository;

    public String generateSystemId(String role) {
        if (role == null || role.isEmpty()) {
            throw new IllegalArgumentException("Role cannot be null or empty");
        }

        String prefix = getRolePrefix(role.toUpperCase());
        long nextSequence = getNextSequenceForRole(prefix);
        return prefix + "-" + String.format("%05d", nextSequence);
    }

    private String getRolePrefix(String role) {
        return switch (role.toUpperCase()) {
            case "PATIENT" -> "PAT";
            case "DOCTOR" -> "DOC";
            case "CAREGIVER" -> "CGV";
            case "ADMIN" -> "ADM";
            default -> throw new IllegalArgumentException("Invalid role: " + role);
        };
    }

    private long getNextSequenceForRole(String prefix) {
        Long maxSequence = userRepository.findMaxSequenceByPrefix(prefix);
        return (maxSequence != null ? maxSequence : 0) + 1;
    }
}
