package com.carenexus.api.communications.service;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.exception.ConflictException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.communications.dto.request.AddThreadMembersRequest;
import com.carenexus.api.communications.dto.request.ArchiveThreadRequest;
import com.carenexus.api.communications.dto.request.CreateThreadRequest;
import com.carenexus.api.communications.dto.response.LastMessageResponse;
import com.carenexus.api.communications.dto.response.PageResponse;
import com.carenexus.api.communications.dto.response.ThreadMemberResponse;
import com.carenexus.api.communications.dto.response.ThreadResponse;
import com.carenexus.api.communications.model.Message;
import com.carenexus.api.communications.model.MessageThread;
import com.carenexus.api.communications.model.ThreadMember;
import com.carenexus.api.communications.repository.MessageRepository;
import com.carenexus.api.communications.repository.MessageThreadRepository;
import com.carenexus.api.communications.repository.NotificationRepository;
import com.carenexus.api.communications.repository.ThreadMemberRepository;
import com.carenexus.api.communications.service.CommunicationsAccessService.Caller;
import com.carenexus.api.communications.service.CommunicationsAccessService.ThreadAccess;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Conversations: list, create (PRIVATE / CARE_TEAM), membership, per-user archive (contract 7.3).
 * Every method re-checks access through {@link CommunicationsAccessService}.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ThreadService {

    static final Pattern CLIENT_ID = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    static final String PRIVATE_SHAPE = "A private conversation is between a doctor and the patient or one of their caregivers";
    static final String NEEDS_DOCTOR = "A care team conversation needs at least one doctor";
    static final String PRIVATE_MEMBERS = "Members of a private conversation cannot be changed";
    static final String ONLY_PATIENT_OR_DOCTOR = "Only the patient or a doctor can change members";
    static final String PATIENT_NOT_REMOVABLE = "The patient cannot be removed from their care team conversation";

    private final CommunicationsAccessService accessService;
    private final MessageThreadRepository threadRepository;
    private final ThreadMemberRepository memberRepository;
    private final MessageRepository messageRepository;
    private final NotificationRepository notificationRepository;
    private final AuditService auditService;

    /** Thread plus whether it was created by this call (201) or an existing one was returned (200). */
    public record ThreadResult(ThreadResponse thread, boolean created) {
    }

    // ==================== READ ====================

    @Transactional(readOnly = true)
    public PageResponse<ThreadResponse> listThreads(String patientSystemId, boolean archived, int page, int size) {
        Caller caller = accessService.requireCaller();
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 50);

        Set<String> allowed = accessService.actablePatients(caller);
        String filter = patientSystemId != null && !patientSystemId.isBlank() ? patientSystemId.trim() : null;
        if (filter != null) {
            allowed = allowed.contains(filter) ? Set.of(filter) : Set.of();
        }
        if (allowed.isEmpty()) {
            return PageResponse.empty(safePage, safeSize);
        }
        Page<MessageThread> threads = threadRepository.findForMember(caller.systemId(), allowed, archived,
                PageRequest.of(safePage, safeSize));
        return PageResponse.of(threads, toResponses(caller, threads.getContent()));
    }

    @Transactional(readOnly = true)
    public ThreadResponse getThread(Integer threadId) {
        Caller caller = accessService.requireCaller();
        ThreadAccess access = accessService.requireThreadAccess(caller, threadId);
        return toResponse(caller, access.thread());
    }

    // ==================== CREATE ====================

    public ThreadResult createThread(CreateThreadRequest request) {
        Caller caller = accessService.requireCaller();
        if (request == null) {
            throw new RuntimeException("Request body is required");
        }
        String patientSystemId = RecipientService.resolvePatient(caller, request.getPatientSystemId());
        String type = request.getType() != null ? request.getType().trim().toUpperCase(Locale.ROOT) : "";
        if (!MessageThread.PRIVATE.equals(type) && !MessageThread.CARE_TEAM.equals(type)) {
            throw new RuntimeException("Conversation type must be PRIVATE or CARE_TEAM");
        }
        String subject = request.getSubject() != null && !request.getSubject().isBlank() ? request.getSubject().trim() : null;
        if (subject != null && subject.length() > 200) {
            throw new RuntimeException("Subject must be at most 200 characters");
        }
        String clientThreadId = request.getClientThreadId() != null && !request.getClientThreadId().isBlank()
                ? request.getClientThreadId().trim() : null;
        if (clientThreadId != null && !CLIENT_ID.matcher(clientThreadId).matches()) {
            throw new RuntimeException("clientThreadId must be 8-64 letters, digits, '-' or '_'");
        }

        accessService.requireCanActFor(patientSystemId);

        if (clientThreadId != null) {
            Optional<MessageThread> retried = threadRepository.findByCreatedBySystemIdAndClientThreadId(caller.systemId(), clientThreadId);
            if (retried.isPresent()) {
                ThreadAccess access = accessService.requireThreadAccess(caller, retried.get().getThreadId());
                return new ThreadResult(toResponse(caller, access.thread()), false);
            }
        }

        Map<String, User> eligible = accessService.eligibleMembers(patientSystemId);
        if (!eligible.containsKey(caller.systemId())) {
            throw new AuthorizationException("You cannot start a conversation about this patient");
        }
        Set<String> requested = new LinkedHashSet<>();
        if (request.getMemberSystemIds() != null) {
            request.getMemberSystemIds().stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(id -> !id.isEmpty() && !id.equals(caller.systemId()))
                    .forEach(requested::add);
        }

        Set<String> memberIds = new LinkedHashSet<>();
        memberIds.add(caller.systemId());
        String legacyType;
        if (MessageThread.PRIVATE.equals(type)) {
            if (requested.size() != 1) {
                throw new RuntimeException(PRIVATE_SHAPE);
            }
            String otherId = requested.iterator().next();
            requireEligible(eligible, otherId);
            String otherRole = CommunicationLabels.role(eligible.get(otherId));
            if (!RecipientService.isPrivatePairAllowed(caller.role(), otherRole)) {
                throw new RuntimeException(PRIVATE_SHAPE);
            }
            List<MessageThread> existing = threadRepository.findPrivateThreadsBetween(patientSystemId, caller.systemId(), otherId);
            if (!existing.isEmpty()) {
                return new ThreadResult(toResponse(caller, existing.get(0)), false);
            }
            memberIds.add(otherId);
            boolean withCaregiver = AuthorizationService.CAREGIVER.equals(otherRole)
                    || caller.is(AuthorizationService.CAREGIVER);
            legacyType = withCaregiver ? "CAREGIVER_DOCTOR" : "PATIENT_DOCTOR";
        } else {
            memberIds.add(patientSystemId);
            memberIds.addAll(requested);
            for (String id : memberIds) {
                requireEligible(eligible, id);
            }
            boolean hasDoctor = memberIds.stream()
                    .anyMatch(id -> AuthorizationService.DOCTOR.equals(CommunicationLabels.role(eligible.get(id))));
            if (!hasDoctor) {
                throw new RuntimeException(NEEDS_DOCTOR);
            }
            legacyType = MessageThread.CARE_TEAM;
        }

        MessageThread thread = threadRepository.save(MessageThread.builder()
                .patientSystemId(patientSystemId)
                .subject(subject)
                .threadType(legacyType)
                .conversationType(type)
                .createdBySystemId(caller.systemId())
                .clientThreadId(clientThreadId)
                .isArchived(false)
                .build());

        Instant now = Instant.now();
        List<ThreadMember> members = memberIds.stream()
                .map(id -> ThreadMember.builder()
                        .threadId(thread.getThreadId())
                        .systemId(id)
                        .memberRole(CommunicationLabels.role(eligible.get(id)))
                        .joinedAt(now)
                        .historyStartMessageId(0)
                        .lastReadMessageId(0)
                        .addedBySystemId(caller.systemId())
                        .build())
                .toList();
        memberRepository.saveAll(members);

        auditService.logClinicalAccess("CREATE", CommunicationsAccessService.RESOURCE_THREAD, thread.getThreadId(), patientSystemId);
        return new ThreadResult(toResponse(caller, thread), true);
    }

    // ==================== MEMBERS ====================

    public ThreadResponse addMembers(Integer threadId, AddThreadMembersRequest request) {
        Caller caller = accessService.requireCaller();
        ThreadAccess access = accessService.requireThreadAccess(caller, threadId);
        MessageThread thread = access.thread();
        if (!thread.isCareTeam()) {
            throw new ConflictException(PRIVATE_MEMBERS);
        }
        if (!caller.canManageMembers()) {
            throw new AuthorizationException(ONLY_PATIENT_OR_DOCTOR);
        }
        Set<String> ids = new LinkedHashSet<>();
        if (request != null && request.getSystemIds() != null) {
            request.getSystemIds().stream().filter(Objects::nonNull).map(String::trim)
                    .filter(id -> !id.isEmpty()).forEach(ids::add);
        }
        if (ids.isEmpty()) {
            throw new RuntimeException("Choose at least one person to add");
        }
        Map<String, User> eligible = accessService.eligibleMembers(thread.getPatientSystemId());
        for (String id : ids) {
            requireEligible(eligible, id);
        }

        // Join-time history boundary: new members only see messages sent after they were added.
        int boundary = Optional.ofNullable(messageRepository.findMaxMessageId(threadId)).orElse(0);
        Instant now = Instant.now();
        List<ThreadMember> changed = new ArrayList<>();
        for (String id : ids) {
            Optional<ThreadMember> existing = memberRepository.findByThreadIdAndSystemId(threadId, id);
            if (existing.isPresent() && existing.get().isActive()) {
                continue;
            }
            ThreadMember member = existing.orElseGet(() -> ThreadMember.builder().threadId(threadId).systemId(id).build());
            member.setMemberRole(CommunicationLabels.role(eligible.get(id)));
            member.setJoinedAt(now);
            member.setHistoryStartMessageId(boundary);
            member.setLastReadMessageId(boundary);
            member.setAddedBySystemId(caller.systemId());
            member.setLeftAt(null);
            member.setRemovedAt(null);
            member.setRemovedBySystemId(null);
            member.setRemovalReason(null);
            member.setArchivedAt(null);
            changed.add(member);
        }
        if (!changed.isEmpty()) {
            memberRepository.saveAll(changed);
            thread.setUpdatedAt(LocalDateTime.now());
            threadRepository.save(thread);
            auditService.logClinicalAccess("UPDATE", CommunicationsAccessService.RESOURCE_THREAD, threadId, thread.getPatientSystemId());
        }
        return toResponse(caller, thread);
    }

    public void removeMember(Integer threadId, String targetSystemId) {
        Caller caller = accessService.requireCaller();
        ThreadAccess access = accessService.requireThreadAccess(caller, threadId);
        MessageThread thread = access.thread();
        if (!thread.isCareTeam()) {
            throw new ConflictException(PRIVATE_MEMBERS);
        }
        String target = targetSystemId != null ? targetSystemId.trim() : "";
        if (target.equals(thread.getPatientSystemId())) {
            throw new ConflictException(PATIENT_NOT_REMOVABLE);
        }
        boolean self = target.equals(caller.systemId());
        if (!self && !caller.canManageMembers()) {
            throw new AuthorizationException(ONLY_PATIENT_OR_DOCTOR);
        }
        ThreadMember member = self ? access.member() : memberRepository.findByThreadIdAndSystemId(threadId, target)
                .orElseThrow(() -> new ResourceNotFoundException("Member not found"));
        if (!member.isActive()) {
            return; // already gone: idempotent
        }
        if (AuthorizationService.DOCTOR.equals(member.getMemberRole())) {
            long otherDoctors = memberRepository.findActiveByThreadId(threadId).stream()
                    .filter(m -> AuthorizationService.DOCTOR.equals(m.getMemberRole()))
                    .filter(m -> !m.getSystemId().equals(target))
                    .count();
            if (otherDoctors == 0) {
                throw new ConflictException(NEEDS_DOCTOR);
            }
        }
        Instant now = Instant.now();
        if (self) {
            member.setLeftAt(now);
            member.setRemovalReason(ThreadMember.REASON_LEFT);
        } else {
            member.setRemovedAt(now);
            member.setRemovedBySystemId(caller.systemId());
            member.setRemovalReason(ThreadMember.REASON_REMOVED_BY_MEMBER);
        }
        memberRepository.save(member);
        notificationRepository.markThreadsRead(target, List.of(threadId), LocalDateTime.now());
        thread.setUpdatedAt(LocalDateTime.now());
        threadRepository.save(thread);
        auditService.logClinicalAccess("UPDATE", CommunicationsAccessService.RESOURCE_THREAD, threadId, thread.getPatientSystemId());
    }

    public void setArchived(Integer threadId, ArchiveThreadRequest request) {
        Caller caller = accessService.requireCaller();
        ThreadAccess access = accessService.requireThreadAccess(caller, threadId);
        if (request == null || request.getArchived() == null) {
            throw new RuntimeException("archived must be true or false");
        }
        ThreadMember member = access.member();
        member.setArchivedAt(request.getArchived() ? Instant.now() : null);
        memberRepository.save(member);
    }

    /**
     * Caregiver lost the patient's approved MESSAGING permission (or the grant was revoked): every active
     * membership of the caregiver in conversations about this patient is ended with reason ACCESS_REVOKED and the
     * caregiver's unread notifications for those conversations are marked read. Not reversed when access returns.
     *
     * @return number of memberships removed
     */
    public int removeCaregiverAfterAccessChange(String caregiverSystemId, String patientSystemId) {
        List<ThreadMember> memberships = memberRepository.findActiveByUserAndPatient(caregiverSystemId, patientSystemId);
        if (memberships.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        for (ThreadMember member : memberships) {
            member.setRemovedAt(now);
            member.setRemovedBySystemId(null);
            member.setRemovalReason(ThreadMember.REASON_ACCESS_REVOKED);
            member.setArchivedAt(null);
        }
        memberRepository.saveAll(memberships);
        List<Integer> threadIds = memberships.stream().map(ThreadMember::getThreadId).distinct().toList();
        notificationRepository.markThreadsRead(caregiverSystemId, threadIds, LocalDateTime.now());
        return memberships.size();
    }

    // ==================== RESPONSE ASSEMBLY ====================

    public ThreadResponse toResponse(Caller caller, MessageThread thread) {
        return toResponses(caller, List.of(thread)).get(0);
    }

    /**
     * Batch assembly (no per-thread queries): members, users, unread stats and last visible message are loaded
     * once for the whole page. Boundary rules come from the caller's own membership rows.
     */
    public List<ThreadResponse> toResponses(Caller caller, List<MessageThread> threads) {
        if (threads.isEmpty()) {
            return List.of();
        }
        List<Integer> ids = threads.stream().map(MessageThread::getThreadId).toList();
        Map<Integer, List<ThreadMember>> membersByThread = memberRepository.findActiveByThreadIds(ids).stream()
                .collect(Collectors.groupingBy(ThreadMember::getThreadId, LinkedHashMap::new, Collectors.toList()));

        Map<Integer, long[]> unread = new HashMap<>();
        for (Object[] row : messageRepository.findUnreadStats(caller.systemId(), ids)) {
            long count = row[1] != null ? ((Number) row[1]).longValue() : 0L;
            long urgent = row[2] != null ? ((Number) row[2]).longValue() : 0L;
            unread.put((Integer) row[0], new long[]{count, urgent});
        }
        Map<Integer, Message> lastByThread = messageRepository.findLastVisible(caller.systemId(), ids).stream()
                .collect(Collectors.toMap(Message::getThreadId, m -> m, (a, b) -> a.getMessageId() > b.getMessageId() ? a : b));

        Set<String> userIds = new HashSet<>();
        threads.forEach(t -> userIds.add(t.getPatientSystemId()));
        membersByThread.values().forEach(list -> list.forEach(m -> userIds.add(m.getSystemId())));
        lastByThread.values().forEach(m -> userIds.add(m.getSenderSystemId()));
        Map<String, User> users = accessService.usersById(userIds);

        List<ThreadResponse> responses = new ArrayList<>();
        for (MessageThread thread : threads) {
            String patientName = CommunicationLabels.name(users.get(thread.getPatientSystemId()), thread.getPatientSystemId());
            List<ThreadMember> members = membersByThread.getOrDefault(thread.getThreadId(), List.of());
            ThreadMember mine = members.stream().filter(m -> m.getSystemId().equals(caller.systemId())).findFirst().orElse(null);

            List<ThreadMemberResponse> memberResponses = members.stream().map(m -> {
                User user = users.get(m.getSystemId());
                String name = CommunicationLabels.name(user, m.getSystemId());
                String role = m.getMemberRole();
                return ThreadMemberResponse.builder()
                        .systemId(m.getSystemId())
                        .name(name)
                        .role(role)
                        .label(CommunicationLabels.label(name, role, patientName))
                        .build();
            }).toList();

            Message last = lastByThread.get(thread.getThreadId());
            LastMessageResponse lastResponse = null;
            if (last != null) {
                User sender = users.get(last.getSenderSystemId());
                lastResponse = LastMessageResponse.builder()
                        .messageId(last.getMessageId())
                        .senderSystemId(last.getSenderSystemId())
                        .senderLabel(CommunicationLabels.label(sender, last.getSenderSystemId(), patientName))
                        .preview(CommunicationLabels.preview(last.getBody()))
                        .urgent(Boolean.TRUE.equals(last.getIsUrgent()))
                        .sentAt(CommunicationLabels.toInstant(last.getSentAt()))
                        .build();
            }
            long[] stats = unread.getOrDefault(thread.getThreadId(), new long[]{0L, 0L});

            responses.add(ThreadResponse.builder()
                    .threadId(thread.getThreadId())
                    .type(thread.getConversationType())
                    .subject(thread.getSubject())
                    .patientSystemId(thread.getPatientSystemId())
                    .patientName(patientName)
                    .members(memberResponses)
                    .lastMessage(lastResponse)
                    .unreadCount(stats[0])
                    .hasUrgentUnread(stats[1] > 0)
                    .archived(mine != null && mine.getArchivedAt() != null)
                    // the caller's newest visible message: never reveals activity before the caller joined
                    .lastMessageAt(lastResponse != null ? lastResponse.getSentAt() : null)
                    .createdAt(CommunicationLabels.toInstant(thread.getCreatedAt()))
                    .canManageMembers(thread.isCareTeam() && caller.canManageMembers())
                    .build());
        }
        return responses;
    }

    /**
     * 400 "{name} cannot be added to this conversation". The name is shown for doctors only; for anyone else the
     * text says "This person" so the endpoint cannot be used to look up names of arbitrary accounts.
     */
    private void requireEligible(Map<String, User> eligible, String systemId) {
        if (eligible.containsKey(systemId)) {
            return;
        }
        User user = accessService.usersById(List.of(systemId)).get(systemId);
        String name = user != null && AuthorizationService.DOCTOR.equals(CommunicationLabels.role(user))
                ? CommunicationLabels.label(user, systemId, null)
                : "This person";
        throw new RuntimeException(name + " cannot be added to this conversation");
    }
}
