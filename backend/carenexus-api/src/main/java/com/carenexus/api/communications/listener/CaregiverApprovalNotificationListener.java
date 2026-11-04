package com.carenexus.api.communications.listener;

import com.carenexus.api.common.event.CaregiverApprovalPendingEvent;
import com.carenexus.api.communications.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Tells the patient (in-app notification, contract 7.4) when a caregiver's grant is waiting for their approval of
 * messaging or video calls, and clears that notification once the request is approved, withdrawn or revoked.
 * Runs synchronously inside the grant change's transaction, so the notification and the grant commit together.
 */
@Component
@RequiredArgsConstructor
public class CaregiverApprovalNotificationListener {

    private final NotificationService notificationService;

    @EventListener
    public void onApprovalPendingChanged(CaregiverApprovalPendingEvent event) {
        if (event == null) {
            return;
        }
        if (event.pending()) {
            notificationService.createCaregiverApprovalNotification(event.patientSystemId(), event.caregiverSystemId(),
                    event.messagingRequested(), event.callsRequested());
        } else {
            notificationService.resolveCaregiverApprovalNotifications(event.patientSystemId(), event.caregiverSystemId());
        }
    }
}
