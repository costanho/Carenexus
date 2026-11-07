package com.carenexus.api.communications.service;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.communications.dto.request.MarkThreadReadRequest;
import com.carenexus.api.communications.dto.request.SendMessageRequest;
import com.carenexus.api.communications.dto.response.MessageResponse;
import com.carenexus.api.communications.dto.response.ThreadMessagesResponse;
import com.carenexus.api.communications.dto.response.ThreadReadResponse;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Message history, retry-safe send and read position (contract 7.3). The sender is always the caller from the
 * JWT and the patient context always comes from the thread. Message bodies are never logged.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class MessageService {

    static final String RESOURCE_MESSAGE = "MESSAGE";

    private final CommunicationsAccessService accessService;
    private final MessageRepository messageRepository;
    private final MessageThreadRepository threadRepository;
    private final ThreadMemberRepository memberRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationService notificationService;
    private final AuditService auditService;

    @Value("${carenexus.messaging.max-body-length:4000}")
    private int maxBodyLength = 4000;

    /** Message plus whether it was stored by this call (201) or an earlier send with the same client id (200). */
    public record SendResult(MessageResponse message, boolean created) {
    }

    // ==================== HISTORY ====================

    @Transactional(readOnly = true)
    public ThreadMessagesResponse listMessages(Integer threadId, Integer before, Integer after, Integer limit) {
        Caller caller = accessService.requireCaller();
        ThreadAccess access = accessService.requireThreadAccess(caller, threadId);
        if (before != null && after != null) {
            throw new RuntimeException("Use either before or after, not both");
        }
        int size = limit == null ? 30 : Math.min(Math.max(limit, 1), 100);
        int boundary = access.member().historyStart();
        PageRequest probe = PageRequest.of(0, size + 1);

        List<Message> rows;
        boolean hasMore;
        if (after != null) {
            rows = new ArrayList<>(messageRepository.findVisibleAfter(threadId, Math.max(after, boundary), probe));
            hasMore = rows.size() > size;
            if (hasMore) {
                rows = rows.subList(0, size);
            }
        } else {
            rows = new ArrayList<>(before == null
                    ? messageRepository.findVisibleNewest(threadId, boundary, probe)
                    : messageRepository.findVisibleBefore(threadId, boundary, before, probe));
            hasMore = rows.size() > size;
            if (hasMore) {
                rows = new ArrayList<>(rows.subList(0, size));
            }
            Collections.reverse(rows);
        }

        MessageThread thread = access.thread();
        Map<String, User> users = usersFor(thread, rows.stream().map(Message::getSenderSystemId).toList());
        String patientName = CommunicationLabels.name(users.get(thread.getPatientSystemId()), thread.getPatientSystemId());
        List<MessageResponse> messages = rows.stream()
                .map(m -> toResponse(m, caller, users, patientName))
                .toList();

        if (after == null) {
            auditService.logClinicalAccess("READ", CommunicationsAccessService.RESOURCE_THREAD, threadId, thread.getPatientSystemId());
        }
        return ThreadMessagesResponse.builder()
                .threadId(threadId)
                .messages(messages)
                .hasMore(hasMore)
                .lastReadMessageId(access.member().lastRead())
                .build();
    }

    // ==================== SEND ====================

    /**
     * One transaction: message, thread pointers, sender read position, un-archive + notification for every other
     * active member who still passes the access rule, audit. A repeated clientMessageId returns the stored message.
     */
    public SendResult sendMessage(Integer threadId, SendMessageRequest request) {
        Caller caller = accessService.requireCaller();
        // Lock the thread row before loading it (fresh state, sends to one thread commit in message-id order)
        threadRepository.findForUpdateById(threadId);
        ThreadAccess access = accessService.requireThreadAccess(caller, threadId);
        MessageThread thread = access.thread();

        String clientMessageId = request != null && request.getClientMessageId() != null ? request.getClientMessageId().trim() : "";
        if (!ThreadService.CLIENT_ID.matcher(clientMessageId).matches()) {
            throw new RuntimeException("A valid clientMessageId is required");
        }
        Optional<Message> duplicate = messageRepository.findByThreadIdAndSenderSystemIdAndClientMessageId(
                threadId, caller.systemId(), clientMessageId);
        if (duplicate.isPresent()) {
            return new SendResult(toResponse(duplicate.get(), caller, thread), false);
        }

        String body = request.getBody() != null ? request.getBody().trim() : "";
        if (body.isEmpty()) {
            throw new RuntimeException("Message cannot be empty");
        }
        if (body.length() > maxBodyLength) {
            throw new RuntimeException("Messages can be at most " + maxBodyLength + " characters");
        }
        boolean urgent = Boolean.TRUE.equals(request.getUrgent());
        LocalDateTime now = LocalDateTime.now();

        Message message = messageRepository.saveAndFlush(Message.builder()
                .threadId(threadId)
                .senderSystemId(caller.systemId())
                .recipientSystemId(null)
                .patientSystemId(thread.getPatientSystemId())
                .clientMessageId(clientMessageId)
                .body(body)
                .isRead(false)
                .isUrgent(urgent)
                .isDeleted(false)
                .sentAt(now)
                .build());

        thread.setLastMessageId(message.getMessageId());
        thread.setLastMessageAt(now);
        thread.setUpdatedAt(now);
        threadRepository.save(thread);

        ThreadMember senderMember = access.member();
        senderMember.setLastReadMessageId(message.getMessageId());
        memberRepository.save(senderMember);

        List<ThreadMember> others = memberRepository.findActiveByThreadId(threadId).stream()
                .filter(m -> !m.getSystemId().equals(caller.systemId()))
                .toList();
        Map<String, User> users = usersFor(thread, List.of(caller.systemId()));
        String patientName = CommunicationLabels.name(users.get(thread.getPatientSystemId()), thread.getPatientSystemId());
        String senderLabel = CommunicationLabels.label(users.get(caller.systemId()), caller.systemId(), patientName);

        List<ThreadMember> unarchived = new ArrayList<>();
        for (ThreadMember other : others) {
            if (!accessService.memberCanActFor(other.getSystemId(), other.getMemberRole(), thread.getPatientSystemId())) {
                continue;
            }
            if (other.getArchivedAt() != null) {
                other.setArchivedAt(null);
                unarchived.add(other);
            }
            notificationService.createMessageNotification(other.getSystemId(), thread, message, senderLabel, patientName);
        }
        if (!unarchived.isEmpty()) {
            memberRepository.saveAll(unarchived);
        }

        auditService.logClinicalAccess("CREATE", RESOURCE_MESSAGE, message.getMessageId(), thread.getPatientSystemId());
        return new SendResult(toResponse(message, caller, users, patientName), true);
    }

    /**
     * Retry lookup after a unique-index race (runs in its own transaction, called by the controller after the
     * failed send rolled back). Access is checked again.
     */
    @Transactional(readOnly = true)
    public Optional<MessageResponse> findOwnMessageByClientId(Integer threadId, String clientMessageId) {
        Caller caller = accessService.requireCaller();
        ThreadAccess access = accessService.requireThreadAccess(caller, threadId);
        if (clientMessageId == null) {
            return Optional.empty();
        }
        return messageRepository.findByThreadIdAndSenderSystemIdAndClientMessageId(threadId, caller.systemId(), clientMessageId.trim())
                .map(m -> toResponse(m, caller, access.thread()));
    }

    // ==================== READ POSITION ====================

    public ThreadReadResponse markRead(Integer threadId, MarkThreadReadRequest request) {
        Caller caller = accessService.requireCaller();
        ThreadAccess access = accessService.requireThreadAccess(caller, threadId);
        if (request == null || request.getLastReadMessageId() == null) {
            throw new RuntimeException("lastReadMessageId is required");
        }
        int value = request.getLastReadMessageId();
        ThreadMember member = access.member();
        // Cap at the newest stored message (not the cached thread pointer)
        int cap = messageRepository.findMaxMessageId(threadId);
        int newPosition = Math.max(member.lastRead(), Math.min(value, cap));
        if (newPosition != member.lastRead()) {
            member.setLastReadMessageId(newPosition);
            memberRepository.save(member);
        }
        notificationRepository.markThreadReadUpTo(caller.systemId(), threadId, value, LocalDateTime.now());
        long unread = messageRepository.countUnread(threadId, Math.max(member.historyStart(), newPosition), caller.systemId());
        return ThreadReadResponse.builder()
                .lastReadMessageId(newPosition)
                .unreadCount(unread)
                .build();
    }

    // ==================== MAPPING ====================

    private Map<String, User> usersFor(MessageThread thread, Collection<String> senders) {
        Set<String> ids = new HashSet<>(senders);
        ids.add(thread.getPatientSystemId());
        return accessService.usersById(ids);
    }

    private MessageResponse toResponse(Message message, Caller caller, MessageThread thread) {
        Map<String, User> users = usersFor(thread, List.of(message.getSenderSystemId()));
        String patientName = CommunicationLabels.name(users.get(thread.getPatientSystemId()), thread.getPatientSystemId());
        return toResponse(message, caller, users, patientName);
    }

    static MessageResponse toResponse(Message message, Caller caller, Map<String, User> users, String patientName) {
        User sender = users.get(message.getSenderSystemId());
        String name = CommunicationLabels.name(sender, message.getSenderSystemId());
        String role = CommunicationLabels.role(sender);
        boolean mine = caller.systemId().equals(message.getSenderSystemId());
        return MessageResponse.builder()
                .messageId(message.getMessageId())
                .threadId(message.getThreadId())
                .clientMessageId(mine ? message.getClientMessageId() : null)
                .senderSystemId(message.getSenderSystemId())
                .senderName(name)
                .senderRole(role)
                .senderLabel(CommunicationLabels.label(name, role, patientName))
                .body(message.getBody())
                .urgent(Boolean.TRUE.equals(message.getIsUrgent()))
                .sentAt(CommunicationLabels.toInstant(message.getSentAt()))
                .mine(mine)
                .build();
    }
}
