package com.carenexus.api.communications.controller;

import com.carenexus.api.communications.service.SupportThreadService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Lets an administrator join a SUPPORT conversation before a conversation route delegates to the messaging services
 * (some of which run in read-only transactions). The join runs in its own transaction; when two first requests of
 * the same administrator race, the loser's insert hits ux_thread_members_thread_user and is ignored, because the
 * winner's membership row is what the route needs.
 */
@Slf4j
final class SupportJoin {

    private SupportJoin() {
    }

    static void joinIfAdministrator(SupportThreadService supportThreadService, Integer threadId) {
        try {
            supportThreadService.joinIfAdministrator(threadId);
        } catch (DataIntegrityViolationException alreadyJoined) {
            log.info("Administrator joined support thread {} in a concurrent request", threadId);
        }
    }
}
