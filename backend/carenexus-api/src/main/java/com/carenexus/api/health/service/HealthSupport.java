package com.carenexus.api.health.service;

import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.health.repository.HealthPersonRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Rules shared by the metric and symptom services: who the patient of a write is, the write access check, the
 * owner-only denial, batch name lookup, date filters and number formatting.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class HealthSupport {

    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final HealthPersonRepository healthPersonRepository;

    /** The caller, as resolved from the JWT. */
    record Caller(String systemId, String role) {

        boolean isPatient() {
            return AuthorizationService.PATIENT.equals(role);
        }

        boolean isDoctor() {
            return AuthorizationService.DOCTOR.equals(role);
        }

        /** Doctors and caregivers write on behalf of a patient and need current write access to do so. */
        boolean actsForPatient() {
            return AuthorizationService.DOCTOR.equals(role) || AuthorizationService.CAREGIVER.equals(role);
        }
    }

    Caller caller() {
        return new Caller(authorizationService.currentSystemId(), authorizationService.currentRole());
    }

    /**
     * The patient a POST writes for, after the role check (ADMIN 403), the "patientSystemId is required" rule for
     * doctors and caregivers, and the MANAGE_HEALTH_METRICS access check (403 for anyone without write access).
     */
    String requireWritablePatient(Caller caller, String requestedPatientSystemId) {
        authorizationService.requireRole(AuthorizationService.PATIENT, AuthorizationService.DOCTOR,
                AuthorizationService.CAREGIVER);
        String patient = requestedPatientSystemId == null ? null : requestedPatientSystemId.trim();
        if (patient == null || patient.isEmpty()) {
            if (!caller.isPatient()) {
                throw new BadRequestException("VALIDATION_FAILED", "patientSystemId is required");
            }
            patient = caller.systemId();
        }
        authorizationService.requirePatientAccess(patient, PatientPermission.MANAGE_HEALTH_METRICS);
        return patient;
    }

    /** Read access of 1.1 (patient self, related doctor, caregiver with can_view_records or FULL_ACCESS, admin). */
    void requireReadAccess(String patientSystemId, Integer resourceId) {
        authorizationService.requirePatientAccess(patientSystemId, PatientPermission.HEALTH_METRICS, resourceId);
    }

    /** Write access of 1.1, used when a doctor or caregiver removes what they recorded. */
    void requireWriteAccess(String patientSystemId, Integer resourceId) {
        authorizationService.requirePatientAccess(patientSystemId, PatientPermission.MANAGE_HEALTH_METRICS, resourceId);
    }

    /** 403 for a caller who is not the owner of the row (audited as ACCESS_DENIED). */
    AuthorizationException denyNotOwner(Caller caller, String resourceType, Integer resourceId, String patientSystemId,
                                        String message) {
        try {
            auditService.logAccessDenied(caller.systemId(), resourceType, resourceId, patientSystemId, null,
                    "Not the owner of the " + resourceType);
        } catch (RuntimeException auditFailure) {
            log.error("Failed to write ACCESS_DENIED audit row: {}", auditFailure.getClass().getName());
        }
        return new AuthorizationException(message, resourceType, resourceId);
    }

    /** "First Last" per system id, in one query; ids without an account are absent. */
    Map<String, String> names(Collection<String> systemIds) {
        Set<String> ids = systemIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = new HashMap<>();
        for (HealthPersonRepository.PersonName person : healthPersonRepository.findNames(ids)) {
            String full = (nullToEmpty(person.getFirstName()) + " " + nullToEmpty(person.getLastName())).trim();
            names.put(person.getSystemId(), full.isEmpty() ? null : full);
        }
        return names;
    }

    /** Inclusive clinic-day filter bounds as [start of from, start of the day after to). */
    static LocalDateTime[] dayRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BadRequestException("VALIDATION_FAILED", "from must not be after to");
        }
        return new LocalDateTime[]{
                from == null ? null : from.atStartOfDay(),
                to == null ? null : to.plusDays(1).atStartOfDay()};
    }

    /** A decimal without trailing zeros and never in exponent form (148.000 -> 148, 71.50 -> 71.5). */
    static BigDecimal plain(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
