package com.carenexus.api.common.service;

import com.carenexus.api.core.model.DependentAccess;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The permissions added for billing, care plans, health tracking, referrals, documents, AI alerts and the care
 * team: which dependent_access flag each needs, that FULL_ACCESS grants all of them and that VIEW_ONLY blocks the
 * write ones. (Expired and revoked grants never reach isGrantedBy: see AuthorizationServiceGrantQueriesTest.)
 */
public class PatientPermissionNewConstantsTest {

    private static final List<PatientPermission> NEW = List.of(
            PatientPermission.BILLING, PatientPermission.MANAGE_BILLING, PatientPermission.CARE_PLANS,
            PatientPermission.HEALTH_METRICS, PatientPermission.MANAGE_HEALTH_METRICS, PatientPermission.REFERRALS,
            PatientPermission.DOCUMENTS, PatientPermission.MANAGE_DOCUMENTS, PatientPermission.AI_ALERTS,
            PatientPermission.CARE_TEAM);

    private static DependentAccess grant(String level, Consumer<DependentAccess> flags) {
        DependentAccess access = DependentAccess.builder()
                .patientSystemId("PAT-00001").caregiverSystemId("CGV-00001").accessLevel(level).build();
        flags.accept(access);
        return access;
    }

    private static Set<PatientPermission> grantedNew(DependentAccess access) {
        Set<PatientPermission> granted = EnumSet.noneOf(PatientPermission.class);
        for (PatientPermission p : NEW) {
            if (p.isGrantedBy(access)) granted.add(p);
        }
        return granted;
    }

    @Test
    void resourceTypesAndWriteFlags_matchTheContract() {
        assertEquals("INVOICE", PatientPermission.BILLING.getResourceType());
        assertEquals("INVOICE", PatientPermission.MANAGE_BILLING.getResourceType());
        assertEquals("CARE_PLAN", PatientPermission.CARE_PLANS.getResourceType());
        assertEquals("HEALTH_METRIC", PatientPermission.HEALTH_METRICS.getResourceType());
        assertEquals("HEALTH_METRIC", PatientPermission.MANAGE_HEALTH_METRICS.getResourceType());
        assertEquals("REFERRAL", PatientPermission.REFERRALS.getResourceType());
        assertEquals("DOCUMENT", PatientPermission.DOCUMENTS.getResourceType());
        assertEquals("DOCUMENT", PatientPermission.MANAGE_DOCUMENTS.getResourceType());
        assertEquals("AI_ALERT", PatientPermission.AI_ALERTS.getResourceType());
        assertEquals("CARE_TEAM", PatientPermission.CARE_TEAM.getResourceType());

        Set<PatientPermission> writes = EnumSet.noneOf(PatientPermission.class);
        for (PatientPermission p : NEW) {
            assertFalse(p.isCommunication(), p.name());
            if (p.isWrite()) writes.add(p);
        }
        assertEquals(EnumSet.of(PatientPermission.MANAGE_BILLING, PatientPermission.MANAGE_HEALTH_METRICS,
                PatientPermission.MANAGE_DOCUMENTS), writes);
    }

    @Test
    void fullAccess_grantsEveryNewPermission_withoutAnyFlag() {
        assertEquals(EnumSet.copyOf(NEW), grantedNew(grant("FULL_ACCESS", a -> { })));
    }

    @Test
    void customWithoutFlags_grantsOnlyCareTeam() {
        assertEquals(EnumSet.of(PatientPermission.CARE_TEAM), grantedNew(grant("CUSTOM", a -> { })));
        assertEquals(EnumSet.of(PatientPermission.CARE_TEAM), grantedNew(grant("VIEW_ONLY", a -> { })));
    }

    @Test
    void billingFlags_areIndependentOfEachOtherAndOfTheRecordFlags() {
        assertEquals(EnumSet.of(PatientPermission.BILLING, PatientPermission.CARE_TEAM),
                grantedNew(grant("CUSTOM", a -> a.setCanViewBilling(true))));
        assertEquals(EnumSet.of(PatientPermission.MANAGE_BILLING, PatientPermission.CARE_TEAM),
                grantedNew(grant("CUSTOM", a -> a.setCanManageBilling(true))));

        DependentAccess everythingButBilling = grant("CUSTOM", a -> {
            a.setCanViewRecords(true);
            a.setCanManageCarePlan(true);
            a.setCanViewAppointments(true);
            a.setCanViewMedications(true);
        });
        assertFalse(PatientPermission.BILLING.isGrantedBy(everythingButBilling));
        assertFalse(PatientPermission.MANAGE_BILLING.isGrantedBy(everythingButBilling));
    }

    @Test
    void canViewRecords_grantsTheReadPermissions() {
        assertEquals(EnumSet.of(PatientPermission.CARE_PLANS, PatientPermission.HEALTH_METRICS,
                        PatientPermission.REFERRALS, PatientPermission.DOCUMENTS, PatientPermission.AI_ALERTS,
                        PatientPermission.CARE_TEAM),
                grantedNew(grant("CUSTOM", a -> a.setCanViewRecords(true))));
    }

    @Test
    void canManageCarePlan_grantsTheWritePermissions_andCarePlanViewing() {
        assertEquals(EnumSet.of(PatientPermission.CARE_PLANS, PatientPermission.MANAGE_HEALTH_METRICS,
                        PatientPermission.MANAGE_DOCUMENTS, PatientPermission.CARE_TEAM),
                grantedNew(grant("CUSTOM", a -> a.setCanManageCarePlan(true))));
    }

    @Test
    void viewOnly_blocksEveryWritePermission_evenWithTheFlag() {
        DependentAccess access = grant("VIEW_ONLY", a -> {
            a.setCanViewBilling(true);
            a.setCanManageBilling(true);
            a.setCanViewRecords(true);
            a.setCanManageCarePlan(true);
        });
        assertEquals(EnumSet.of(PatientPermission.BILLING, PatientPermission.CARE_PLANS, PatientPermission.HEALTH_METRICS,
                        PatientPermission.REFERRALS, PatientPermission.DOCUMENTS, PatientPermission.AI_ALERTS,
                        PatientPermission.CARE_TEAM),
                grantedNew(access));
    }

    @Test
    void editOnly_allowsWritesWithTheFlag() {
        DependentAccess access = grant("EDIT_ONLY", a -> {
            a.setCanManageBilling(true);
            a.setCanManageCarePlan(true);
        });
        assertTrue(PatientPermission.MANAGE_BILLING.isGrantedBy(access));
        assertTrue(PatientPermission.MANAGE_HEALTH_METRICS.isGrantedBy(access));
        assertTrue(PatientPermission.MANAGE_DOCUMENTS.isGrantedBy(access));
        assertFalse(PatientPermission.BILLING.isGrantedBy(access), "managing does not imply the view flag");
    }

    @Test
    void nullGrant_grantsNothing_andIsRequestedByFollowsTheFlag() {
        for (PatientPermission p : NEW) {
            assertFalse(p.isGrantedBy(null), p.name());
            assertFalse(p.isRequestedBy(null), p.name());
        }
        DependentAccess access = grant("VIEW_ONLY", a -> a.setCanManageBilling(true));
        assertTrue(PatientPermission.MANAGE_BILLING.isRequestedBy(access));
        assertFalse(PatientPermission.MANAGE_BILLING.isGrantedBy(access));
    }

    @Test
    void existingConstants_areUnchanged() {
        DependentAccess billingOnly = grant("CUSTOM", a -> {
            a.setCanViewBilling(true);
            a.setCanManageBilling(true);
        });
        assertFalse(PatientPermission.RECORDS.isGrantedBy(billingOnly));
        assertFalse(PatientPermission.MANAGE_CARE_PLAN.isGrantedBy(billingOnly));
        assertFalse(PatientPermission.APPOINTMENTS.isGrantedBy(billingOnly));
        assertTrue(PatientPermission.PROFILE.isGrantedBy(billingOnly));
        assertEquals("CARE_PLAN", PatientPermission.MANAGE_CARE_PLAN.getResourceType());
    }
}
