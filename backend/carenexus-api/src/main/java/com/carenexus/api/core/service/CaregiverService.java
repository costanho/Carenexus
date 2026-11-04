package com.carenexus.api.core.service;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.repository.UserRepository;
import com.carenexus.api.auth.service.AuthService;
import com.carenexus.api.common.event.CaregiverAccessChangedEvent;
import com.carenexus.api.common.event.CaregiverApprovalPendingEvent;
import com.carenexus.api.common.event.CaregiverMessagingRegainedEvent;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.core.dto.request.CreateDependentRequest;
import com.carenexus.api.core.dto.request.GrantCaregiverAccessRequest;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Caregiver (proxy) access to dependents.
 *
 * Who may change a dependent_access grant:
 * - the PATIENT it belongs to (or an ADMIN): any change, via the /api/patients/me/caregiver-access and the
 *   legacy /api/patients/me/caregivers endpoints;
 * - the CAREGIVER holding it: may only NARROW an active grant (turn permissions off, lower the access level,
 *   bring the expiry earlier). Any widening is rejected with 403.
 *
 * Communication consent (messaging the care team, joining video calls): can_message_care_team and can_join_calls
 * are only REQUESTS. They take effect when the patient approves them (approval stamp *_approved_at +
 * *_approved_by_system_id = patient). Only the patient who owns the grant approves, by setting the flag true on a
 * grant or PATCH. Admin changes never approve. Turning a flag off (anyone) clears its approval in the same save.
 * FULL_ACCESS never implies them. See PatientPermission.isGrantedBy.
 *
 * Every successful change publishes CaregiverAccessChangedEvent with the effective values after the save. In the
 * same transaction it may also publish CaregiverMessagingRegainedEvent (messaging effective again after a gap: the
 * messaging listener ends surviving memberships so history restarts) and CaregiverApprovalPendingEvent (the grant
 * started / stopped waiting for the patient's approval: in-app notification for the patient).
 *
 * The class default is read-only, so every write method carries its own @Transactional.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CaregiverService {

    private static final String AUTH_CAREGIVER = "CAREGIVER";
    private static final Set<String> ACCESS_LEVELS = Set.of("FULL_ACCESS", "VIEW_ONLY", "EDIT_ONLY", "CUSTOM");
    private static final Set<String> GENDERS = Set.of("MALE", "FEMALE", "OTHER");
    private static final Set<String> BLOOD_TYPES = Set.of("A+", "A-", "B+", "B-", "O+", "O-", "AB+", "AB-");
    private static final Set<String> HEALTH_STATUSES = Set.of("STABLE", "MONITOR", "CRITICAL");

    static final String ONLY_REDUCE_MESSAGE =
            "You can only reduce your own access. Ask the patient to grant additional permissions.";
    static final String NO_CAREGIVER_MESSAGE = "No caregiver account found for this email";
    static final String ALREADY_GRANTED_MESSAGE = "This caregiver already has access. Change their permissions instead.";

    private final DependentAccessRepository dependentAccessRepository;
    private final UserRepository userRepository;
    private final PatientRepository patientRepository;
    private final CaregiverRepository caregiverRepository;
    private final AppointmentService appointmentService;
    private final ConsultationService consultationService;
    private final AuthService authService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

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

        // Communication permissions can only be turned on by the patient (and then need their approval)
        if (request != null && (turnsOn(request.getCanMessageCareTeam(), access.getCanMessageCareTeam())
                || turnsOn(request.getCanJoinCalls(), access.getCanJoinCalls()))) {
            throw new AuthorizationException(ONLY_REDUCE_MESSAGE, "DEPENDENT_ACCESS", accessId);
        }

        DependentAccess before = copyOf(access);
        // A date-only value for the current expiry day means "unchanged" (it would otherwise parse to 23:59:59)
        if (request != null && request.getExpiresAt() != null && before.getExpiresAt() != null
                && request.getExpiresAt().trim().equals(before.getExpiresAt().toLocalDate().toString())) {
            request.setExpiresAt(null);
        }
        applyChanges(access, request, false);
        if (request != null) {
            // No approver: a caregiver can only turn these off (which clears the approval)
            applyCommunicationChanges(access, request.getCanMessageCareTeam(), request.getCanJoinCalls(), null);
        }

        for (PatientPermission permission : PatientPermission.values()) {
            if (permission.isGrantedBy(access) && !permission.isGrantedBy(before)) {
                throw new AuthorizationException(ONLY_REDUCE_MESSAGE, "DEPENDENT_ACCESS", accessId);
            }
        }
        if (before.getExpiresAt() != null
                && (access.getExpiresAt() == null || access.getExpiresAt().isAfter(before.getExpiresAt()))) {
            throw new AuthorizationException("You cannot extend your own access. Ask the patient to extend it.",
                    "DEPENDENT_ACCESS", accessId);
        }

        DependentAccess saved = dependentAccessRepository.save(access);
        auditService.logClinicalAccess("UPDATE", "DEPENDENT_ACCESS", accessId, saved.getPatientSystemId());
        publishChange(before, saved);
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
        String relationship = requireRelationship(request.getRelationship());
        String accessLevel = parseAccessLevel(request.getAccessLevel());
        LocalDateTime expiresAt = parseFutureExpiry(request.getExpiresAt());
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
                .canViewConsultations(full || Boolean.TRUE.equals(request.getCanViewConsultations()))
                .canScheduleAppointments(full || Boolean.TRUE.equals(request.getCanScheduleAppointments()))
                .canManageCarePlan(full || Boolean.TRUE.equals(request.getCanManageCarePlan()))
                // Communication permissions are stored as REQUESTS only (no approval stamp): the patient must
                // approve them. FULL_ACCESS does not set them.
                .canMessageCareTeam(Boolean.TRUE.equals(request.getCanMessageCareTeam()))
                .canJoinCalls(Boolean.TRUE.equals(request.getCanJoinCalls()))
                .expiresAt(expiresAt)
                .isActive(true)
                .build();
        DependentAccess saved = dependentAccessRepository.save(access);

        auditService.logClinicalAccess("CREATE", "DEPENDENT_ACCESS", saved.getAccessId(), saved.getPatientSystemId());
        publishChange(null, saved);
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
     * Every grant of this patient that has not been revoked (active and expired), newest first.
     */
    public List<CaregiverDependentResponse> getCaregiverAccessForPatient(String patientSystemId) {
        return dependentAccessRepository.findUnrevokedForPatient(patientSystemId)
                .stream()
                .map(access -> toEnrichedResponse(access, false))
                .collect(Collectors.toList());
    }

    /**
     * The patient grants an existing caregiver account access. Communication flags set true here are approved by
     * the patient in the same save. A previously revoked grant for the same caregiver is re-used (UNIQUE
     * caregiver/patient) with fresh values; an unrevoked one gives 409.
     */
    @Transactional
    public CaregiverDependentResponse grantCaregiverAccessAsPatient(String patientSystemId,
                                                                    GrantCaregiverAccessRequest request) {
        if (request == null) {
            throw new RuntimeException("Caregiver details are required");
        }
        String email = request.getCaregiverEmail() == null ? "" : request.getCaregiverEmail().trim().toLowerCase(Locale.ROOT);
        if (email.isEmpty()) {
            throw new RuntimeException("Caregiver email is required");
        }
        String relationship = requireRelationship(request.getRelationship());
        String accessLevel = parseAccessLevel(request.getAccessLevel());
        LocalDateTime expiresAt = parseFutureExpiry(request.getExpiresAt());

        if (patientRepository.findBySystemId(patientSystemId).isEmpty()) {
            throw new AuthorizationException("Only patients can grant caregiver access", "DEPENDENT_ACCESS", null);
        }
        User caregiver = userRepository.findFirstByEmailIgnoreCaseOrderByUserIdAsc(email)
                .filter(user -> Boolean.TRUE.equals(user.getIsActive()))
                .filter(user -> AUTH_CAREGIVER.equalsIgnoreCase(user.getRole()))
                .filter(user -> caregiverRepository.findBySystemId(user.getSystemId()).isPresent())
                .orElseThrow(() -> new ResourceNotFoundException(NO_CAREGIVER_MESSAGE));

        Optional<DependentAccess> existing =
                dependentAccessRepository.findByCaregiverSystemIdAndPatientSystemId(caregiver.getSystemId(), patientSystemId);
        if (existing.isPresent() && existing.get().getRevokedAt() == null
                && Boolean.TRUE.equals(existing.get().getIsActive())) {
            throw new ConflictException(ALREADY_GRANTED_MESSAGE);
        }

        boolean full = "FULL_ACCESS".equals(accessLevel);
        DependentAccess before = existing.map(CaregiverService::copyOf).orElse(null);
        DependentAccess access = existing.orElseGet(DependentAccess::new);
        boolean reused = access.getAccessId() != null;
        access.setPatientSystemId(patientSystemId);
        access.setCaregiverSystemId(caregiver.getSystemId());
        access.setAuthorizedBySystemId(patientSystemId);
        access.setRelationship(relationship);
        access.setAccessLevel(accessLevel);
        access.setCanViewRecords(full || Boolean.TRUE.equals(request.getCanViewRecords()));
        access.setCanViewAppointments(full || Boolean.TRUE.equals(request.getCanViewAppointments()));
        access.setCanViewMedications(full || Boolean.TRUE.equals(request.getCanViewMedications()));
        access.setCanViewLabResults(full || Boolean.TRUE.equals(request.getCanViewLabResults()));
        access.setCanViewImaging(full || Boolean.TRUE.equals(request.getCanViewImaging()));
        access.setCanViewConsultations(full || Boolean.TRUE.equals(request.getCanViewConsultations()));
        access.setCanScheduleAppointments(full || Boolean.TRUE.equals(request.getCanScheduleAppointments()));
        access.setCanManageCarePlan(full || Boolean.TRUE.equals(request.getCanManageCarePlan()));
        // Fresh grant: no approval carried over from a revoked row
        access.setCanMessageCareTeam(false);
        access.setCanJoinCalls(false);
        clearMessagingApproval(access);
        clearCallsApproval(access);
        applyCommunicationChanges(access, Boolean.TRUE.equals(request.getCanMessageCareTeam()),
                Boolean.TRUE.equals(request.getCanJoinCalls()), patientSystemId);
        access.setExpiresAt(expiresAt);
        access.setRevokedAt(null);
        access.setIsActive(true);
        if (reused) {
            // @PrePersist only runs on insert
            access.setAuthorizedDate(LocalDateTime.now());
        }

        DependentAccess saved = dependentAccessRepository.save(access);
        auditService.logClinicalAccess("CREATE", "DEPENDENT_ACCESS", saved.getAccessId(), saved.getPatientSystemId());
        publishChange(before, saved);
        return toEnrichedResponse(saved, false);
    }

    /**
     * Patient (or admin) changes a caregiver's grant: permissions, access level and expiry (empty string removes
     * the expiry). Revoked grants cannot be edited.
     *
     * Setting canMessageCareTeam / canJoinCalls to true approves it when the caller is the patient (also when the
     * flag was already requested but not approved). Admin changes never approve.
     *
     * An expired grant can be extended here. Extending (or re-approving) never brings back the caregiver's old
     * conversation memberships: see CaregiverMessagingRegainedEvent.
     */
    @Transactional
    public CaregiverDependentResponse updateCaregiverAccessAsPatient(String callerSystemId, boolean callerIsAdmin,
                                                                     Integer accessId, UpdateDependentAccessRequest request) {
        DependentAccess access = loadForPatient(callerSystemId, callerIsAdmin, accessId);
        if (!Boolean.TRUE.equals(access.getIsActive()) || access.getRevokedAt() != null) {
            throw new ConflictException("This access has been revoked");
        }
        DependentAccess before = copyOf(access);
        applyChanges(access, request, true);
        if (request != null) {
            boolean callerIsPatient = !callerIsAdmin && access.getPatientSystemId().equals(callerSystemId);
            applyCommunicationChanges(access, request.getCanMessageCareTeam(), request.getCanJoinCalls(),
                    callerIsPatient ? access.getPatientSystemId() : null);
        }
        DependentAccess saved = dependentAccessRepository.save(access);
        auditService.logClinicalAccess("UPDATE", "DEPENDENT_ACCESS", accessId, saved.getPatientSystemId());
        publishChange(before, saved);
        return toEnrichedResponse(saved, false);
    }

    /**
     * Patient (or admin) revokes a caregiver's grant. Idempotent: the event is published every time so
     * conversations and calls are cleaned up even if an earlier attempt failed.
     */
    @Transactional
    public void revokeCaregiverAccessAsPatient(String callerSystemId, boolean callerIsAdmin, Integer accessId) {
        DependentAccess access = loadForPatient(callerSystemId, callerIsAdmin, accessId);
        DependentAccess before = copyOf(access);
        DependentAccess saved = access;
        if (access.getRevokedAt() == null || Boolean.TRUE.equals(access.getIsActive())) {
            access.setIsActive(false);
            access.setRevokedAt(LocalDateTime.now());
            clearMessagingApproval(access);
            clearCallsApproval(access);
            saved = dependentAccessRepository.save(access);
            auditService.logClinicalAccess("DELETE", "DEPENDENT_ACCESS", accessId, access.getPatientSystemId());
        }
        publishChange(before, saved);
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

    private static boolean turnsOn(Boolean requested, Boolean current) {
        return Boolean.TRUE.equals(requested) && !Boolean.TRUE.equals(current);
    }

    /**
     * Sets the communication flags (null = unchanged). {@code approverSystemId} is the patient when the patient is
     * the caller, else null: with an approver, a flag set true is stamped approved (if not approved yet). In every
     * case a flag that ends up false loses its approval, keeping the V15 CHECK constraints satisfied.
     */
    static void applyCommunicationChanges(DependentAccess access, Boolean messaging, Boolean calls,
                                          String approverSystemId) {
        boolean approve = approverSystemId != null && approverSystemId.equals(access.getPatientSystemId());
        if (messaging != null) {
            access.setCanMessageCareTeam(messaging);
            if (messaging && approve && !PatientPermission.MESSAGING.isGrantedBy(access)) {
                access.setMessagingApprovedAt(Instant.now());
                access.setMessagingApprovedBySystemId(approverSystemId);
            }
        }
        if (calls != null) {
            access.setCanJoinCalls(calls);
            if (calls && approve && !PatientPermission.JOIN_CALLS.isGrantedBy(access)) {
                access.setCallsApprovedAt(Instant.now());
                access.setCallsApprovedBySystemId(approverSystemId);
            }
        }
        if (!PatientPermission.MESSAGING.isGrantedBy(access)) {
            clearMessagingApproval(access);
        }
        if (!PatientPermission.JOIN_CALLS.isGrantedBy(access)) {
            clearCallsApproval(access);
        }
    }

    private static void clearMessagingApproval(DependentAccess access) {
        access.setMessagingApprovedAt(null);
        access.setMessagingApprovedBySystemId(null);
    }

    private static void clearCallsApproval(DependentAccess access) {
        access.setCallsApprovedAt(null);
        access.setCallsApprovedBySystemId(null);
    }

    /**
     * @param before the grant as it was before this change (copyOf), or null for a new row
     */
    private void publishChange(DependentAccess before, DependentAccess access) {
        boolean active = isCurrentlyActive(access);
        eventPublisher.publishEvent(new CaregiverAccessChangedEvent(
                access.getAccessId(),
                access.getPatientSystemId(),
                access.getCaregiverSystemId(),
                !active,
                active && PatientPermission.MESSAGING.isGrantedBy(access),
                active && PatientPermission.JOIN_CALLS.isGrantedBy(access)));

        if (before != null && !messagingEffective(before) && messagingEffective(access)) {
            eventPublisher.publishEvent(new CaregiverMessagingRegainedEvent(
                    access.getAccessId(), access.getPatientSystemId(), access.getCaregiverSystemId(), isExpired(before)));
        }

        boolean wasPending = before != null && isApprovalPending(before);
        boolean pending = isApprovalPending(access);
        if (wasPending != pending) {
            eventPublisher.publishEvent(new CaregiverApprovalPendingEvent(
                    access.getAccessId(), access.getPatientSystemId(), access.getCaregiverSystemId(), pending,
                    pending && Boolean.TRUE.equals(access.getCanMessageCareTeam()) && !PatientPermission.MESSAGING.isGrantedBy(access),
                    pending && Boolean.TRUE.equals(access.getCanJoinCalls()) && !PatientPermission.JOIN_CALLS.isGrantedBy(access)));
        }
    }

    private static boolean messagingEffective(DependentAccess access) {
        return isCurrentlyActive(access) && PatientPermission.MESSAGING.isGrantedBy(access);
    }

    /** Not revoked, but past its expiry. */
    private static boolean isExpired(DependentAccess access) {
        return Boolean.TRUE.equals(access.getIsActive()) && access.getRevokedAt() == null
                && access.getExpiresAt() != null && !access.getExpiresAt().isAfter(LocalDateTime.now());
    }

    /** Active grant with a communication flag requested but not (yet) approved by the patient. */
    static boolean isApprovalPending(DependentAccess access) {
        return isCurrentlyActive(access)
                && ((Boolean.TRUE.equals(access.getCanMessageCareTeam()) && !PatientPermission.MESSAGING.isGrantedBy(access))
                    || (Boolean.TRUE.equals(access.getCanJoinCalls()) && !PatientPermission.JOIN_CALLS.isGrantedBy(access)));
    }

    private static String accessStatus(DependentAccess access) {
        if (access.getRevokedAt() != null || !Boolean.TRUE.equals(access.getIsActive())) {
            return "REVOKED";
        }
        if (access.getExpiresAt() != null && !access.getExpiresAt().isAfter(LocalDateTime.now())) {
            return "EXPIRED";
        }
        return "ACTIVE";
    }

    private static String requireRelationship(String raw) {
        String relationship = raw == null ? "" : raw.trim();
        if (relationship.isEmpty()) {
            throw new RuntimeException("Relationship is required");
        }
        if (relationship.length() > 80) {
            throw new RuntimeException("Relationship must be at most 80 characters");
        }
        return relationship;
    }

    private static String parseAccessLevel(String raw) {
        String accessLevel = raw == null || raw.isBlank() ? "VIEW_ONLY" : raw.trim().toUpperCase(Locale.ROOT);
        if (!ACCESS_LEVELS.contains(accessLevel)) {
            throw new RuntimeException("Invalid access level. Must be FULL_ACCESS, VIEW_ONLY, EDIT_ONLY or CUSTOM");
        }
        return accessLevel;
    }

    private static LocalDateTime parseFutureExpiry(String raw) {
        LocalDateTime expiresAt = parseExpiry(raw);
        if (expiresAt != null && !expiresAt.isAfter(LocalDateTime.now())) {
            throw new RuntimeException("Access expiry must be in the future");
        }
        return expiresAt;
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
        // canMessageCareTeam / canJoinCalls: see applyCommunicationChanges (they carry the approval rule)
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
                .canJoinCalls(a.getCanJoinCalls())
                .messagingApprovedAt(a.getMessagingApprovedAt())
                .messagingApprovedBySystemId(a.getMessagingApprovedBySystemId())
                .callsApprovedAt(a.getCallsApprovedAt())
                .callsApprovedBySystemId(a.getCallsApprovedBySystemId())
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
        boolean active = isCurrentlyActive(access);
        boolean messagingAllowed = active && PatientPermission.MESSAGING.isGrantedBy(access);
        boolean joinCallsAllowed = active && PatientPermission.JOIN_CALLS.isGrantedBy(access);
        boolean pendingApproval = isApprovalPending(access);
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
                .revokedAt(access.getRevokedAt())
                .canJoinCalls(access.getCanJoinCalls())
                .messagingApprovedAt(access.getMessagingApprovedAt())
                .callsApprovedAt(access.getCallsApprovedAt())
                .messagingAllowed(messagingAllowed)
                .joinCallsAllowed(joinCallsAllowed)
                .pendingApproval(pendingApproval)
                .accessStatus(accessStatus(access));

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
