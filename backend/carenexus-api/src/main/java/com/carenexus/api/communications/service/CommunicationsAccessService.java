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
 * All messaging access rules (contract 7.1, be-communications-hardening section 4), checked on EVERY request:
 * - ADMIN never reads or sends in a PRIVATE or CARE_TEAM conversation and has no conversation list, recipients or
 *   thread creation; an administrator may read and post only in SUPPORT conversations, as an active member
 *   (member_role ADMIN, joined through SupportThreadService on the first request).
 * - Clinical thread access = active membership AND the caller can still act for the thread's patient now
 *   (PATIENT self, DOCTOR with a care relationship, CAREGIVER with an approved MESSAGING grant).
 * - SUPPORT thread access = active membership: the opener (no patient context, no grant needed) or a joined
 *   administrator.
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

    /**
     * Caller from the JWT, any role. Administrators pass here; {@link #requireThreadAccess} refuses them on every
     * conversation that is not a SUPPORT conversation, and the routes without a thread use
     * {@link #requireMessagingCaller()}.
     */
    public Caller requireCaller() {
        return caller();
    }

    /**
     * Caller from the JWT for the routes that have no thread (recipients, conversation list, create): ADMIN is
     * rejected with 403 "Administrators cannot access conversations".
     */
    public Caller requireMessagingCaller() {
        Caller caller = caller();
        if (caller.is(AuthorizationService.ADMIN)) {
            throw new AuthorizationException(ADMIN_DENIED);
        }
        return caller;
    }

    /** The thread, or 404 "Conversation not found". No access check. */
    public MessageThread requireThread(Integer threadId) {
        if (threadId == null) {
            throw new ResourceNotFoundException(THREAD_NOT_FOUND);
        }
        return threadRepository.findById(threadId)
                .orElseThrow(() -> new ResourceNotFoundException(THREAD_NOT_FOUND));
    }

    /** Caller can act for the patient (audited denial, 403). */
    public void requireCanActFor(String patientSystemId) {
        authorizationService.requirePatientAccess(patientSystemId, PatientPermission.MESSAGING);
    }

    /**
     * 404 "Conversation not found" for an unknown id. ADMIN: 403 "Administrators cannot access conversations" on a
     * PRIVATE or CARE_TEAM conversation; on a SUPPORT conversation an active ADMIN membership is required (the join
     * happens before, in its own transaction). Everyone else: 403 "You no longer have access to this conversation"
     * when the caller is not an active member, or (clinical conversations) can no longer act for the patient, or
     * (SUPPORT conversations) is not the opener. Every denial is audited (ACCESS_DENIED).
     */
    public ThreadAccess requireThreadAccess(Caller caller, Integer threadId) {
        MessageThread thread = requireThread(threadId);
        boolean admin = caller.is(AuthorizationService.ADMIN);
        if (admin && !thread.isSupport()) {
            auditDenied(caller, thread, "Administrators cannot access clinical conversations");
            throw new AuthorizationException(ADMIN_DENIED, RESOURCE_THREAD, threadId);
        }
        ThreadMember member = memberRepository.findByThreadIdAndSystemId(threadId, caller.systemId())
                .filter(ThreadMember::isActive)
                .orElse(null);
        if (member == null) {
            auditDenied(caller, thread, "Not an active member of the conversation");
            throw new AuthorizationException(THREAD_DENIED, RESOURCE_THREAD, threadId);
        }
        if (thread.isSupport()) {
            boolean allowed = admin
                    ? ThreadMember.ROLE_ADMIN.equals(member.getMemberRole())
                    : caller.systemId().equals(thread.getCreatedBySystemId());
            if (!allowed) {
                auditDenied(caller, thread, "Not a participant of the support conversation");
                throw new AuthorizationException(THREAD_DENIED, RESOURCE_THREAD, threadId);
            }
            return new ThreadAccess(thread, member);
        }
        try {
            authorizationService.requirePatientAccess(thread.getPatientSystemId(), PatientPermission.MESSAGING, threadId);
        } catch (AuthorizationException denied) {
            // requirePatientAccess already wrote the ACCESS_DENIED audit row
            throw new AuthorizationException(THREAD_DENIED, RESOURCE_THREAD, threadId);
        }
        return new ThreadAccess(thread, member);
    }

    private void auditDenied(Caller caller, MessageThread thread, String reason) {
        try {
            auditService.logAccessDenied(caller.systemId(), RESOURCE_THREAD, thread.getThreadId(),
                    thread.getPatientSystemId(), null, reason);
        } catch (RuntimeException auditFailure) {
            log.error("Failed to write ACCESS_DENIED audit row: {}", auditFailure.getClass().getName());
        }
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
     *
     * A conversation without a patient ({@code patientSystemId == null}) is a SUPPORT conversation, whose only
     * members are its opener and the administrators who joined it: the opener can always act (no grant is
     * involved). Administrators are reported as not acting through this path on purpose: they are notified of the
     * opener's messages through NotificationPublisher (one notification per thread and hour), never once per message,
     * and one administrator's answer does not notify the others.
     */
    public boolean memberCanActFor(String systemId, String memberRole, String patientSystemId) {
        if (systemId == null || memberRole == null) {
            return false;
        }
        String role = memberRole.toUpperCase(Locale.ROOT);
        if (patientSystemId == null) {
            return !AuthorizationService.ADMIN.equals(role);
        }
        return switch (role) {
            case AuthorizationService.PATIENT -> systemId.equals(patientSystemId);
            case AuthorizationService.DOCTOR -> relationshipService.isDoctorAssignedToPatient(systemId, patientSystemId);
            case AuthorizationService.CAREGIVER ->
                    authorizationService.hasCaregiverPermission(systemId, patientSystemId, PatientPermission.MESSAGING);
            default -> false;
        };
    }

    /**
     * Threads the caller may access now: active membership AND either the caller can act for the thread's patient
     * (PRIVATE, CARE_TEAM) or the thread has no patient (SUPPORT: the opener or a joined administrator). For ADMIN
     * only SUPPORT threads.
     */
    public List<Integer> accessibleThreadIds(Caller caller) {
        List<Object[]> rows = memberRepository.findActiveThreadPatientsForUser(caller.systemId());
        if (rows.isEmpty()) {
            return List.of();
        }
        boolean admin = caller.is(AuthorizationService.ADMIN);
        Set<String> allowed = admin ? Set.of() : actablePatients(caller);
        List<Integer> ids = new ArrayList<>();
        for (Object[] row : rows) {
            String patient = (String) row[1];
            if (patient == null || allowed.contains(patient)) {
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
