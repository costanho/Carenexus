package com.carenexus.api.communications.service;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.communications.dto.response.NotificationResponse;
import com.carenexus.api.communications.dto.response.PageResponse;
import com.carenexus.api.communications.dto.response.UnreadCountResponse;
import com.carenexus.api.communications.model.Message;
import com.carenexus.api.communications.model.MessageThread;
import com.carenexus.api.communications.model.Notification;
import com.carenexus.api.communications.repository.NotificationRepository;
import com.carenexus.api.communications.service.CommunicationsAccessService.Caller;
import com.carenexus.api.core.model.UserSettings;
import com.carenexus.api.core.repository.UserSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * In-app notifications (contract 7.4). MESSAGE notifications are created only inside the send transaction, one per
 * recipient per message, with generic text (never the message body), and only for recipients whose in-app message
 * notification preference (user_settings.message_notifications) is on; unread counts in the inbox do not depend on
 * it. Lists exclude MESSAGE notifications for threads the caller can no longer access or for messages before the
 * caller's history boundary.
 *
 * SYSTEM notifications tell a patient that a caregiver's grant waits for their approval (target CAREGIVER_ACCESS).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationService {

    static final String ICON_MESSAGE = "chatbubble-outline";
    static final String ICON_CAREGIVER = "people-outline";
    /** Frontend target for the patient's caregiver-access screen (sidebar key my-proxies). */
    public static final String TARGET_CAREGIVER_ACCESS = "CAREGIVER_ACCESS";
    public static final String ACTION_CAREGIVER_ACCESS = "/dashboard/patient?section=my-proxies";
    /** Placeholder so "IN :threadIds" is never empty. */
    private static final List<Integer> NO_THREADS = List.of(-1);

    private final NotificationRepository notificationRepository;
    private final CommunicationsAccessService accessService;
    private final UserSettingsRepository userSettingsRepository;

    /**
     * Called by MessageService inside the send transaction. Idempotent per (recipient, message). Skipped when the
     * recipient turned in-app message notifications off.
     */
    public void createMessageNotification(String recipientSystemId, MessageThread thread, Message message,
                                          String senderLabel, String patientName) {
        if (!wantsMessageNotifications(recipientSystemId)) {
            return;
        }
        if (notificationRepository.existsBySystemIdAndMessageId(recipientSystemId, message.getMessageId())) {
            return;
        }
        boolean urgent = Boolean.TRUE.equals(message.getIsUrgent());
        // "about {patient}" only when someone else wrote about the patient; never "Thomas Dube ... about Thomas Dube"
        boolean toPatient = recipientSystemId.equals(thread.getPatientSystemId());
        boolean fromPatient = thread.getPatientSystemId() != null && thread.getPatientSystemId().equals(message.getSenderSystemId());
        String text = toPatient || fromPatient
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

    /** In-app message notifications are on unless the user saved message_notifications = false. */
    boolean wantsMessageNotifications(String systemId) {
        return userSettingsRepository.findBySystemId(systemId)
                .map(UserSettings::getMessageNotifications)
                .map(Boolean.TRUE::equals)
                .orElse(true);
    }

    /**
     * Patient notification: a caregiver's grant waits for their approval of messaging and/or video calls. Called
     * inside the grant change's transaction (CaregiverApprovalNotificationListener). Never names the patient's data.
     */
    public void createCaregiverApprovalNotification(String patientSystemId, String caregiverSystemId,
                                                    boolean messaging, boolean calls) {
        if (patientSystemId == null || caregiverSystemId == null || (!messaging && !calls)) {
            return;
        }
        User caregiver = accessService.usersById(List.of(caregiverSystemId)).get(caregiverSystemId);
        String name = caregiver != null ? CommunicationLabels.name(caregiver, caregiverSystemId) : "A caregiver";
        String what = messaging && calls ? "message your care team and join your video calls"
                : messaging ? "message your care team" : "join your video calls";
        notificationRepository.save(Notification.builder()
                .systemId(patientSystemId)
                .type(Notification.TYPE_SYSTEM)
                .title("Caregiver approval needed")
                .message(name + " is waiting for your approval to " + what)
                .priority("NORMAL")
                .isRead(false)
                .icon(ICON_CAREGIVER)
                .actionUrl(ACTION_CAREGIVER_ACCESS)
                .patientSystemId(patientSystemId)
                .actorSystemId(caregiverSystemId)
                .build());
    }

    /** The request was approved, withdrawn or revoked: unread approval notifications about this caregiver are read. */
    public void resolveCaregiverApprovalNotifications(String patientSystemId, String caregiverSystemId) {
        if (patientSystemId == null || caregiverSystemId == null) {
            return;
        }
        notificationRepository.markReadForActorAndAction(patientSystemId, caregiverSystemId, ACTION_CAREGIVER_ACCESS,
                LocalDateTime.now());
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
                .actionUrl(n.getActionUrl())
                .target(ACTION_CAREGIVER_ACCESS.equals(n.getActionUrl()) ? TARGET_CAREGIVER_ACCESS : null)
                .build();
    }
}
