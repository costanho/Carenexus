package com.carenexus.api.careteam;

import com.carenexus.api.careteam.dto.request.AssignCareTeamMemberRequest;
import com.carenexus.api.careteam.dto.request.UpdateCareTeamMemberRequest;
import com.carenexus.api.careteam.dto.response.CareTeamMemberResponse;
import com.carenexus.api.careteam.service.CareTeamService;
import com.carenexus.api.core.model.CareTeam;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-patient care-team lock must make a writer that waited act on the state committed while it waited.
 *
 * <p>Each test reproduces the race deterministically inside one transaction: the request's transaction loads a
 * care_team row into its persistence context first (as the authorization check does for a doctor's own row), then a
 * concurrent writer commits a change to that row on another connection (REQUIRES_NEW, as the request that held the
 * lock would), and only then does the service method run in the first transaction. Hibernate hands back the instance
 * it already holds, so without the refresh under the lock the service would decide on, and flush back, the stale
 * values.
 */
class CareTeamLockConsistencyTest extends CommittedIntegrationTest {

    @Autowired
    private CareTeamService careTeamService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void signOut() {
        clearAuthentication();
    }

    // ==================== HELPERS ====================

    private int legacyPrimaryRow() {
        return jdbc.queryForObject("SELECT care_team_id FROM care_team WHERE patient_system_id = ? "
                + "AND role = 'PRIMARY_DOCTOR' ORDER BY care_team_id LIMIT 1", Integer.class, patient.getSystemId());
    }

    private int externalNurseRow() {
        return jdbc.queryForObject("INSERT INTO care_team (patient_system_id, provider_name, role, joined_date, "
                + "assigned_at, is_primary, is_active, created_at) VALUES (?, 'Nurse Joy Banda', 'NURSE', CURRENT_DATE, "
                + "now(), FALSE, TRUE, now()) RETURNING care_team_id", Integer.class, patient.getSystemId());
    }

    /** Commits {@code change} on its own connection while the caller's transaction is open. */
    private void commitConcurrently(Runnable change) {
        TransactionTemplate other = new TransactionTemplate(transactionManager);
        other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        other.executeWithoutResult(status -> change.run());
    }

    private Map<String, Object> stored(int careTeamId) {
        return jdbc.queryForMap("SELECT role, is_primary, is_active FROM care_team WHERE care_team_id = ?", careTeamId);
    }

    private int activePrimaryRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM care_team WHERE patient_system_id = ? AND is_active "
                + "AND is_primary", Integer.class, patient.getSystemId());
    }

    // ==================== TESTS ====================

    @Test
    void update_afterConcurrentPrimarySwap_doesNotRevertTheSwap() {
        int x = legacyPrimaryRow();     // DOC-00001, legacy PRIMARY_DOCTOR: reads as the primary
        int y = externalNurseRow();
        authenticateAs(doctor);

        AtomicReference<CareTeamMemberResponse> response = new AtomicReference<>();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            CareTeam loadedBeforeLock = careTeamRepository.findById(x).orElseThrow();
            assertEquals("PRIMARY_DOCTOR", loadedBeforeLock.getRole());

            // The patient's swap (Y becomes primary, X is demoted) commits while this request waits for the lock.
            commitConcurrently(() -> {
                jdbc.update("UPDATE care_team SET role = 'DOCTOR', is_primary = FALSE WHERE care_team_id = ?", x);
                jdbc.update("UPDATE care_team SET is_primary = TRUE WHERE care_team_id = ?", y);
            });

            response.set(careTeamService.update(patient.getSystemId(), x,
                    UpdateCareTeamMemberRequest.builder().role("SPECIALIST").build()));
        });

        assertEquals("SPECIALIST", response.get().getRole());
        assertFalse(response.get().getIsPrimary(), "X was demoted by the concurrent swap and stays demoted");
        assertEquals("SPECIALIST", stored(x).get("role"));
        assertEquals(Boolean.FALSE, stored(x).get("is_primary"));
        assertEquals(Boolean.TRUE, stored(y).get("is_primary"), "the patient's swap is kept");
        assertEquals(1, activePrimaryRows());
    }

    @Test
    void assignPrimary_afterConcurrentPrimarySwap_demotesTheCurrentPrimary() {
        int x = legacyPrimaryRow();
        int y = externalNurseRow();
        authenticateAs(admin);

        AtomicReference<CareTeamMemberResponse> response = new AtomicReference<>();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            CareTeam loadedBeforeLock = careTeamRepository.findById(y).orElseThrow();
            assertFalse(loadedBeforeLock.getIsPrimary());

            commitConcurrently(() -> {
                jdbc.update("UPDATE care_team SET role = 'DOCTOR', is_primary = FALSE WHERE care_team_id = ?", x);
                jdbc.update("UPDATE care_team SET is_primary = TRUE WHERE care_team_id = ?", y);
            });

            response.set(careTeamService.assign(patient.getSystemId(), AssignCareTeamMemberRequest.builder()
                    .providerName("Riverside Pharmacy").role("PHARMACY").isPrimary(true).build()));
        });

        assertTrue(response.get().getIsPrimary());
        assertEquals(Boolean.FALSE, stored(y).get("is_primary"), "the primary committed concurrently is demoted");
        assertEquals(Boolean.TRUE, stored(response.get().getCareTeamId()).get("is_primary"));
        assertEquals(1, activePrimaryRows());
    }

    @Test
    void remove_afterConcurrentChange_keepsTheCommittedRoleAndPrimary() {
        int x = legacyPrimaryRow();
        authenticateAs(doctor);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            CareTeam loadedBeforeLock = careTeamRepository.findById(x).orElseThrow();
            assertEquals("PRIMARY_DOCTOR", loadedBeforeLock.getRole());

            commitConcurrently(() -> jdbc.update(
                    "UPDATE care_team SET role = 'SPECIALIST', is_primary = TRUE WHERE care_team_id = ?", x));

            careTeamService.remove(patient.getSystemId(), x);
        });

        Map<String, Object> row = stored(x);
        assertEquals(Boolean.FALSE, row.get("is_active"));
        assertEquals("SPECIALIST", row.get("role"), "the soft delete does not write back the stale role");
        assertEquals(Boolean.TRUE, row.get("is_primary"), "the soft delete does not write back the stale flag");
    }
}
