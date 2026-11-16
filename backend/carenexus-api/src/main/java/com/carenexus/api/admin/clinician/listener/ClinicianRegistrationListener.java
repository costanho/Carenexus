package com.carenexus.api.admin.clinician.listener;

import com.carenexus.api.admin.clinician.repository.ClinicianRepository;
import com.carenexus.api.common.event.UserRegisteredEvent;
import com.carenexus.api.communications.notify.NotificationCategory;
import com.carenexus.api.communications.notify.NotificationCommand;
import com.carenexus.api.communications.notify.NotificationPriority;
import com.carenexus.api.communications.notify.NotificationPublisher;
import com.carenexus.api.communications.notify.NotificationTarget;
import com.carenexus.api.communications.notify.NotificationType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Tells every active administrator that a clinician registered and waits for licence verification. Runs inside the
 * registration transaction (plain {@code @EventListener}), so the notice exists exactly when the registration
 * committed. Only self-registrations of role DOCTOR count (an account created by somebody else carries
 * {@code createdBySystemId}). One notice per administrator and doctor (dedupe key).
 */
@Component
@RequiredArgsConstructor
public class ClinicianRegistrationListener {

    private final ClinicianRepository clinicianRepository;
    private final NotificationPublisher notificationPublisher;

    @EventListener
    public void onUserRegistered(UserRegisteredEvent event) {
        if (event == null || !"DOCTOR".equals(event.role()) || event.createdBySystemId() != null) {
            return;
        }
        String doctorSystemId = event.systemId();
        for (String adminSystemId : clinicianRepository.findActiveAdminSystemIds()) {
            notificationPublisher.publish(NotificationCommand.builder()
                    .recipientSystemId(adminSystemId)
                    .type(NotificationType.SYSTEM)
                    .category(NotificationCategory.SYSTEM)
                    .priority(NotificationPriority.NORMAL)
                    .title("New clinician registration")
                    .message("A clinician registered and is waiting for licence verification.")
                    .target(NotificationTarget.ADMIN_CLINICIAN_QUEUE)
                    .resourceType("ACCOUNT")
                    .actorSystemId(doctorSystemId)
                    .dedupeKey("DOCTOR_REGISTERED:" + doctorSystemId)
                    .build());
        }
    }
}
