package com.carenexus.api.communications.job;

import com.carenexus.api.common.event.CaregiverAccessChangedEvent;
import com.carenexus.api.common.event.CaregiverGrantRecordedEvent;
import com.carenexus.api.core.model.DependentAccess;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The expiry sweeper records each lapse exactly once in the consent ledger (CaregiverGrantRecordedEvent EXPIRED,
 * no actor, attestation carried) next to the access-changed event, skips revoked and unexpired grants, and records a
 * second lapse after an extension.
 */
@RecordApplicationEvents
class CaregiverAccessExpirySweeperTest extends CommittedIntegrationTest {

    @Autowired
    private CaregiverAccessExpirySweeper sweeper;

    @Autowired
    private ApplicationEvents applicationEvents;

    private List<CaregiverGrantRecordedEvent> expiredEvents() {
        return applicationEvents.stream(CaregiverGrantRecordedEvent.class)
                .filter(e -> CaregiverGrantRecordedEvent.EXPIRED.equals(e.kind()))
                .toList();
    }

    @Test
    void eachLapse_isRecordedOnce_withoutActor_andWithTheAttestation() throws Exception {
        DependentAccess lapsed = grantAccess(caregiver, patient, a -> {
            a.setCanViewRecords(true);
            a.setGuardianAttestedAt(Instant.now());
            a.setExpiresAt(LocalDateTime.now().minusMinutes(5));
        });
        grantAccess(otherCaregiver, patient, a -> a.setExpiresAt(LocalDateTime.now().plusDays(3)));   // not due
        grantAccess(caregiver, otherPatient, a -> {                                                  // revoked
            a.setExpiresAt(LocalDateTime.now().minusDays(1));
            a.setIsActive(false);
            a.setRevokedAt(LocalDateTime.now().minusHours(1));
        });

        assertEquals(1, sweeper.sweepOnce());
        assertEquals(List.of(new CaregiverGrantRecordedEvent(lapsed.getAccessId(), patient.getSystemId(),
                caregiver.getSystemId(), null, CaregiverGrantRecordedEvent.EXPIRED, true)), expiredEvents());
        assertTrue(applicationEvents.stream(CaregiverAccessChangedEvent.class)
                .anyMatch(e -> e.accessId().equals(lapsed.getAccessId()) && e.revoked()));

        assertEquals(0, sweeper.sweepOnce(), "a processed lapse is not recorded twice");
        assertEquals(1, expiredEvents().size());

        // The patient extends; when the new expiry passes the lapse is recorded again.
        MvcResult extended = doPatch("/api/patients/me/caregiver-access/" + lapsed.getAccessId(), patient,
                Map.of("expiresAt", LocalDate.now().plusDays(10).toString()));
        assertEquals(200, extended.getResponse().getStatus(), extended.getResponse().getContentAsString());
        assertEquals(CaregiverGrantRecordedEvent.EXTENDED, applicationEvents.stream(CaregiverGrantRecordedEvent.class)
                .reduce((a, b) -> b).orElseThrow().kind());
        // (a shorter extension than requested, lapsing within the next sweep at a later clock time)
        jdbc.update("UPDATE dependent_access SET expires_at = ? WHERE access_id = ?",
                LocalDateTime.now().plusMinutes(5), lapsed.getAccessId());
        assertEquals(0, sweeper.sweepOnce());
        assertEquals(1, sweeper.sweepOnce(LocalDateTime.now().plusMinutes(10)));
        assertEquals(2, expiredEvents().size());
    }
}
