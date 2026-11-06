package com.carenexus.api.core.service;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.auth.service.AuthService;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.core.dto.request.CreateDependentRequest;
import com.carenexus.api.core.dto.request.UpdateDependentAccessRequest;
import com.carenexus.api.core.dto.response.AppointmentResponse;
import com.carenexus.api.core.dto.response.CaregiverDependentActivityResponse;
import com.carenexus.api.core.dto.response.CaregiverDependentResponse;
import com.carenexus.api.core.dto.response.ConsultationResponse;
import com.carenexus.api.core.model.DependentAccess;
import com.carenexus.api.core.model.Patient;
import com.carenexus.api.core.repository.CaregiverRepository;
import com.carenexus.api.core.repository.DependentAccessRepository;
import com.carenexus.api.core.repository.PatientRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Caregiver (proxy) access to dependents.
 *
 * Who may change a dependent_access grant:
 * - the PATIENT it belongs to (or an ADMIN): any change, via the /api/patients/me/caregivers endpoints;
 * - the CAREGIVER holding it: may only NARROW an active grant (turn permissions off, lower the access level,
 *   bring the expiry earlier). Any widening is rejected with 403.
 *
 * The class default is read-only, so every write method carries its own @Transactional.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CaregiverService {

    private static final Set<String> ACCESS_LEVELS = Set.of("FULL_ACCESS", "VIEW_ONLY", "EDIT_ONLY", "CUSTOM");
    private static final Set<String> GENDERS = Set.of("MALE", "FEMALE", "OTHER");
    private static final Set<String> BLOOD_TYPES = Set.of("A+", "A-", "B+", "B-", "O+", "O-", "AB+", "AB-");
    private static final Set<String> HEALTH_STATUSES = Set.of("STABLE", "MONITOR", "CRITICAL");

    private final DependentAccessRepository dependentAccessRepository;
    private final UserRepository userRepository;
    private final PatientRepository patientRepository;
    private final CaregiverRepository caregiverRepository;
    private final AppointmentService appointmentService;
    private final ConsultationService consultationService;
    private final AuthService authService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    // ==================== CAREGIVER VIEW ====================

    public List<CaregiverDependentResponse> getDependentsForCaregiver(String caregiverSystemId) {
        return dependentAccessRepository.findAllDependentsForCaregiver(caregiverSystemId)
                .stream()
                .map(access -> toEnrichedResponse(access, true))
                .collect(Collectors.toList());
    }

    public CaregiverDependentActivityResponse getActivityForDependents(String caregiverSystemId) {
        List<DependentAccess> accesses = dependentAccessRepository.findAllDependentsForCaregiver(caregiverSystemId);

        Map<String, String> nameMap = accesses.stream()
                .map(DependentAccess::getPatientSystemId)
                .distinct()
                .collect(Collectors.toMap(
                        id -> id,
                        id -> userRepository.findBySystemId(id)
                                .map(u -> u.getFirstName() + " " + u.getLastName())
                                .orElse(id)
                ));

        List<String> appointmentPatientIds = accesses.stream()
                .filter(PatientPermission.APPOINTMENTS::isGrantedBy)
                .map(DependentAccess::getPatientSystemId)
                .collect(Collectors.toList());

        List<String> consultationPatientIds = accesses.stream()
                .filter(PatientPermission.CONSULTATIONS::isGrantedBy)
                .map(DependentAccess::getPatientSystemId)
                .collect(Collectors.toList());

        List<AppointmentResponse> appointments = appointmentService.getAppointmentsForPatients(appointmentPatientIds);
        appointments.forEach(a -> a.setPatientName(nameMap.get(a.getPatientSystemId())));

        List<ConsultationResponse> consultations = consultationService.getConsultationsForPatients(consultationPatientIds);
        consultations.forEach(c -> c.setPatientName(nameMap.get(c.getPatientSystemId())));

        return CaregiverDependentActivityResponse.builder()
                .appointments(appointments)
                .consultations(consultations)
                .build();
    }

    /**
     * Caregiver-side edit of their own grant: only narrowing is allowed, and only while the grant is active.
     */
    @Transactional
    public CaregiverDependentResponse updateDependentAccess(String caregiverSystemId, Integer accessId,
                                                            UpdateDependentAccessRequest request) {
        DependentAccess access = dependentAccessRepository.findById(accessId)
                .orElseThrow(() -> new ResourceNotFoundException("Access record not found"));

        if (!access.getCaregiverSystemId().equals(caregiverSystemId)) {
            throw new AuthorizationException("Not authorized to update this access record", "DEPENDENT_ACCESS", accessId);
        }
        if (!isCurrentlyActive(access)) {
            throw new AuthorizationException("This access has expired or been revoked. Ask the patient to grant access again.",
                    "DEPENDENT_ACCESS", accessId);
        }

        DependentAccess before = copyOf(access);
        // A date-only value for the current expiry day means "unchanged" (it would otherwise parse to 23:59:59)
        if (request != null && request.getExpiresAt() != null && before.getExpiresAt() != null
                && request.getExpiresAt().trim().equals(before.getExpiresAt().toLocalDate().toString())) {
            request.setExpiresAt(null);
        }
        applyChanges(access, request, false);

        for (PatientPermission permission : PatientPermission.values()) {
            if (permission.isGrantedBy(access) && !permission.isGrantedBy(before)) {
                throw new AuthorizationException(
                        "You can only reduce your own access. Ask the patient to grant additional permissions.",
                        "DEPENDENT_ACCESS", accessId);
            }
        }
        if (before.getExpiresAt() != null
                && (access.getExpiresAt() == null || access.getExpiresAt().isAfter(before.getExpiresAt()))) {
            throw new AuthorizationException("You cannot extend your own access. Ask the patient to extend it.",
                    "DEPENDENT_ACCESS", accessId);
        }

        DependentAccess saved = dependentAccessRepository.save(access);
        auditService.logClinicalAccess("UPDATE", "DEPENDENT_ACCESS", accessId, saved.getPatientSystemId());
        return toEnrichedResponse(saved, true);
    }

    /**
     * Atomically create a dependent: users row (PATIENT, active) + patients row + dependent_access grant for the
     * calling caregiver. Any failure rolls all three back. Duplicate email/phone gives 409.
     */
    @Transactional
    public CaregiverDependentResponse createDependent(String caregiverSystemId, CreateDependentRequest request) {
        if (request == null) {
            throw new RuntimeException("Dependent details are required");
        }
        if (caregiverRepository.findBySystemId(caregiverSystemId).isEmpty()) {
            throw new AuthorizationException("Only proxy/caregiver accounts can add a dependent", "DEPENDENT_ACCESS", null);
        }

        // Validate everything before creating anything
        LocalDate dateOfBirth = parseDateOfBirth(request.getDateOfBirth());
        String gender = optionalEnum(request.getGender(), GENDERS, "Gender must be MALE, FEMALE or OTHER");
        String bloodType = optionalEnum(request.getBloodType(), BLOOD_TYPES, "Invalid blood type");
        String healthStatus = optionalEnum(request.getHealthStatus(), HEALTH_STATUSES,
                "Invalid health status. Must be STABLE, MONITOR, or CRITICAL");
        String relationship = request.getRelationship() == null ? "" : request.getRelationship().trim();
        if (relationship.isEmpty()) {
            throw new RuntimeException("Relationship is required");
        }
        if (relationship.length() > 80) {
            throw new RuntimeException("Relationship must be at most 80 characters");
        }
        String accessLevel = request.getAccessLevel() == null || request.getAccessLevel().isBlank()
                ? "VIEW_ONLY" : request.getAccessLevel().trim().toUpperCase(Locale.ROOT);
        if (!ACCESS_LEVELS.contains(accessLevel)) {
            throw new RuntimeException("Invalid access level. Must be FULL_ACCESS, VIEW_ONLY, EDIT_ONLY or CUSTOM");
        }
        LocalDateTime expiresAt = parseExpiry(request.getExpiresAt());
        if (expiresAt != null && !expiresAt.isAfter(LocalDateTime.now())) {
            throw new RuntimeException("Access expiry must be in the future");
        }
        String allergies = toJsonArray(request.getAllergies());
        String chronicConditions = toJsonArray(request.getChronicConditions());

        User user = authService.createActiveUserAccount(request.getFirstName(), request.getLastName(),
                request.getEmail(), request.getPassword(), request.getPhone(), "PATIENT");

        patientRepository.save(Patient.builder()
                .systemId(user.getSystemId())
                .dateOfBirth(dateOfBirth)
                .gender(gender)
                .bloodType(bloodType)
                .healthStatus(healthStatus == null ? "STABLE" : healthStatus)
                .allergies(allergies)
                .chronicConditions(chronicConditions)
                .build());

        boolean full = "FULL_ACCESS".equals(accessLevel);
        DependentAccess access = DependentAccess.builder()
                .patientSystemId(user.getSystemId())
                .caregiverSystemId(caregiverSystemId)
                // The caregiver creates the dependent's account and is the one authorizing this grant
                .authorizedBySystemId(caregiverSystemId)
                .relationship(relationship)
                .accessLevel(accessLevel)
                .canViewRecords(full || Boolean.TRUE.equals(request.getCanViewRecords()))
                .canViewAppointments(full || Boolean.TRUE.equals(request.getCanViewAppointments()))
                .canViewMedications(full || Boolean.TRUE.equals(request.getCanViewMedications()))
                .canViewLabResults(full || Boolean.TRUE.equals(request.getCanViewLabResults()))
                .canViewImaging(full || Boolean.TRUE.equals(request.getCanViewImaging()))
                .canMessageCareTeam(full || Boolean.TRUE.equals(request.getCanMessageCareTeam()))
                .canViewConsultations(full || Boolean.TRUE.equals(request.getCanViewConsultations()))
                .canScheduleAppointments(full || Boolean.TRUE.equals(request.getCanScheduleAppointments()))
                .canManageCarePlan(full || Boolean.TRUE.equals(request.getCanManageCarePlan()))
                .expiresAt(expiresAt)
                .isActive(true)
                .build();
        DependentAccess saved = dependentAccessRepository.save(access);

        auditService.logClinicalAccess("CREATE", "DEPENDENT_ACCESS", saved.getAccessId(), saved.getPatientSystemId());
        return toEnrichedResponse(saved, true);
    }

    // ==================== PATIENT VIEW (grant management) ====================

    /**
     * Caregivers with an active grant to this patient.
     */
    public List<CaregiverDependentResponse> getCaregiversForPatient(String patientSystemId) {
        return dependentAccessRepository.findAllCaregiversForDependent(patientSystemId)
                .stream()
                .map(access -> toEnrichedResponse(access, false))
                .collect(Collectors.toList());
    }

    /**
     * Patient (or admin) changes a caregiver's grant: permissions, access level and expiry (empty string removes
     * the expiry). Revoked grants cannot be edited.
     */
    @Transactional
    public CaregiverDependentResponse updateCaregiverAccessAsPatient(String callerSystemId, boolean callerIsAdmin,
                                                                     Integer accessId, UpdateDependentAccessRequest request) {
        DependentAccess access = loadForPatient(callerSystemId, callerIsAdmin, accessId);
        if (!Boolean.TRUE.equals(access.getIsActive()) || access.getRevokedAt() != null) {
            throw new ConflictException("This access has been revoked");
        }
        applyChanges(access, request, true);
        DependentAccess saved = dependentAccessRepository.save(access);
        auditService.logClinicalAccess("UPDATE", "DEPENDENT_ACCESS", accessId, saved.getPatientSystemId());
        return toEnrichedResponse(saved, false);
    }

    /**
     * Patient (or admin) revokes a caregiver's grant. Idempotent.
     */
    @Transactional
    public void revokeCaregiverAccessAsPatient(String callerSystemId, boolean callerIsAdmin, Integer accessId) {
        DependentAccess access = loadForPatient(callerSystemId, callerIsAdmin, accessId);
        if (access.getRevokedAt() == null || Boolean.TRUE.equals(access.getIsActive())) {
            access.setIsActive(false);
            access.setRevokedAt(LocalDateTime.now());
            dependentAccessRepository.save(access);
            auditService.logClinicalAccess("DELETE", "DEPENDENT_ACCESS", accessId, access.getPatientSystemId());
        }
    }

    // ==================== HELPERS ====================

    private DependentAccess loadForPatient(String callerSystemId, boolean callerIsAdmin, Integer accessId) {
        DependentAccess access = dependentAccessRepository.findById(accessId)
                .orElseThrow(() -> new ResourceNotFoundException("Access record not found"));
        if (!callerIsAdmin && !access.getPatientSystemId().equals(callerSystemId)) {
            throw new AuthorizationException("Not authorized to change this access record", "DEPENDENT_ACCESS", accessId);
        }
        return access;
    }

    private static boolean isCurrentlyActive(DependentAccess access) {
        return Boolean.TRUE.equals(access.getIsActive())
                && access.getRevokedAt() == null
                && (access.getExpiresAt() == null || access.getExpiresAt().isAfter(LocalDateTime.now()));
    }

    private void applyChanges(DependentAccess access, UpdateDependentAccessRequest request, boolean allowClearExpiry) {
        if (request == null) {
            return;
        }
        if (request.getAccessLevel() != null && !request.getAccessLevel().isBlank()) {
            String level = request.getAccessLevel().trim().toUpperCase(Locale.ROOT);
            if (!ACCESS_LEVELS.contains(level)) {
                throw new RuntimeException("Invalid access level. Must be FULL_ACCESS, VIEW_ONLY, EDIT_ONLY or CUSTOM");
            }
            access.setAccessLevel(level);
        }
        if (request.getExpiresAt() != null) {
            if (request.getExpiresAt().isBlank()) {
                if (!allowClearExpiry) {
                    throw new AuthorizationException("You cannot extend your own access. Ask the patient to extend it.",
                            "DEPENDENT_ACCESS", access.getAccessId());
                }
                access.setExpiresAt(null);
            } else {
                access.setExpiresAt(parseExpiry(request.getExpiresAt()));
            }
        }
        if (request.getCanViewRecords() != null)          access.setCanViewRecords(request.getCanViewRecords());
        if (request.getCanViewAppointments() != null)     access.setCanViewAppointments(request.getCanViewAppointments());
        if (request.getCanViewMedications() != null)      access.setCanViewMedications(request.getCanViewMedications());
        if (request.getCanViewLabResults() != null)       access.setCanViewLabResults(request.getCanViewLabResults());
        if (request.getCanViewImaging() != null)          access.setCanViewImaging(request.getCanViewImaging());
        if (request.getCanMessageCareTeam() != null)      access.setCanMessageCareTeam(request.getCanMessageCareTeam());
        if (request.getCanViewConsultations() != null)    access.setCanViewConsultations(request.getCanViewConsultations());
        if (request.getCanScheduleAppointments() != null) access.setCanScheduleAppointments(request.getCanScheduleAppointments());
        if (request.getCanManageCarePlan() != null)       access.setCanManageCarePlan(request.getCanManageCarePlan());
    }

    private static DependentAccess copyOf(DependentAccess a) {
        return DependentAccess.builder()
                .accessId(a.getAccessId())
                .patientSystemId(a.getPatientSystemId())
                .caregiverSystemId(a.getCaregiverSystemId())
                .accessLevel(a.getAccessLevel())
                .canViewRecords(a.getCanViewRecords())
                .canViewAppointments(a.getCanViewAppointments())
                .canViewMedications(a.getCanViewMedications())
                .canViewLabResults(a.getCanViewLabResults())
                .canViewImaging(a.getCanViewImaging())
                .canMessageCareTeam(a.getCanMessageCareTeam())
                .canViewConsultations(a.getCanViewConsultations())
                .canScheduleAppointments(a.getCanScheduleAppointments())
                .canManageCarePlan(a.getCanManageCarePlan())
                .expiresAt(a.getExpiresAt())
                .revokedAt(a.getRevokedAt())
                .isActive(a.getIsActive())
                .build();
    }

    /**
     * "YYYY-MM-DD" (end of that day), ISO local date-time, or ISO date-time with offset. Blank -> null.
     */
    static LocalDateTime parseExpiry(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
            // try the other formats
        }
        try {
            return OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // try a plain date
        }
        try {
            return LocalDate.parse(value).atTime(23, 59, 59);
        } catch (DateTimeParseException e) {
            throw new RuntimeException("Access expiry must be a date in the format YYYY-MM-DD");
        }
    }

    private static LocalDate parseDateOfBirth(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        LocalDate date;
        try {
            date = LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new RuntimeException("Date of birth must be in the format YYYY-MM-DD");
        }
        if (date.isAfter(LocalDate.now())) {
            throw new RuntimeException("Date of birth cannot be in the future");
        }
        if (date.isBefore(LocalDate.now().minusYears(130))) {
            throw new RuntimeException("Please enter a valid date of birth");
        }
        return date;
    }

    private static String optionalEnum(String raw, Set<String> allowed, String message) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(value)) {
            throw new RuntimeException(message);
        }
        return value;
    }

    private String toJsonArray(List<String> values) {
        if (values == null) {
            return null;
        }
        List<String> cleaned = values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .distinct()
                .collect(Collectors.toList());
        try {
            return objectMapper.writeValueAsString(cleaned);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize list", e);
        }
    }

    /**
     * @param caregiverView true when the caller is the caregiver: health fields (date of birth, blood type,
     *                      health status) are only included when the grant allows viewing records.
     */
    private CaregiverDependentResponse toEnrichedResponse(DependentAccess access, boolean caregiverView) {
        CaregiverDependentResponse.CaregiverDependentResponseBuilder builder = CaregiverDependentResponse.builder()
                .accessId(access.getAccessId())
                .caregiverSystemId(access.getCaregiverSystemId())
                .patientSystemId(access.getPatientSystemId())
                .relationship(access.getRelationship())
                .accessLevel(access.getAccessLevel())
                .canViewRecords(access.getCanViewRecords())
                .canViewAppointments(access.getCanViewAppointments())
                .canViewMedications(access.getCanViewMedications())
                .canViewLabResults(access.getCanViewLabResults())
                .canViewImaging(access.getCanViewImaging())
                .canMessageCareTeam(access.getCanMessageCareTeam())
                .canViewConsultations(access.getCanViewConsultations())
                .canScheduleAppointments(access.getCanScheduleAppointments())
                .canManageCarePlan(access.getCanManageCarePlan())
                .authorizedBySystemId(access.getAuthorizedBySystemId())
                .authorizedDate(access.getAuthorizedDate())
                .expiresAt(access.getExpiresAt())
                .isActive(access.getIsActive())
                .revokedAt(access.getRevokedAt());

        userRepository.findBySystemId(access.getPatientSystemId()).ifPresent(user ->
                builder.patientName(user.getFirstName() + " " + user.getLastName())
                       .patientEmail(user.getEmail())
        );

        if (!caregiverView) {
            userRepository.findBySystemId(access.getCaregiverSystemId()).ifPresent(user ->
                    builder.caregiverName(user.getFirstName() + " " + user.getLastName())
                           .caregiverEmail(user.getEmail())
            );
        }

        boolean includeHealth = !caregiverView || PatientPermission.RECORDS.isGrantedBy(access);
        patientRepository.findBySystemId(access.getPatientSystemId()).ifPresent(patient -> {
            builder.patientGender(patient.getGender());
            if (includeHealth) {
                builder.patientHealthStatus(patient.getHealthStatus())
                       .patientDateOfBirth(patient.getDateOfBirth())
                       .patientBloodType(patient.getBloodType());
            }
        });

        return builder.build();
    }
}
