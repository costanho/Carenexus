package com.carenexus.api.communications.service;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.exception.AuthorizationException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.communications.dto.request.CreateSupportThreadRequest;
import com.carenexus.api.communications.dto.response.LastMessageResponse;
import com.carenexus.api.communications.dto.response.SupportThreadResponse;
import com.carenexus.api.communications.dto.response.ThreadResponse;
import com.carenexus.api.communications.model.Message;
import com.carenexus.api.communications.model.MessageThread;
import com.carenexus.api.communications.model.ThreadMember;
import com.carenexus.api.communications.notify.NotificationCategory;
import com.carenexus.api.communications.notify.NotificationCommand;
import com.carenexus.api.communications.notify.NotificationPriority;
import com.carenexus.api.communications.notify.NotificationPublisher;
import com.carenexus.api.communications.notify.NotificationTarget;
import com.carenexus.api.communications.notify.NotificationType;
import com.carenexus.api.communications.repository.CommunicationUserRepository;
import com.carenexus.api.communications.repository.MessageRepository;
import com.carenexus.api.communications.repository.MessageThreadRepository;
import com.carenexus.api.communications.repository.ThreadMemberRepository;
import com.carenexus.api.communications.service.CommunicationsAccessService.Caller;
import com.carenexus.api.communications.service.ThreadService.ThreadResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Support conversations (conversation type SUPPORT, be-communications-hardening section 5).
 *
 * <ul>
 *   <li>A patient, caregiver or doctor has at most one OPEN support conversation (V65 partial unique index); opening
 *       returns it or creates it, with the caller as its only member. No patient context, no caregiver grant.</li>
 *   <li>Administrators answer as members with member_role ADMIN. An administrator joins on the first request to a
 *       conversation route ({@link #joinIfAdministrator}); listing and closing do not join.</li>
 *   <li>A message from the opener notifies every active administrator through {@link NotificationPublisher}
 *       (one per administrator, thread and clinic clock hour); an administrator's message notifies the opener through
 *       the existing message notification path of MessageService.</li>
 *   <li>Closing is final: a closed conversation stays readable and refuses new messages (409); the opener can open a
 *       new one.</li>
 * </ul>
 * Audited with resource type SUPPORT_THREAD (no patient): CREATE on open, UPDATE on join and close, READ per inbox page.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class SupportThreadService {

    public static final String RESOURCE = "SUPPORT_THREAD";
    public static final String ADMIN_CANNOT_OPEN = "Administrators cannot open a support conversation";
    public static final String CLOSED = "This support conversation is closed";
    static final String ADMIN_ONLY = "You don't have permission to access this resource";
    /** Largest page of the administrator inbox. */
    static final int MAX_PAGE_SIZE = 100;
    static final String DEDUPE_PREFIX = "SUPPORT_MESSAGE:";
    private static final DateTimeFormatter CLOCK_HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH");
    private static final Set<String> OPENER_ROLES =
            Set.of(AuthorizationService.PATIENT, AuthorizationService.DOCTOR, AuthorizationService.CAREGIVER);

    private final CommunicationsAccessService accessService;
    private final ThreadService threadService;
    private final MessageThreadRepository threadRepository;
    private final ThreadMemberRepository memberRepository;
    private final MessageRepository messageRepository;
    private final CommunicationUserRepository userRepository;
    private final NotificationPublisher notificationPublisher;
    private final AuditService auditService;
    private final Clock clock;

    /** What the send route needs to know about a thread before delegating to MessageService. */
    public record SendContext(boolean support, boolean closed) {
    }

    // ==================== OPEN (POST /api/communications/support-thread) ====================

    /**
     * The caller's open support conversation (200, archive flag cleared), or a new one (201). ADMIN: 403. A
     * concurrent first call that loses the unique-index race throws DataIntegrityViolationException from the insert;
     * the controller then answers with {@link #findOpenOwn()}.
     */
    public ThreadResult openOrGet(CreateSupportThreadRequest request) {
        Caller caller = accessService.caller();
        if (caller.is(AuthorizationService.ADMIN)) {
            throw new AuthorizationException(ADMIN_CANNOT_OPEN);
        }
        if (!OPENER_ROLES.contains(caller.role())) {
            throw new AuthorizationException(ADMIN_ONLY);
        }

        Optional<MessageThread> existing = threadRepository.findOpenSupportThread(caller.systemId());
        if (existing.isPresent()) {
            MessageThread thread = existing.get();
            memberRepository.findByThreadIdAndSystemId(thread.getThreadId(), caller.systemId())
                    .filter(member -> member.getArchivedAt() != null)
                    .ifPresent(member -> {
                        member.setArchivedAt(null);
                        memberRepository.save(member);
                    });
            return new ThreadResult(threadService.toResponse(caller, thread), false);
        }

        String subject = request != null && request.getSubject() != null && !request.getSubject().isBlank()
                ? request.getSubject().trim() : null;
        MessageThread thread = threadRepository.saveAndFlush(MessageThread.builder()
                .patientSystemId(null)
                .subject(subject)
                .threadType(MessageThread.THREAD_TYPE_PATIENT_SUPPORT)
                .conversationType(MessageThread.SUPPORT)
                .createdBySystemId(caller.systemId())
                .isArchived(false)
                .build());
        memberRepository.saveAndFlush(ThreadMember.builder()
                .threadId(thread.getThreadId())
                .systemId(caller.systemId())
                .memberRole(caller.role())
                .joinedAt(Instant.now(clock))
                .historyStartMessageId(0)
                .lastReadMessageId(0)
                .addedBySystemId(caller.systemId())
                .build());
        auditService.logClinicalAccess("CREATE", RESOURCE, thread.getThreadId(), null);
        return new ThreadResult(threadService.toResponse(caller, thread), true);
    }

    /** The caller's open support conversation, looked up in a new transaction after a lost creation race. */
    @Transactional(readOnly = true)
    public Optional<ThreadResponse> findOpenOwn() {
        Caller caller = accessService.caller();
        return threadRepository.findOpenSupportThread(caller.systemId())
                .map(thread -> threadService.toResponse(caller, thread));
    }

    // ==================== ADMINISTRATOR PARTICIPATION ====================

    /**
     * Makes the calling administrator an active member (role ADMIN, full history, read position 0) of a SUPPORT
     * conversation the first time they open it. Does nothing for other roles, unknown ids and clinical
     * conversations: the conversation route that follows answers 404 / 403 for those. Audited (UPDATE).
     *
     * @return true when the administrator joined with this call
     */
    public boolean joinIfAdministrator(Integer threadId) {
        Caller caller = accessService.caller();
        if (!caller.is(AuthorizationService.ADMIN) || threadId == null) {
            return false;
        }
        MessageThread thread = threadRepository.findById(threadId).orElse(null);
        if (thread == null || !thread.isSupport()) {
            return false;
        }
        Optional<ThreadMember> existing = memberRepository.findByThreadIdAndSystemId(threadId, caller.systemId());
        if (existing.isPresent() && existing.get().isActive()) {
            return false;
        }
        ThreadMember member = existing.orElseGet(() -> ThreadMember.builder()
                .threadId(threadId)
                .systemId(caller.systemId())
                .build());
        member.setMemberRole(ThreadMember.ROLE_ADMIN);
        member.setJoinedAt(Instant.now(clock));
        member.setHistoryStartMessageId(0);
        member.setLastReadMessageId(member.getLastReadMessageId() != null ? member.getLastReadMessageId() : 0);
        member.setAddedBySystemId(caller.systemId());
        member.setLeftAt(null);
        member.setRemovedAt(null);
        member.setRemovedBySystemId(null);
        member.setRemovalReason(null);
        member.setArchivedAt(null);
        memberRepository.saveAndFlush(member);
        auditService.logClinicalAccess("UPDATE", RESOURCE, threadId, null);
        return true;
    }

    /**
     * Whether the thread is a SUPPORT conversation and closed. No access check (the message route checks access
     * before it answers anything that depends on this); an unknown id is reported as not support.
     */
    @Transactional(readOnly = true)
    public SendContext sendContext(Integer threadId) {
        MessageThread thread = threadId == null ? null : threadRepository.findById(threadId).orElse(null);
        return new SendContext(thread != null && thread.isSupport(), thread != null && thread.isClosed());
    }

    /**
     * After a stored (201) message: when the sender is the opener of the SUPPORT conversation, every active
     * administrator is notified through the publisher (deduplicated per administrator, thread and clinic clock hour;
     * suppressed by the publisher for administrators who switched message notifications off). Text never contains the
     * subject or the message body.
     *
     * @return number of notifications stored
     */
    public int notifyAdministrators(Integer threadId, String senderSystemId) {
        MessageThread thread = threadId == null ? null : threadRepository.findById(threadId).orElse(null);
        if (thread == null || !thread.isSupport() || senderSystemId == null
                || !senderSystemId.equals(thread.getCreatedBySystemId())) {
            return 0;
        }
        User opener = accessService.usersById(List.of(senderSystemId)).get(senderSystemId);
        String openerName = CommunicationLabels.name(opener, senderSystemId);
        String dedupeKey = DEDUPE_PREFIX + threadId + ":" + LocalDateTime.now(clock).format(CLOCK_HOUR);
        NotificationCommand template = NotificationCommand.builder()
                .type(NotificationType.MESSAGE)
                .category(NotificationCategory.MESSAGE)
                .priority(NotificationPriority.NORMAL)
                .title("New support message")
                .message(openerName + " sent a message to support")
                .target(NotificationTarget.MESSAGES)
                .resourceType(RESOURCE)
                .resourceId(threadId)
                .actorSystemId(senderSystemId)
                .dedupeKey(dedupeKey)
                .build();
        int stored = 0;
        for (String adminSystemId : userRepository.findActiveAdminSystemIds()) {
            if (notificationPublisher.publish(template.toBuilder().recipientSystemId(adminSystemId).build()).isPresent()) {
                stored++;
            }
        }
        return stored;
    }

    /**
     * POST /threads/{id}/read by an administrator: their SUPPORT_THREAD notifications of this conversation (which
     * carry no thread id) are marked read as well. Call it after the read position was stored.
     */
    public void markAdministratorNotificationsRead(Integer threadId) {
        Caller caller = accessService.caller();
        if (!caller.is(AuthorizationService.ADMIN) || threadId == null) {
            return;
        }
        threadRepository.markSupportNotificationsRead(caller.systemId(), threadId, LocalDateTime.now(clock));
    }

    // ==================== ADMINISTRATOR INBOX ====================

    /**
     * Every SUPPORT conversation with at least one message, newest activity first (ADMIN only). Listing does not join
     * and does not move any read position. Audited: one READ SUPPORT_THREAD row per request.
     *
     * @param open null = open and closed, true = only open, false = only closed
     */
    @Transactional(readOnly = true)
    public PageResponse<SupportThreadResponse> listForAdministrators(Boolean open, int page, int size) {
        Caller caller = requireAdministrator();
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int openFilter = open == null ? 0 : open ? 1 : 2;
        Page<MessageThread> threads = threadRepository.findSupportInbox(openFilter, PageRequest.of(safePage, safeSize));
        auditService.logClinicalAccess("READ", RESOURCE, null, null);
        return PageResponse.of(threads, toSupportResponses(caller, threads.getContent()));
    }

    /**
     * Closes a SUPPORT conversation (ADMIN only, member or not). Closing a closed conversation changes nothing and
     * answers its current state. 404 for unknown ids and for clinical conversations. Audited (UPDATE) only on change.
     */
    public SupportThreadResponse close(Integer threadId) {
        Caller caller = requireAdministrator();
        MessageThread thread = threadId == null ? null : threadRepository.findForUpdateById(threadId).orElse(null);
        if (thread == null || !thread.isSupport()) {
            throw new ResourceNotFoundException(CommunicationsAccessService.THREAD_NOT_FOUND);
        }
        if (thread.getClosedAt() == null) {
            LocalDateTime now = LocalDateTime.now(clock);
            thread.setClosedAt(now);
            thread.setClosedBySystemId(caller.systemId());
            thread.setUpdatedAt(now);
            threadRepository.save(thread);
            auditService.logClinicalAccess("UPDATE", RESOURCE, threadId, null);
        }
        return toSupportResponses(caller, List.of(thread)).get(0);
    }

    // ==================== HELPERS ====================

    private Caller requireAdministrator() {
        Caller caller = accessService.caller();
        if (!caller.is(AuthorizationService.ADMIN)) {
            throw new AuthorizationException(ADMIN_ONLY);
        }
        return caller;
    }

    /** Batch assembly: openers, last messages, senders and unread flags are loaded once per page. */
    private List<SupportThreadResponse> toSupportResponses(Caller admin, List<MessageThread> threads) {
        if (threads.isEmpty()) {
            return List.of();
        }
        List<Integer> ids = threads.stream().map(MessageThread::getThreadId).toList();
        List<Integer> lastIds = threads.stream().map(MessageThread::getLastMessageId).filter(Objects::nonNull).toList();
        Map<Integer, Message> lastByThread = lastIds.isEmpty() ? Map.of()
                : messageRepository.findAllById(lastIds).stream()
                .collect(Collectors.toMap(Message::getThreadId, Function.identity(), (a, b) -> a));
        Set<Integer> unread = new HashSet<>(threadRepository.findUnreadSupportThreadIds(admin.systemId(), ids));

        Set<String> userIds = new HashSet<>();
        threads.forEach(t -> userIds.add(t.getCreatedBySystemId()));
        lastByThread.values().forEach(m -> userIds.add(m.getSenderSystemId()));
        Map<String, User> users = accessService.usersById(userIds);

        List<SupportThreadResponse> responses = new ArrayList<>(threads.size());
        for (MessageThread thread : threads) {
            String openerId = thread.getCreatedBySystemId();
            User opener = users.get(openerId);
            Message last = lastByThread.get(thread.getThreadId());
            LastMessageResponse lastResponse = null;
            if (last != null && !Boolean.TRUE.equals(last.getIsDeleted())) {
                lastResponse = LastMessageResponse.builder()
                        .messageId(last.getMessageId())
                        .senderSystemId(last.getSenderSystemId())
                        .senderLabel(CommunicationLabels.label(users.get(last.getSenderSystemId()),
                                last.getSenderSystemId(), null))
                        .preview(CommunicationLabels.preview(last.getBody()))
                        .urgent(false)
                        .sentAt(CommunicationLabels.toInstant(last.getSentAt()))
                        .build();
            }
            responses.add(SupportThreadResponse.builder()
                    .threadId(thread.getThreadId())
                    .subject(thread.getSubject())
                    .openerSystemId(openerId)
                    .openerName(CommunicationLabels.name(opener, openerId))
                    .openerRole(CommunicationLabels.role(opener))
                    .open(thread.getClosedAt() == null)
                    .closedAt(CommunicationLabels.toInstant(thread.getClosedAt()))
                    .lastMessage(lastResponse)
                    .lastMessageAt(lastResponse != null ? lastResponse.getSentAt()
                            : CommunicationLabels.toInstant(thread.getLastMessageAt()))
                    .createdAt(CommunicationLabels.toInstant(thread.getCreatedAt()))
                    .unread(unread.contains(thread.getThreadId()))
                    .build());
        }
        return responses;
    }
}
