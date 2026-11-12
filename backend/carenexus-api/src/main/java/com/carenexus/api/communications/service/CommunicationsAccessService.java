package com.carenexus.api.communications.service;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.common.service.PatientPermission;
import com.carenexus.api.communications.model.MessageThread;
import com.carenexus.api.communications.model.ThreadMember;
import com.carenexus.api.communications.repository.CommunicationUserRepository;
import com.carenexus.api.communications.repository.MessageThreadRepository;
import com.carenexus.api.communications.repository.ThreadMemberRepository;
import com.carenexus.api.core.model.DependentAccess;
import com.carenexus.api.core.repository.DependentAccessRepository;
import com.carenexus.api.core.service.RelationshipService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * All messaging access rules (contract 7.1), checked on EVERY request:
 * - ADMIN never reads or sends messages.
 * - Thread access = active membership AND the caller can still act for the thread's patient now
 *   (PATIENT self, DOCTOR with a care relationship, CAREGIVER with an approved MESSAGING grant).
 * - Membership eligibility for patient P: P, doctors related to P, caregivers with an approved MESSAGING grant.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CommunicationsAccessService {

    public static final String ADMIN_DENIED = "Administrators cannot access conversations";
    public static final String THREAD_DENIED = "You no longer have access to this conversation";
    public static final String THREAD_NOT_FOUND = "Conversation not found";
    public static final String RESOURCE_THREAD = "MESSAGE_THREAD";

    private final AuthorizationService authorizationService;
    private final RelationshipService relationshipService;
    private final DependentAccessRepository dependentAccessRepository;
    private final MessageThreadRepository threadRepository;
    private final ThreadMemberRepository memberRepository;
    private final CommunicationUserRepository userRepository;
    private final AuditService auditService;

    /** The authenticated caller (system id + uppercase role) from the JWT. */
    public record Caller(String systemId, String role) {
        public boolean is(String expectedRole) {
            return expectedRole.equals(role);
        }

        public boolean canManageMembers() {
            return is(AuthorizationService.PATIENT) || is(AuthorizationService.DOCTOR);
        }
    }

    /** A thread the caller may access, with the caller's active membership row. */
    public record ThreadAccess(MessageThread thread, ThreadMember member) {
    }

    /** Caller from the JWT, any role (notifications). */
    public Caller caller() {
        return new Caller(authorizationService.currentSystemId(), authorizationService.currentRole());
    }

    /** Caller from the JWT; ADMIN is rejected with 403 (all /api/communications/** endpoints). */
    public Caller requireCaller() {
        Caller caller = caller();
        if (caller.is(AuthorizationService.ADMIN)) {
            throw new AuthorizationException(ADMIN_DENIED);
        }
        return caller;
    }

    /** Caller can act for the patient (audited denial, 403). */
    public void requireCanActFor(String patientSystemId) {
        authorizationService.requirePatientAccess(patientSystemId, PatientPermission.MESSAGING);
    }

    /**
     * 404 "Conversation not found" for an unknown id; 403 "You no longer have access to this conversation" when
     * the caller is not an active member or can no longer act for the thread's patient.
     */
    public ThreadAccess requireThreadAccess(Caller caller, Integer threadId) {
        if (threadId == null) {
            throw new ResourceNotFoundException(THREAD_NOT_FOUND);
        }
        MessageThread thread = threadRepository.findById(threadId)
                .orElseThrow(() -> new ResourceNotFoundException(THREAD_NOT_FOUND));
        ThreadMember member = memberRepository.findByThreadIdAndSystemId(threadId, caller.systemId())
                .filter(ThreadMember::isActive)
                .orElse(null);
        if (member == null) {
            try {
                auditService.logAccessDenied(caller.systemId(), RESOURCE_THREAD, threadId, thread.getPatientSystemId(),
                        null, "Not an active member of the conversation");
            } catch (RuntimeException auditFailure) {
                log.error("Failed to write ACCESS_DENIED audit row: {}", auditFailure.getClass().getName());
            }
            throw new AuthorizationException(THREAD_DENIED, RESOURCE_THREAD, threadId);
        }
        try {
            authorizationService.requirePatientAccess(thread.getPatientSystemId(), PatientPermission.MESSAGING, threadId);
        } catch (AuthorizationException denied) {
            // requirePatientAccess already wrote the ACCESS_DENIED audit row
            throw new AuthorizationException(THREAD_DENIED, RESOURCE_THREAD, threadId);
        }
        return new ThreadAccess(thread, member);
    }

    /**
     * Patients the caller may act for right now (computed once per request, avoids per-thread checks).
     */
    public Set<String> actablePatients(Caller caller) {
        return switch (caller.role()) {
            case AuthorizationService.PATIENT -> Set.of(caller.systemId());
            case AuthorizationService.DOCTOR -> new LinkedHashSet<>(relationshipService.getPatientsForDoctor(caller.systemId()));
            case AuthorizationService.CAREGIVER -> dependentAccessRepository.findAllDependentsForCaregiver(caller.systemId())
                    .stream()
                    .filter(PatientPermission.MESSAGING::isGrantedBy)
                    .map(DependentAccess::getPatientSystemId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            default -> Set.of();
        };
    }

    /**
     * Non-throwing, audit-free check for OTHER members (e.g. notification recipients) and for code without a
     * request context.
     */
    public boolean memberCanActFor(String systemId, String memberRole, String patientSystemId) {
        if (systemId == null || memberRole == null || patientSystemId == null) {
            return false;
        }
        return switch (memberRole.toUpperCase(Locale.ROOT)) {
            case AuthorizationService.PATIENT -> systemId.equals(patientSystemId);
            case AuthorizationService.DOCTOR -> relationshipService.isDoctorAssignedToPatient(systemId, patientSystemId);
            case AuthorizationService.CAREGIVER ->
                    authorizationService.hasCaregiverPermission(systemId, patientSystemId, PatientPermission.MESSAGING);
            default -> false;
        };
    }

    /**
     * Threads the caller may access now (active membership + can act for the thread's patient). Empty for ADMIN.
     */
    public List<Integer> accessibleThreadIds(Caller caller) {
        if (caller.is(AuthorizationService.ADMIN)) {
            return List.of();
        }
        List<Object[]> rows = memberRepository.findActiveThreadPatientsForUser(caller.systemId());
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<String> allowed = actablePatients(caller);
        List<Integer> ids = new ArrayList<>();
        for (Object[] row : rows) {
            if (allowed.contains((String) row[1])) {
                ids.add((Integer) row[0]);
            }
        }
        return ids;
    }

    /**
     * Users who may be members of a conversation about the patient, keyed by system id, in a stable order:
     * the patient, related doctors, then caregivers with an approved MESSAGING grant. Inactive users are left out.
     */
    public Map<String, User> eligibleMembers(String patientSystemId) {
        List<String> doctorIds = relationshipService.getRelatedDoctorsForPatient(patientSystemId);
        List<String> caregiverIds = dependentAccessRepository.findAllCaregiversForDependent(patientSystemId)
                .stream()
                .filter(PatientPermission.MESSAGING::isGrantedBy)
                .map(DependentAccess::getCaregiverSystemId)
                .toList();

        Set<String> all = new LinkedHashSet<>();
        all.add(patientSystemId);
        all.addAll(doctorIds);
        all.addAll(caregiverIds);
        Map<String, User> users = usersById(all);

        Map<String, User> eligible = new LinkedHashMap<>();
        addIfRole(eligible, users.get(patientSystemId), AuthorizationService.PATIENT);
        doctorIds.forEach(id -> addIfRole(eligible, users.get(id), AuthorizationService.DOCTOR));
        caregiverIds.forEach(id -> addIfRole(eligible, users.get(id), AuthorizationService.CAREGIVER));
        return eligible;
    }

    /** Batch user lookup by system id. */
    public Map<String, User> usersById(Collection<String> systemIds) {
        Set<String> ids = systemIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return new HashMap<>();
        }
        return userRepository.findBySystemIdIn(ids).stream()
                .collect(Collectors.toMap(User::getSystemId, Function.identity(), (a, b) -> a, HashMap::new));
    }

    private static void addIfRole(Map<String, User> target, User user, String role) {
        if (user != null && Boolean.TRUE.equals(user.getIsActive()) && role.equalsIgnoreCase(user.getRole())) {
            target.putIfAbsent(user.getSystemId(), user);
        }
    }
}
