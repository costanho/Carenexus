package com.carenexus.api.caregiveraccess.service;

import com.carenexus.api.caregiveraccess.model.CaregiverAccessRequest;
import com.carenexus.api.common.event.CaregiverGrantRecordedEvent;
import com.carenexus.api.communications.notify.NotificationCategory;
import com.carenexus.api.communications.notify.NotificationCommand;
import com.carenexus.api.communications.notify.NotificationLinks;
import com.carenexus.api.communications.notify.NotificationPriority;
import com.carenexus.api.communications.notify.NotificationPublisher;
import com.carenexus.api.communications.notify.NotificationTarget;
import com.carenexus.api.communications.notify.NotificationType;
import com.carenexus.api.core.model.DependentAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The in-app notifications of this module, all through {@link NotificationPublisher} (links are computed there).
 *
 * <ul>
 *   <li>Request notifications, category SYSTEM (never suppressible): N1 to the patient on a new request, N2 and N3
 *       to the caregiver on the decision (resource ACCESS_REQUEST, so the caregiver's link opens the
 *       access-requests section).</li>
 *   <li>Grant-change notifications to the caregiver, category ACCESS_CHANGE (switched off by the caregiver's
 *       accessChangeAlerts setting): N4 granted, N5 permissions changed, N6 communication approved, N7 extended,
 *       N8 removed, N9 expired, N10 expires soon (resource DEPENDENT_ACCESS without an id, so the link opens the
 *       dependent's profile settings). NARROWED notifies nobody, and the publisher never notifies the actor of a
 *       non-SYSTEM notification (a caregiver who created the dependent or closed their own account).</li>
 * </ul>
 * Texts name people only ("First Last", or "A patient" / "A caregiver"); they carry no clinical content.
 */
@Component
@RequiredArgsConstructor
public class AccessNotifications {

    static final String RESOURCE_DEPENDENT_ACCESS = "DEPENDENT_ACCESS";
    private static final DateTimeFormatter NOTICE_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final NotificationPublisher publisher;
    private final AccessPeople people;

    // ==================== REQUESTS (SYSTEM) ====================

    /** N1: the patient is asked to approve a new request. */
    public void requestReceived(CaregiverAccessRequest request) {
        publisher.publish(NotificationCommand.builder()
                .recipientSystemId(request.getPatientSystemId())
                .type(NotificationType.SYSTEM)
                .category(NotificationCategory.SYSTEM)
                .priority(NotificationPriority.NORMAL)
                .title("Caregiver access request")
                .message(people.caregiverName(request.getCaregiverSystemId())
                        + " has asked for access to your health information")
                .target(NotificationTarget.CAREGIVER_ACCESS)
                .param("tab", "requests")
                .resourceType(NotificationLinks.RESOURCE_ACCESS_REQUEST)
                .resourceId(request.getRequestId())
                .patientSystemId(request.getPatientSystemId())
                .actorSystemId(request.getCaregiverSystemId())
                .dedupeKey("ACCESS_REQUEST_NEW:" + request.getRequestId())
                .expiresAt(request.requestExpiresAt())
                .build());
    }

    /** N2: the caregiver's request was approved. */
    public void requestApproved(CaregiverAccessRequest request) {
        publishDecision(request, "Access request approved",
                people.patientName(request.getPatientSystemId()) + " approved your access request",
                "ACCESS_REQUEST_APPROVED:");
    }

    /** N3: the caregiver's request was declined. */
    public void requestDeclined(CaregiverAccessRequest request) {
        publishDecision(request, "Access request declined",
                "Your access request for " + request.getPatientEmail() + " was declined",
                "ACCESS_REQUEST_DECLINED:");
    }

    private void publishDecision(CaregiverAccessRequest request, String title, String message, String dedupePrefix) {
        publisher.publish(NotificationCommand.builder()
                .recipientSystemId(request.getCaregiverSystemId())
                .type(NotificationType.SYSTEM)
                .category(NotificationCategory.SYSTEM)
                .priority(NotificationPriority.NORMAL)
                .title(title)
                .message(message)
                .target(NotificationTarget.CAREGIVER_ACCESS)
                .resourceType(NotificationLinks.RESOURCE_ACCESS_REQUEST)
                .resourceId(request.getRequestId())
                .actorSystemId(request.getPatientSystemId())
                .dedupeKey(dedupePrefix + request.getRequestId())
                .build());
    }

    // ==================== GRANT CHANGES (ACCESS_CHANGE) ====================

    /**
     * N4 to N9 for one consent-ledger step. GRANTED is skipped while the grant is being created by the approval of
     * an access request (N2 replaces it); NARROWED never notifies.
     *
     * @param consentId the ledger row of the step (part of the dedupe key, so every step notifies once)
     */
    public void grantChanged(CaregiverGrantRecordedEvent event, Integer consentId) {
        String patientName = people.patientName(event.patientSystemId());
        String title;
        String message;
        switch (event.kind()) {
            case CaregiverGrantRecordedEvent.GRANTED -> {
                if (ApprovalInProgress.isMarked(event.caregiverSystemId(), event.patientSystemId())) {
                    return;
                }
                title = "Access granted";
                message = patientName + " gave you access to their health information";
            }
            case CaregiverGrantRecordedEvent.PERMISSIONS_CHANGED -> {
                title = "Your access was changed";
                message = "Your access to " + patientName + "'s information was changed";
            }
            case CaregiverGrantRecordedEvent.COMMUNICATION_APPROVED -> {
                title = "Communication approved";
                message = patientName + " approved your request to message their care team or join their video calls";
            }
            case CaregiverGrantRecordedEvent.EXTENDED -> {
                title = "Your access was extended";
                message = "Your access to " + patientName + "'s information was extended";
            }
            case CaregiverGrantRecordedEvent.REVOKED -> {
                title = "Your access was removed";
                message = "Your access to " + patientName + "'s information has been removed";
            }
            case CaregiverGrantRecordedEvent.EXPIRED -> {
                title = "Your access has expired";
                message = "Your access to " + patientName + "'s information has expired";
            }
            default -> {
                return; // NARROWED: the caregiver reduced their own access
            }
        }
        publishAccessChange(event.caregiverSystemId(), event.patientSystemId(), event.actorSystemId(), title, message,
                "ACCESS_CHANGE:" + event.accessId() + ":" + consentId);
    }

    /**
     * N10: the grant ends within the notice window. Once per grant and expiry date (a later date notifies again).
     *
     * @return true when a notification was stored (false: switched off, or this date was already announced)
     */
    public boolean accessExpiringSoon(DependentAccess grant, String patientName) {
        LocalDateTime expiresAt = grant.getExpiresAt();
        String name = patientName == null ? AccessPeople.SOME_PATIENT : patientName;
        return publishAccessChange(grant.getCaregiverSystemId(), grant.getPatientSystemId(), null,
                "Your access expires soon",
                "Your access to " + name + "'s information ends on " + NOTICE_DATE.format(expiresAt),
                "ACCESS_EXPIRING:" + grant.getAccessId() + ":" + expiresAt.toLocalDate());
    }

    private boolean publishAccessChange(String caregiverSystemId, String patientSystemId, String actorSystemId,
                                        String title, String message, String dedupeKey) {
        return publisher.publish(NotificationCommand.builder()
                .recipientSystemId(caregiverSystemId)
                .type(NotificationType.SYSTEM)
                .category(NotificationCategory.ACCESS_CHANGE)
                .priority(NotificationPriority.NORMAL)
                .title(title)
                .message(message)
                .target(NotificationTarget.CAREGIVER_ACCESS)
                .resourceType(RESOURCE_DEPENDENT_ACCESS)
                .patientSystemId(patientSystemId)
                .actorSystemId(actorSystemId)
                .dedupeKey(dedupeKey)
                .build()).isPresent();
    }
}
