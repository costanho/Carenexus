package com.carenexus.api.caregiveraccess.service;

import com.carenexus.api.caregiveraccess.dto.response.ConsentScopeResponse;
import com.carenexus.api.caregiveraccess.model.CaregiverAccessRequest;
import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.core.model.DependentAccess;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Pure rules of the caregiveraccess module: status parsing, flag normalisation, the approval mark, the snapshot. */
class CaregiverAccessRulesTest {

    @Test
    void parseStatus_trimsUpperCasesAndRejectsUnknownValues() {
        assertNull(CaregiverAccessRequestService.parseStatus(null));
        assertNull(CaregiverAccessRequestService.parseStatus("   "));
        assertEquals("PENDING", CaregiverAccessRequestService.parseStatus(" pending "));
        assertEquals("EXPIRED", CaregiverAccessRequestService.parseStatus("Expired"));
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> CaregiverAccessRequestService.parseStatus("OPEN"));
        assertEquals("Invalid status. Must be PENDING, APPROVED, DECLINED, CANCELLED or EXPIRED", e.getMessage());
    }

    @Test
    void normalizeFlags_followsTheGrantRule() {
        CaregiverAccessRequest full = CaregiverAccessRequest.builder().accessLevel("FULL_ACCESS").build();
        CaregiverAccessRequestService.normalizeFlags(full);
        assertTrue(full.getCanViewRecords() && full.getCanViewBilling() && full.getCanManageBilling()
                && full.getCanScheduleAppointments() && full.getCanManageCarePlan() && full.getCanViewImaging());
        assertFalse(full.getCanMessageCareTeam());
        assertFalse(full.getCanJoinCalls());

        CaregiverAccessRequest viewOnly = CaregiverAccessRequest.builder().accessLevel("VIEW_ONLY")
                .canViewRecords(true).canScheduleAppointments(true).canManageCarePlan(true).canManageBilling(true)
                .canJoinCalls(true).build();
        CaregiverAccessRequestService.normalizeFlags(viewOnly);
        assertTrue(viewOnly.getCanViewRecords());
        assertFalse(viewOnly.getCanScheduleAppointments());
        assertFalse(viewOnly.getCanManageCarePlan());
        assertFalse(viewOnly.getCanManageBilling());
        assertTrue(viewOnly.getCanJoinCalls());

        CaregiverAccessRequest custom = CaregiverAccessRequest.builder().accessLevel("CUSTOM")
                .canManageBilling(true).build();
        CaregiverAccessRequestService.normalizeFlags(custom);
        assertTrue(custom.getCanManageBilling());
        assertFalse(custom.getCanViewRecords());
    }

    @Test
    void requestExpiresThirtyDaysAfterCreation() {
        LocalDateTime created = LocalDateTime.of(2026, 11, 10, 9, 14, 22);
        CaregiverAccessRequest request = CaregiverAccessRequest.builder().createdAt(created).build();
        assertEquals(LocalDateTime.of(2026, 12, 10, 9, 14, 22), request.requestExpiresAt());
    }

    @Test
    void unrevoked_meansActiveAndNotRevoked() {
        assertTrue(CaregiverAccessRequestService.isUnrevoked(DependentAccess.builder().isActive(true).build()));
        assertTrue(CaregiverAccessRequestService.isUnrevoked(DependentAccess.builder().isActive(true)
                .expiresAt(LocalDateTime.now().minusDays(1)).build()), "an expired grant still binds the pair");
        assertFalse(CaregiverAccessRequestService.isUnrevoked(DependentAccess.builder().isActive(false)
                .revokedAt(LocalDateTime.now()).build()));
    }

    @Test
    void approvalMark_isScopedToThePairAndAlwaysCleared() {
        AtomicBoolean seen = new AtomicBoolean();
        String result = ApprovalInProgress.during("CGV-00001", "PAT-00001", () -> {
            seen.set(ApprovalInProgress.isMarked("CGV-00001", "PAT-00001"));
            assertFalse(ApprovalInProgress.isMarked("CGV-00002", "PAT-00001"));
            return "done";
        });
        assertEquals("done", result);
        assertTrue(seen.get());
        assertFalse(ApprovalInProgress.isMarked("CGV-00001", "PAT-00001"));

        assertThrows(IllegalStateException.class, () -> ApprovalInProgress.during("CGV-00001", "PAT-00001", () -> {
            throw new IllegalStateException("grant failed");
        }));
        assertFalse(ApprovalInProgress.isMarked("CGV-00001", "PAT-00001"), "cleared after a failure too");
    }

    @Test
    void snapshot_reportsApprovalsOnlyForRequestedFlags() {
        DependentAccess grant = DependentAccess.builder()
                .accessLevel("CUSTOM")
                .canViewLabResults(true)
                .canMessageCareTeam(true)
                .messagingApprovedAt(Instant.now())
                .canJoinCalls(false)
                .callsApprovedAt(Instant.now())
                .expiresAt(LocalDateTime.of(2027, 11, 10, 23, 59, 59))
                .build();
        ConsentScopeResponse scope = ConsentLedgerService.snapshot(grant, true);
        assertEquals("CUSTOM", scope.getAccessLevel());
        assertTrue(scope.isCanViewLabResults());
        assertFalse(scope.isCanViewImaging());
        assertTrue(scope.isMessagingApproved());
        assertFalse(scope.isCallsApproved(), "an approval stamp without the requested flag is not in place");
        assertEquals("2027-11-10T23:59:59", scope.getExpiresAt());
        assertTrue(scope.isGuardianAttested());
    }

    @Test
    void snapshot_alwaysWritesTheExpiryWithSeconds() {
        DependentAccess onTheHour = DependentAccess.builder()
                .accessLevel("VIEW_ONLY")
                .expiresAt(LocalDateTime.of(2027, 11, 10, 10, 0))
                .build();
        assertEquals("2027-11-10T10:00:00", ConsentLedgerService.snapshot(onTheHour, false).getExpiresAt());
        DependentAccess withNanos = DependentAccess.builder()
                .accessLevel("VIEW_ONLY")
                .expiresAt(LocalDateTime.of(2027, 11, 10, 23, 59, 0, 500_000_000))
                .build();
        assertEquals("2027-11-10T23:59:00", ConsentLedgerService.snapshot(withNanos, false).getExpiresAt());
        DependentAccess noExpiry = DependentAccess.builder().accessLevel("VIEW_ONLY").build();
        assertNull(ConsentLedgerService.snapshot(noExpiry, false).getExpiresAt());
    }
}
