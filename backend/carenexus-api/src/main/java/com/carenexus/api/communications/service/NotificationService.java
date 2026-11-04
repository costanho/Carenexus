package com.carenexus.api.communications.service;

import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.communications.dto.response.NotificationResponse;
import com.carenexus.api.communications.dto.response.PageResponse;
import com.carenexus.api.communications.dto.response.UnreadCountResponse;
import com.carenexus.api.communications.model.Message;
import com.carenexus.api.communications.model.MessageThread;
import com.carenexus.api.communications.model.Notification;
import com.carenexus.api.communications.repository.NotificationRepository;
import com.carenexus.api.communications.service.CommunicationsAccessService.Caller;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * In-app notifications (contract 7.4). MESSAGE notifications are created only inside the send transaction, one per
 * recipient per message, with generic text (never the message body). Lists exclude MESSAGE notifications for
 * threads the caller can no longer access or for messages before the caller's history boundary.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationService {

    static final String ICON_MESSAGE = "chatbubble-outline";
    /** Placeholder so "IN :threadIds" is never empty. */
    private static final List<Integer> NO_THREADS = List.of(-1);

    private final NotificationRepository notificationRepository;
    private final CommunicationsAccessService accessService;

    /**
     * Called by MessageService inside the send transaction. Idempotent per (recipient, message).
     */
    public void createMessageNotification(String recipientSystemId, MessageThread thread, Message message,
                                          String senderLabel, String patientName) {
        if (notificationRepository.existsBySystemIdAndMessageId(recipientSystemId, message.getMessageId())) {
            return;
        }
        boolean urgent = Boolean.TRUE.equals(message.getIsUrgent());
        String text = recipientSystemId.equals(thread.getPatientSystemId())
                ? senderLabel + " sent you a message"
                : senderLabel + " sent a message about " + patientName;
        notificationRepository.save(Notification.builder()
                .systemId(recipientSystemId)
                .type(Notification.TYPE_MESSAGE)
                .title(urgent ? "Urgent message" : "New message")
                .message(text)
                .priority(urgent ? "HIGH" : "NORMAL")
                .isRead(false)
                .icon(ICON_MESSAGE)
                .actionUrl(null)
                .threadId(thread.getThreadId())
                .messageId(message.getMessageId())
                .patientSystemId(thread.getPatientSystemId())
                .actorSystemId(message.getSenderSystemId())
                .build());
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> list(boolean unreadOnly, int page, int size) {
        Caller caller = accessService.caller();
        Page<Notification> result = notificationRepository.findVisible(caller.systemId(), visibleThreads(caller),
                unreadOnly, PageRequest.of(Math.max(page, 0), clampSize(size)));
        return PageResponse.of(result, NotificationService::toResponse);
    }

    @Transactional(readOnly = true)
    public UnreadCountResponse unreadCount() {
        Caller caller = accessService.caller();
        List<Integer> threads = visibleThreads(caller);
        return UnreadCountResponse.builder()
                .unreadCount(notificationRepository.countVisibleUnread(caller.systemId(), threads))
                .unreadMessageCount(notificationRepository.countVisibleUnreadMessages(caller.systemId(), threads))
                .build();
    }

    public void markRead(Integer notificationId) {
        Caller caller = accessService.caller();
        Notification notification = notificationRepository.findByNotificationIdAndSystemId(notificationId, caller.systemId())
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));
        if (!Boolean.TRUE.equals(notification.getIsRead())) {
            notification.setIsRead(true);
            notification.setReadAt(LocalDateTime.now());
            notificationRepository.save(notification);
        }
    }

    public void markAllRead() {
        Caller caller = accessService.caller();
        notificationRepository.markAllRead(caller.systemId(), LocalDateTime.now());
    }

    private List<Integer> visibleThreads(Caller caller) {
        List<Integer> ids = accessService.accessibleThreadIds(caller);
        return ids.isEmpty() ? NO_THREADS : ids;
    }

    static int clampSize(int size) {
        return Math.min(Math.max(size, 1), 50);
    }

    static NotificationResponse toResponse(Notification n) {
        return NotificationResponse.builder()
                .notificationId(n.getNotificationId())
                .type(n.getType())
                .title(n.getTitle())
                .message(n.getMessage())
                .priority(n.getPriority())
                .read(Boolean.TRUE.equals(n.getIsRead()))
                .readAt(CommunicationLabels.toInstant(n.getReadAt()))
                .createdAt(CommunicationLabels.toInstant(n.getCreatedAt()))
                .threadId(n.getThreadId())
                .messageId(n.getMessageId())
                .patientSystemId(n.getPatientSystemId())
                .build();
    }
}
