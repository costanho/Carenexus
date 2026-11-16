package com.carenexus.api.admin.clinician;

import com.carenexus.api.common.event.UserRegisteredEvent;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Administrators hear about clinician self-registrations, and only about those.
 */
class ClinicianRegistrationListenerTest extends CommittedIntegrationTest {

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void aSelfRegisteredDoctor_notifiesEveryActiveAdministratorOnce() {
        createAndSaveUser(8, "ADM-00002", "second.admin@example.com", "ADMIN");
        createAndSaveUser(9, "ADM-00003", "retired.admin@example.com", "ADMIN");
        jdbc.update("UPDATE users SET is_active = FALSE WHERE system_id = 'ADM-00003'");

        publish(new UserRegisteredEvent("DOC-00002", "DOCTOR", "other.doc@example.com", null, null, null, null, null));
        publish(new UserRegisteredEvent("DOC-00002", "DOCTOR", "other.doc@example.com", null, null, null, null, null));

        assertEquals(1, count("SELECT COUNT(*) FROM notifications WHERE system_id = 'ADM-00001' "
                + "AND dedupe_key = 'DOCTOR_REGISTERED:DOC-00002' AND target = 'ADMIN_CLINICIAN_QUEUE' "
                + "AND category = 'SYSTEM' AND priority = 'NORMAL' AND actor_system_id = 'DOC-00002' "
                + "AND resource_type = 'ACCOUNT' AND action_url = '/dashboard/admin?section=clinician-queue'"));
        assertEquals(1, count("SELECT COUNT(*) FROM notifications WHERE system_id = 'ADM-00002'"));
        assertEquals(0, count("SELECT COUNT(*) FROM notifications WHERE system_id = 'ADM-00003'"));
        assertEquals(2, count("SELECT COUNT(*) FROM notifications"));
    }

    @Test
    void otherRolesAndAccountsCreatedBySomebodyElse_notifyNobody() {
        publish(new UserRegisteredEvent("PAT-00002", "PATIENT", "mary@example.com", null, null, null, null, null));
        publish(new UserRegisteredEvent("CGV-00002", "CAREGIVER", "other.cg@example.com", null, null, null, null, null));
        publish(new UserRegisteredEvent("ADM-00002", "ADMIN", "new.admin@example.com", null, null, null, null, "ADM-00001"));
        publish(new UserRegisteredEvent("DOC-00002", "DOCTOR", "other.doc@example.com", null, null, null, null, "ADM-00001"));

        assertEquals(0, count("SELECT COUNT(*) FROM notifications"));
    }

    private void publish(UserRegisteredEvent event) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> eventPublisher.publishEvent(event));
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
