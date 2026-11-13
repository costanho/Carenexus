package com.carenexus.api.account.legal.listener;

import com.carenexus.api.account.legal.service.LegalService;
import com.carenexus.api.common.event.UserRegisteredEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Records the terms and privacy versions a self-registration accepted. A plain listener: it runs inside the
 * registration transaction, so a stale version (400 LEGAL_ACCEPTANCE_REQUIRED) rolls the whole registration back.
 * It runs before the other registration listeners, so nothing else is prepared for a registration it refuses.
 */
@Component
@RequiredArgsConstructor
public class LegalRegistrationListener {

    private final LegalService legalService;

    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE + 100)
    public void onUserRegistered(UserRegisteredEvent event) {
        legalService.recordRegistrationAcceptances(event);
    }
}
