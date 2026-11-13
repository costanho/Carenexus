package com.carenexus.api.communications.service;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.dto.PageParams;
import com.carenexus.api.common.dto.PageResponse;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.common.exception.ResourceNotFoundException;
import com.carenexus.api.common.service.AuditService;
import com.carenexus.api.communications.dto.response.NotificationResponse;
import com.carenexus.api.communications.dto.response.UnreadCountResponse;
import com.carenexus.api.communications.model.Message;
import com.carenexus.api.communications.model.MessageThread;
import com.carenexus.api.communications.model.Notification;
import com.carenexus.api.communications.notify.NotificationCategory;
import com.carenexus.api.communications.notify.NotificationPreferences;
import com.carenexus.api.communications.notify.NotificationPriority;
import com.carenexus.api.communications.notify.NotificationTarget;
import com.carenexus.api.communications.notify.NotificationType;
import com.carenexus.api.communications.repository.NotificationRepository;
import com.carenexus.api.communications.service.CommunicationsAccessService.Caller;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * The caller's in-app notifications (list, counts, read state) and the two messaging writers.
 *
 * Writers. MESSAGE notifications are created only inside the send transaction, one per recipient per message, with
 * generic text (never the message body), and only for recipients whose in-app message notification preference
 * (user_settings.message_notifications) is on; unread counts in the inbox do not depend on it. SYSTEM notifications
 * tell a patient that a caregiver's grant waits for their approval (target CAREGIVER_ACCESS). Every other
 * notification is created through NotificationPublisher (communications.notify). The two writers here do not
 * publish NotificationStoredEvent: these notifications stay in-app only.
 *
 * Reads. A caller only ever sees, counts and marks their own rows. Lists and counts leave out expired rows and
 * MESSAGE notifications for threads the caller can no longer access or for messages before the caller's history
 * boundary. A page of the list and a change of read state are audited (resource NOTIFICATION); the unread-count
 * poll is not.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class NotificationService {

    static final String RESOURCE = "NOTIFICATION";
    static final String ICON_MESSAGE = NotificationType.MESSAGE.icon();
    static final String ICON_CAREGIVER = "people-outline";
    /** Largest page of the notification list. */
    static final int MAX_PAGE_SIZE = 50;
    /** Frontend target for the patient's caregiver-access screen (sidebar key my-proxies). */
    public static final String TARGET_CAREGIVER_ACCESS = NotificationTarget.CAREGIVER_ACCESS.name();
    public static final String ACTION_CAREGIVER_ACCESS = "/dashboard/patient?section=my-proxies";
    /** Placeholder so "IN :threadIds" is never empty. */
    private static final List<Integer> NO_THREADS = List.of(-1);

    private final NotificationRepository notificationRepository;
    private final CommunicationsAccessService accessService;
    private final NotificationPreferences notificationPreferences;
    private final AuditService auditService;

    // ==================== MESSAGING WRITERS ====================

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
        // "about {patient}" only when someone else wrote about the patient; never "Thomas Dube ... about Thomas Dube",
        // and never for a conversation that is not about a patient
        String threadPatient = thread.getPatientSystemId();
        boolean aboutPatient = threadPatient != null && patientName != null
                && !recipientSystemId.equals(threadPatient)
                && !threadPatient.equals(message.getSenderSystemId());
        String text = aboutPatient
                ? senderLabel + " sent a message about " + patientName
                : senderLabel + " sent you a message";
        notificationRepository.save(Notification.builder()
                .systemId(recipientSystemId)
                .type(Notification.TYPE_MESSAGE)
                .category(NotificationCategory.MESSAGE.name())
                .title(urgent ? "Urgent message" : "New message")
                .message(text)
                .priority(urgent ? Notification.PRIORITY_HIGH : Notification.PRIORITY_NORMAL)
                .isRead(false)
                .icon(ICON_MESSAGE)
                .actionUrl(null)
                .threadId(thread.getThreadId())
                .messageId(message.getMessageId())
                .patientSystemId(threadPatient)
                .actorSystemId(message.getSenderSystemId())
                .build());
    }

    /** In-app message notifications are on unless the user saved message_notifications = false. */
    boolean wantsMessageNotifications(String systemId) {
        return notificationPreferences.allows(systemId, NotificationCategory.MESSAGE, NotificationPriority.NORMAL);
    }

    /**
     * Patient notification: a caregiver's grant waits for their approval of messaging and/or video calls. Called
     * inside the grant change's transaction (CaregiverApprovalNotificationListener). Never names the patient's data.
     * Not preference-gated (category SYSTEM).
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
                .category(NotificationCategory.SYSTEM.name())
                .target(TARGET_CAREGIVER_ACCESS)
                .title("Caregiver approval needed")
                .message(name + " is waiting for your approval to " + what)
                .priority(Notification.PRIORITY_NORMAL)
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

    // ==================== READS ====================

    /** The caller's visible notifications of every type, newest first. */
    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> list(boolean unreadOnly, int page, int size) {
        return list(unreadOnly, null, page, size);
    }

    /**
     * The caller's visible notifications, newest first.
     *
     * @param type optional notification type filter (any case); null or blank = every type
     * @throws BadRequestException when {@code type} is not a notification type
     */
    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> list(boolean unreadOnly, String type, int page, int size) {
        Caller caller = accessService.caller();
        String typeFilter = typeFilter(type);
        Page<Notification> result = notificationRepository.findVisible(caller.systemId(), visibleThreads(caller),
                unreadOnly, typeFilter, LocalDateTime.now(), PageParams.of(page, size, MAX_PAGE_SIZE, null));
        // One READ row per patient referenced on the page; a page without patient references writes none
        auditService.logListRead(RESOURCE, result.getContent(), Notification::getPatientSystemId);
        return PageResponse.of(result, NotificationService::toResponse);
    }

    /** Polled by the clients; deliberately not audited. */
    @Transactional(readOnly = true)
    public UnreadCountResponse unreadCount() {
        Caller caller = accessService.caller();
        List<Integer> threads = visibleThreads(caller);
        LocalDateTime now = LocalDateTime.now();
        return UnreadCountResponse.builder()
                .unreadCount(notificationRepository.countVisibleUnread(caller.systemId(), threads, now))
                .unreadMessageCount(notificationRepository.countVisibleUnreadMessages(caller.systemId(), threads, now))
                .build();
    }

    // ==================== READ STATE ====================

    /**
     * Marks one of the caller's own notifications read. Idempotent: a repeat keeps the original read time and
     * writes no audit row. Expiry and thread access are not checked, so a row that expired between list and click
     * can still be marked.
     */
    public void markRead(Integer notificationId) {
        Caller caller = accessService.caller();
        Notification notification = notificationRepository.findByNotificationIdAndSystemId(notificationId, caller.systemId())
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));
        if (!Boolean.TRUE.equals(notification.getIsRead())) {
            notification.setIsRead(true);
            notification.setReadAt(LocalDateTime.now());
            notificationRepository.save(notification);
            auditService.logClinicalAccess("UPDATE", RESOURCE, notification.getNotificationId(),
                    notification.getPatientSystemId());
        }
    }

    /** Marks every unread notification of the caller read (all types, hidden rows included). */
    public void markAllRead() {
        Caller caller = accessService.caller();
        int changed = notificationRepository.markAllRead(caller.systemId(), LocalDateTime.now());
        if (changed > 0) {
            auditService.logClinicalAccess("UPDATE", RESOURCE, null, null);
        }
    }

    // ==================== HELPERS ====================

    private List<Integer> visibleThreads(Caller caller) {
        List<Integer> ids = accessService.accessibleThreadIds(caller);
        return ids.isEmpty() ? NO_THREADS : ids;
    }

    /** The type name to filter on, or the repository's "any type" marker. */
    private static String typeFilter(String type) {
        if (type == null || type.isBlank()) {
            return NotificationRepository.ANY_TYPE;
        }
        String value = type.trim().toUpperCase(Locale.ROOT);
        for (NotificationType known : NotificationType.values()) {
            if (known.name().equals(value)) {
                return value;
            }
        }
        throw new BadRequestException("Unknown notification type");
    }

    static NotificationResponse toResponse(Notification n) {
        // A row stored without target or category still answers with the values the clients know
        String target = n.getTarget() != null ? n.getTarget()
                : ACTION_CAREGIVER_ACCESS.equals(n.getActionUrl()) ? TARGET_CAREGIVER_ACCESS : null;
        String category = n.getCategory() != null ? n.getCategory() : Notification.defaultCategory(n.getType());
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
                .target(target)
                .category(category)
                .resourceType(n.getResourceType())
                .resourceId(n.getResourceId())
                .build();
    }
}
