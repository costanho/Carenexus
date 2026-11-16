package com.carenexus.api.caregiveraccess.listener;

import com.carenexus.api.caregiveraccess.model.ConsentRecord;
import com.carenexus.api.caregiveraccess.service.AccessNotifications;
import com.carenexus.api.caregiveraccess.service.ConsentLedgerService;
import com.carenexus.api.common.event.CaregiverGrantRecordedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Consent ledger and access-change notifications for every recorded step of a caregiver grant
 * (CaregiverService, CaregiverAccessExpirySweeper, registration with a caregiver id).
 *
 * A plain {@link EventListener}: it runs synchronously inside the publisher's transaction, so the ledger row commits
 * (or rolls back) with the grant change. It writes the row first and then notifies the caregiver, using the row id
 * in the notification's dedupe key so each step notifies at most once.
 */
@Component
@RequiredArgsConstructor
public class CaregiverGrantLedgerListener {

    private final ConsentLedgerService consentLedgerService;
    private final AccessNotifications accessNotifications;

    @EventListener
    public void onGrantRecorded(CaregiverGrantRecordedEvent event) {
        consentLedgerService.record(event)
                .map(ConsentRecord::getConsentId)
                .ifPresent(consentId -> accessNotifications.grantChanged(event, consentId));
    }
}
