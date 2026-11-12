package com.carenexus.api.careteam.service;

import com.carenexus.api.careteam.dto.request.AssignCareTeamMemberRequest;
import com.carenexus.api.careteam.dto.request.CareTeamRequestRules;
import com.carenexus.api.careteam.dto.request.UpdateCareTeamMemberRequest;
import com.carenexus.api.careteam.dto.response.CareTeamMemberResponse;
import com.carenexus.api.careteam.repository.CareTeamLookupRepository;
import com.carenexus.api.careteam.repository.CareTeamLookupRepository.DoctorProfile;
import com.carenexus.api.careteam.repository.CareTeamLookupRepository.FacilityRow;
import com.carenexus.api.careteam.repository.CareTeamLookupRepository.Person;
import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.communications.notify.NotificationCategory;
import com.carenexus.api.communications.notify.NotificationCommand;
import com.carenexus.api.communications.notify.NotificationPriority;
import com.carenexus.api.communications.notify.NotificationPublisher;
import com.carenexus.api.communications.notify.NotificationTarget;
import com.carenexus.api.communications.notify.NotificationType;
import com.carenexus.api.core.model.CareTeam;
import com.carenexus.api.core.model.Doctor;
import com.carenexus.api.core.repository.CareTeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Care team management: who looks after a patient.
 *
 * <ul>
 *   <li>Members are care_team rows (ASSIGNED): CareNexus doctors ({@code providerSystemId}, role DOCTOR or
 *       SPECIALIST) and external providers entered by name (any of the six roles).</li>
 *   <li>Lists also show doctors related to the patient only through a non-cancelled appointment or a consultation
 *       (DERIVED, read-only), so the patient sees everyone who treats them.</li>
 *   <li>At most one primary member per patient; setting a new primary demotes the previous one in the same
 *       transaction, under a per-patient lock.</li>
 *   <li>Unassigning is a soft delete ({@code is_active = false}, {@code unassigned_at}). A doctor who still has an
 *       appointment or consultation with the patient keeps access (RelationshipService rule) and reappears as
 *       DERIVED.</li>
 * </ul>
 *
 * Authorization is checked here on every method (AuthorizationService); every success is audited with resource
 * {@value #RESOURCE}. An assignment notifies the patient (SYSTEM / ACCESS_CHANGE, target CARE_TEAM).
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class CareTeamService {

    static final String RESOURCE = "CARE_TEAM";

    /** Hard cap of each part (ASSIGNED, DERIVED) of a patient's list. */
    static final int LIST_CAP = 100;

    static final String PATIENT_NOT_FOUND = "Patient not found";
    static final String MEMBER_NOT_FOUND = "Care team member not found";
    static final String PROVIDE_PROVIDER = "Provide providerSystemId or providerName";
    static final String PROVIDER_EXCLUSIVE = "Provide either providerSystemId or the external provider fields, not both";
    static final String NOT_APPROVED_DOCTOR = "The selected provider is not an active, approved doctor";
    static final String ACCOUNT_ROLE_ONLY = "A CareNexus doctor can only be assigned as DOCTOR or SPECIALIST";
    static final String FACILITY_INVALID = "The selected facility does not exist or is inactive";
    static final String DUPLICATE = "This provider is already on the patient's care team";
    static final String PROVIDE_CHANGE = "Provide role or isPrimary";
    static final String PATIENT_PRIMARY_ONLY = "Patients can only change the primary care team member";
    static final String REMOVE_DENIED =
            "Only the patient, the assigned provider or an administrator can remove a care team member";

    private final CareTeamRepository careTeamRepository;
    private final CareTeamLookupRepository lookupRepository;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final NotificationPublisher notificationPublisher;
    private final Clock clock;

    /** Shared, transaction-bound EntityManager; used only to refresh rows read again under the care-team lock. */
    @PersistenceContext
    private EntityManager entityManager;

    // ==================== READ ====================

    /**
     * A patient's care team: active assigned members (primary first, then oldest assignment) followed by the doctors
     * related only through appointments or consultations (by name). At most {@value #LIST_CAP} of each.
     * Patient (self), related doctor, caregiver with any active grant, admin.
     */
    @Transactional(readOnly = true)
    public List<CareTeamMemberResponse> listForPatient(String patientSystemId) {
        authorizationService.requirePatientAccess(patientSystemId, PatientPermission.CARE_TEAM);
        requirePatientExists(patientSystemId);
        List<CareTeamMemberResponse> members = buildCareTeam(patientSystemId);
        auditService.logClinicalAccess("READ", RESOURCE, null, patientSystemId);
        return members;
    }

    /**
     * The signed-in patient's own care team (same content as {@link #listForPatient}). PATIENT only.
     */
    @Transactional(readOnly = true)
    public List<CareTeamMemberResponse> listMine() {
        authorizationService.requireRole(AuthorizationService.PATIENT);
        String patientSystemId = authorizationService.currentSystemId();
        List<CareTeamMemberResponse> members = buildCareTeam(patientSystemId);
        auditService.logClinicalAccess("READ", RESOURCE, null, patientSystemId);
        return members;
    }

    /**
     * The calling doctor's own active assignments, newest first, paged. DOCTOR only (an admin has none).
     */
    @Transactional(readOnly = true)
    public PageResponse<CareTeamMemberResponse> listForDoctor(Integer page, Integer size) {
        authorizationService.requireRole(AuthorizationService.DOCTOR);
        String doctorSystemId = authorizationService.currentSystemId();
        Pageable pageable = PageParams.of(page, size, Sort.unsorted());
        Page<CareTeam> rows = careTeamRepository.findActiveAssignmentsForProvider(doctorSystemId, pageable);
        List<CareTeamMemberResponse> content = toResponses(rows.getContent());
        auditService.logListRead(RESOURCE, content, CareTeamMemberResponse::getPatientSystemId);
        return PageResponse.of(rows, content);
    }

    // ==================== ASSIGN ====================

    /**
     * Adds a member to the patient's care team. A related doctor or an admin. A provider account must be an active,
     * approved doctor assigned as DOCTOR or SPECIALIST; an external provider may hold any of the six roles. One
     * active row per provider (409). {@code isPrimary = true} demotes the previous primary. Notifies the patient.
     */
    public CareTeamMemberResponse assign(String patientSystemId, AssignCareTeamMemberRequest request) {
        authorizationService.requireRole(AuthorizationService.DOCTOR, AuthorizationService.ADMIN);
        authorizationService.requirePatientAccess(patientSystemId, PatientPermission.CARE_TEAM);
        requirePatientExists(patientSystemId);
        String callerSystemId = authorizationService.currentSystemId();

        String role = requireWritableRole(request.getRole());
        String providerSystemId = trimToNull(request.getProviderSystemId());
        String providerName = trimToNull(request.getProviderName());
        String providerPhone = trimToNull(request.getProviderPhone());
        String providerOrganisation = trimToNull(request.getProviderOrganisation());
        Integer facilityId = request.getFacilityId();

        if (providerSystemId == null && providerName == null) {
            throw new BadRequestException(PROVIDE_PROVIDER);
        }
        if (providerSystemId != null && (providerName != null || providerPhone != null || providerOrganisation != null)) {
            throw new BadRequestException(PROVIDER_EXCLUSIVE);
        }
        if (providerSystemId != null) {
            requireApprovedDoctor(providerSystemId);
            if (!CareTeamRoles.ACCOUNT_PROVIDER_ROLES.contains(role)) {
                throw new BadRequestException(ACCOUNT_ROLE_ONLY);
            }
        }
        if (facilityId != null && !lookupRepository.existsActiveFacility(facilityId)) {
            throw new BadRequestException(FACILITY_INVALID);
        }

        List<CareTeam> active = lockCareTeam(patientSystemId);
        if (isDuplicate(active, providerSystemId, providerName, role)) {
            throw new ConflictException(DUPLICATE);
        }

        boolean primary = Boolean.TRUE.equals(request.getIsPrimary());
        if (primary) {
            demotePrimaries(active, null, patientSystemId);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        CareTeam row = careTeamRepository.saveAndFlush(CareTeam.builder()
                .patientSystemId(patientSystemId)
                .providerSystemId(providerSystemId)
                .providerName(providerSystemId == null ? providerName : null)
                .providerPhone(providerSystemId == null ? providerPhone : null)
                .providerOrganisation(providerSystemId == null ? providerOrganisation : null)
                .facilityId(facilityId)
                .role(role)
                .isPrimary(primary)
                .isActive(true)
                .assignedAt(now)
                .joinedDate(now.toLocalDate())
                .createdAt(now)
                .build());
        auditService.logClinicalAccess("CREATE", RESOURCE, row.getCareTeamId(), patientSystemId);

        CareTeamMemberResponse response = toResponses(List.of(row)).get(0);
        notifyPatient(response, callerSystemId);
        log.info("Care team member {} assigned to {} by {}", row.getCareTeamId(), patientSystemId, callerSystemId);
        return response;
    }

    // ==================== UPDATE ====================

    /**
     * Changes a member's role and/or primary flag. A related doctor or an admin may change both; the patient may
     * only set or clear primary. A request that changes nothing returns the current state without writing.
     */
    public CareTeamMemberResponse update(String patientSystemId, Integer careTeamId, UpdateCareTeamMemberRequest request) {
        authorizationService.requireRole(AuthorizationService.PATIENT, AuthorizationService.DOCTOR,
                AuthorizationService.ADMIN);
        authorizationService.requirePatientAccess(patientSystemId, PatientPermission.CARE_TEAM, careTeamId);

        // Lock first, then read: every CareTeam state used below comes from the locked, refreshed rows, so a swap
        // committed by a concurrent request while this one waited is seen (never reverted from a stale copy).
        List<CareTeam> active = lockCareTeam(patientSystemId);
        CareTeam row = active.stream()
                .filter(r -> r.getCareTeamId().equals(careTeamId) && CareTeamRoles.isMemberRole(r.getRole()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(MEMBER_NOT_FOUND));

        String requestedRole = request.getRole() == null ? null : requireWritableRole(request.getRole());
        Boolean requestedPrimary = request.getIsPrimary();
        if (requestedRole != null && AuthorizationService.PATIENT.equals(authorizationService.currentRole())) {
            deny(patientSystemId, careTeamId, "Patient tried to change a care team role", PATIENT_PRIMARY_ONLY);
        }
        if (requestedRole == null && requestedPrimary == null) {
            throw new BadRequestException(PROVIDE_CHANGE);
        }
        if (requestedRole != null && row.getProviderSystemId() != null
                && !CareTeamRoles.ACCOUNT_PROVIDER_ROLES.contains(requestedRole)) {
            throw new BadRequestException(ACCOUNT_ROLE_ONLY);
        }

        boolean legacyPrimaryDoctor = CareTeamRoles.PRIMARY_DOCTOR.equals(row.getRole());
        String newRole = requestedRole != null ? requestedRole
                : (legacyPrimaryDoctor ? CareTeamRoles.DOCTOR : row.getRole());
        boolean newPrimary = requestedPrimary != null ? requestedPrimary
                : (legacyPrimaryDoctor || Boolean.TRUE.equals(row.getIsPrimary()));

        boolean unchanged = newRole.equals(row.getRole()) && newPrimary == Boolean.TRUE.equals(row.getIsPrimary());
        if (unchanged) {
            return toResponses(List.of(row)).get(0);
        }
        if (newPrimary) {
            demotePrimaries(active, row.getCareTeamId(), patientSystemId);
        }
        row.setRole(newRole);
        row.setIsPrimary(newPrimary);
        CareTeam saved = careTeamRepository.saveAndFlush(row);
        auditService.logClinicalAccess("UPDATE", RESOURCE, saved.getCareTeamId(), patientSystemId);
        return toResponses(List.of(saved)).get(0);
    }

    // ==================== REMOVE ====================

    /**
     * Unassigns a member (soft delete). The patient, the doctor on the row, or an admin. Repeating it on a removed
     * member changes nothing.
     */
    public void remove(String patientSystemId, Integer careTeamId) {
        authorizationService.requireRole(AuthorizationService.PATIENT, AuthorizationService.DOCTOR,
                AuthorizationService.ADMIN);
        String role = authorizationService.currentRole();
        String callerSystemId = authorizationService.currentSystemId();
        if (AuthorizationService.PATIENT.equals(role)) {
            authorizationService.requirePatientAccess(patientSystemId, PatientPermission.CARE_TEAM, careTeamId);
        }

        careTeamRepository.lockPatientCareTeam(patientSystemId);
        CareTeam row = careTeamRepository.findMemberOfPatient(careTeamId, patientSystemId)
                .orElseThrow(() -> new ResourceNotFoundException(MEMBER_NOT_FOUND));
        // The row may already be in the persistence context from before the lock (the authorization check loads
        // the caller's own assignment): re-read it, locked, so the active check and the flush use current state.
        entityManager.refresh(row, LockModeType.PESSIMISTIC_WRITE);
        if (AuthorizationService.DOCTOR.equals(role) && !callerSystemId.equals(row.getProviderSystemId())) {
            deny(patientSystemId, careTeamId, "Doctor tried to remove a care team member other than themselves",
                    REMOVE_DENIED);
        }
        if (!Boolean.TRUE.equals(row.getIsActive())) {
            return;
        }
        row.setIsActive(false);
        row.setUnassignedAt(LocalDateTime.now(clock));
        careTeamRepository.saveAndFlush(row);
        auditService.logClinicalAccess("DELETE", RESOURCE, careTeamId, patientSystemId);
        log.info("Care team member {} of {} removed by {}", careTeamId, patientSystemId, callerSystemId);
    }

    // ==================== RULES ====================

    private void requirePatientExists(String patientSystemId) {
        if (!lookupRepository.existsPatient(patientSystemId)) {
            throw new ResourceNotFoundException(PATIENT_NOT_FOUND);
        }
    }

    /**
     * Serialises every write to one patient's care team: takes the per-patient advisory lock, then locks the
     * patient's active rows FOR UPDATE and refreshes each one. The refresh matters because Hibernate returns an
     * instance it already holds (loaded before the lock, e.g. by the authorization check) without copying the
     * locked row's state into it; without it a writer that waited on the lock would act on, and flush back, the
     * values from before the concurrent commit. Lock order is always advisory lock, then rows by id.
     */
    private List<CareTeam> lockCareTeam(String patientSystemId) {
        careTeamRepository.lockPatientCareTeam(patientSystemId);
        List<CareTeam> active = careTeamRepository.lockActiveForPatient(patientSystemId);
        active.forEach(entityManager::refresh);
        return active;
    }

    /** Normalised role; the DTO already rejects other values, this guards direct service callers. */
    private static String requireWritableRole(String role) {
        String normalized = CareTeamRoles.normalize(role);
        if (normalized == null) {
            throw new BadRequestException("role: " + CareTeamRequestRules.ROLE_REQUIRED);
        }
        if (!CareTeamRoles.WRITABLE.contains(normalized)) {
            throw new BadRequestException("role: " + CareTeamRequestRules.ROLE_INVALID);
        }
        return normalized;
    }

    /** The provider account exists, is an active user with role DOCTOR, and its doctor profile is APPROVED. */
    private void requireApprovedDoctor(String providerSystemId) {
        Set<String> ids = Set.of(providerSystemId);
        boolean activeDoctorUser = lookupRepository.findPeople(ids).stream()
                .anyMatch(p -> Boolean.TRUE.equals(p.getActive()) && "DOCTOR".equalsIgnoreCase(p.getRole()));
        boolean approvedProfile = lookupRepository.findDoctors(ids).stream()
                .anyMatch(d -> Doctor.VERIFICATION_APPROVED.equals(d.getVerificationStatus())
                        && !Boolean.FALSE.equals(d.getActive()));
        if (!activeDoctorUser || !approvedProfile) {
            throw new BadRequestException(NOT_APPROVED_DOCTOR);
        }
    }

    /**
     * An active row (any role, legacy rows included) for the same provider account, or an active external member
     * with the same name (case-insensitive) and the same role.
     */
    private static boolean isDuplicate(List<CareTeam> active, String providerSystemId, String providerName, String role) {
        if (providerSystemId != null) {
            return active.stream().anyMatch(r -> providerSystemId.equals(r.getProviderSystemId()));
        }
        String name = providerName.toLowerCase(Locale.ROOT);
        return active.stream().anyMatch(r -> r.getProviderSystemId() == null
                && r.getProviderName() != null
                && r.getProviderName().trim().toLowerCase(Locale.ROOT).equals(name)
                && role.equals(r.getRole()));
    }

    /**
     * Clears the primary flag of every other active row of the patient and rewrites another legacy PRIMARY_DOCTOR
     * row to DOCTOR (so the read rule and the single-primary index agree). Flushed before the caller writes the new
     * primary, because the partial unique index is checked per statement. One UPDATE audit row per changed member.
     */
    private void demotePrimaries(List<CareTeam> active, Integer keepCareTeamId, String patientSystemId) {
        List<CareTeam> changed = new ArrayList<>();
        for (CareTeam other : active) {
            if (other.getCareTeamId().equals(keepCareTeamId)) {
                continue;
            }
            boolean wasPrimary = Boolean.TRUE.equals(other.getIsPrimary());
            boolean legacy = CareTeamRoles.PRIMARY_DOCTOR.equals(other.getRole());
            if (!wasPrimary && !legacy) {
                continue;
            }
            other.setIsPrimary(false);
            if (legacy) {
                other.setRole(CareTeamRoles.DOCTOR);
            }
            changed.add(other);
        }
        if (changed.isEmpty()) {
            return;
        }
        careTeamRepository.saveAllAndFlush(changed);
        changed.forEach(other ->
                auditService.logClinicalAccess("UPDATE", RESOURCE, other.getCareTeamId(), patientSystemId));
    }

    private void deny(String patientSystemId, Integer careTeamId, String auditReason, String clientMessage) {
        try {
            auditService.logAccessDenied(authorizationService.currentSystemId(), RESOURCE, careTeamId,
                    patientSystemId, null, auditReason);
        } catch (RuntimeException auditFailure) {
            log.error("Failed to write ACCESS_DENIED audit row: {}", auditFailure.getClass().getName());
        }
        throw new AuthorizationException(clientMessage, RESOURCE, careTeamId);
    }

    // ==================== NOTIFICATION ====================

    private void notifyPatient(CareTeamMemberResponse member, String actorSystemId) {
        String who = member.getProviderSystemId() != null ? "Dr. " + member.getProviderName() : member.getProviderName();
        notificationPublisher.publish(NotificationCommand.builder()
                .recipientSystemId(member.getPatientSystemId())
                .type(NotificationType.SYSTEM)
                .category(NotificationCategory.ACCESS_CHANGE)
                .priority(NotificationPriority.NORMAL)
                .title("Care team updated")
                .message(who + " was added to your care team as " + CareTeamRoles.label(member.getRole()) + ".")
                .target(NotificationTarget.CARE_TEAM)
                .resourceType(RESOURCE)
                .resourceId(member.getCareTeamId())
                .patientSystemId(member.getPatientSystemId())
                .actorSystemId(actorSystemId)
                .dedupeKey("CARE_TEAM_ASSIGNED:" + member.getCareTeamId())
                .build());
    }

    // ==================== MAPPING ====================

    /** ASSIGNED rows then DERIVED doctors of one patient, with one lookup query per kind. */
    private List<CareTeamMemberResponse> buildCareTeam(String patientSystemId) {
        List<CareTeam> assigned = careTeamRepository.findActiveMembersForPatient(patientSystemId,
                PageRequest.of(0, LIST_CAP));

        Set<String> assignedProviders = new HashSet<>(
                careTeamRepository.findActiveProviderSystemIdsForPatient(patientSystemId));
        Set<String> derivedCandidates = new LinkedHashSet<>();
        derivedCandidates.addAll(careTeamRepository.findAppointmentDoctorSystemIdsForPatient(patientSystemId));
        derivedCandidates.addAll(careTeamRepository.findConsultationDoctorSystemIdsForPatient(patientSystemId));
        derivedCandidates.remove(null);
        derivedCandidates.removeAll(assignedProviders);

        Lookups lookups = lookups(assigned, Set.of(patientSystemId), derivedCandidates);
        List<CareTeamMemberResponse> members = new ArrayList<>(assigned.size());
        assigned.forEach(row -> members.add(toResponse(row, lookups)));

        String patientName = lookups.name(patientSystemId);
        derivedCandidates.stream()
                .map(lookups.people()::get)
                .filter(Objects::nonNull)
                .filter(p -> Boolean.TRUE.equals(p.getActive()) && "DOCTOR".equalsIgnoreCase(p.getRole()))
                .map(p -> CareTeamMemberResponse.builder()
                        .careTeamId(null)
                        .patientSystemId(patientSystemId)
                        .patientName(patientName)
                        .source(CareTeamMemberResponse.SOURCE_DERIVED)
                        .providerSystemId(p.getSystemId())
                        .providerName(lookups.name(p.getSystemId()))
                        .role(CareTeamRoles.DOCTOR)
                        .isPrimary(false)
                        .specialization(lookups.specialization(p.getSystemId()))
                        .build())
                .sorted(Comparator.comparing((CareTeamMemberResponse m) -> m.getProviderName().toLowerCase(Locale.ROOT))
                        .thenComparing(CareTeamMemberResponse::getProviderSystemId))
                .limit(LIST_CAP)
                .forEach(members::add);
        return members;
    }

    /** ASSIGNED responses for rows that may belong to several patients, with one lookup query per kind. */
    private List<CareTeamMemberResponse> toResponses(List<CareTeam> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<String> patients = rows.stream().map(CareTeam::getPatientSystemId).collect(Collectors.toSet());
        Lookups lookups = lookups(rows, patients, Set.of());
        return rows.stream().map(row -> toResponse(row, lookups)).toList();
    }

    private Lookups lookups(Collection<CareTeam> rows, Collection<String> patients, Collection<String> extraDoctors) {
        Set<String> doctors = new HashSet<>(extraDoctors);
        rows.stream().map(CareTeam::getProviderSystemId).filter(Objects::nonNull).forEach(doctors::add);
        Set<String> people = new HashSet<>(doctors);
        people.addAll(patients);
        Set<Integer> facilityIds = rows.stream().map(CareTeam::getFacilityId).filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<String, Person> personMap = people.isEmpty() ? Map.of()
                : index(lookupRepository.findPeople(people), Person::getSystemId);
        Map<String, DoctorProfile> doctorMap = doctors.isEmpty() ? Map.of()
                : index(lookupRepository.findDoctors(doctors), DoctorProfile::getSystemId);
        Map<Integer, FacilityRow> facilityMap = facilityIds.isEmpty() ? Map.of()
                : index(lookupRepository.findFacilities(facilityIds), FacilityRow::getFacilityId);
        return new Lookups(personMap, doctorMap, facilityMap);
    }

    private static <K, V> Map<K, V> index(List<V> values, Function<V, K> key) {
        return values.stream().collect(Collectors.toMap(key, Function.identity(), (a, b) -> a));
    }

    private static CareTeamMemberResponse toResponse(CareTeam row, Lookups lookups) {
        boolean external = row.getProviderSystemId() == null;
        FacilityRow facility = row.getFacilityId() == null ? null : lookups.facilities().get(row.getFacilityId());
        return CareTeamMemberResponse.builder()
                .careTeamId(row.getCareTeamId())
                .patientSystemId(row.getPatientSystemId())
                .patientName(lookups.name(row.getPatientSystemId()))
                .source(CareTeamMemberResponse.SOURCE_ASSIGNED)
                .providerSystemId(row.getProviderSystemId())
                .providerName(external ? row.getProviderName() : lookups.name(row.getProviderSystemId()))
                .providerPhone(external ? row.getProviderPhone() : null)
                .providerOrganisation(external ? row.getProviderOrganisation() : null)
                .role(CareTeamRoles.readRole(row.getRole()))
                .isPrimary(CareTeamRoles.readPrimary(row.getRole(), row.getIsPrimary()))
                .specialization(external ? null : lookups.specialization(row.getProviderSystemId()))
                .facilityId(row.getFacilityId())
                .facilityName(facility != null ? facility.getName() : null)
                .assignedAt(row.getAssignedAt())
                .build();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Batched lookups for one response build. */
    private record Lookups(Map<String, Person> people, Map<String, DoctorProfile> doctors,
                           Map<Integer, FacilityRow> facilities) {

        /** "First Last" of an account; the system id when the account has no name (never null). */
        String name(String systemId) {
            Person person = people.get(systemId);
            if (person == null) {
                return systemId;
            }
            String name = ((person.getFirstName() == null ? "" : person.getFirstName().trim()) + " "
                    + (person.getLastName() == null ? "" : person.getLastName().trim())).trim();
            return name.isEmpty() ? systemId : name;
        }

        String specialization(String systemId) {
            DoctorProfile doctor = doctors.get(systemId);
            if (doctor == null || doctor.getSpecialization() == null || doctor.getSpecialization().isBlank()) {
                return null;
            }
            return doctor.getSpecialization().trim();
        }
    }
}
