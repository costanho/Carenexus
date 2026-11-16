package com.carenexus.api.referrals;

import com.carenexus.api.referrals.model.Referral;
import com.carenexus.api.referrals.service.ReferralRules;
import com.carenexus.api.referrals.service.ReferralRules.Responder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The workflow table, the responder rule and allowedActions, without Spring.
 */
class ReferralRulesTest {

    private static final String REFERRING = "DOC-00001";
    private static final String SPECIALIST = "DOC-00003";
    private static final String OTHER_DOCTOR = "DOC-00002";
    private static final String ADMIN = "ADM-00001";
    private static final String PATIENT = "PAT-00001";

    private static Referral referral(String status, String specialist, String externalName) {
        return Referral.builder()
                .referralId(1)
                .patientSystemId(PATIENT)
                .referringDoctorSystemId(REFERRING)
                .specialistSystemId(specialist)
                .externalSpecialistName(externalName)
                .status(status)
                .priority(Referral.PRIORITY_ROUTINE)
                .reason("r")
                .build();
    }

    @Test
    void transitionTableAllowsOnlyTheWorkflowEdges() {
        for (String from : Referral.STATUSES) {
            for (String to : Referral.STATUSES) {
                boolean expected = (from.equals("PENDING") && List.of("ACCEPTED", "DECLINED", "CANCELLED").contains(to))
                        || (from.equals("ACCEPTED") && to.equals("COMPLETED"));
                assertThat(ReferralRules.canTransition(from, to)).as(from + " -> " + to).isEqualTo(expected);
            }
        }
    }

    @Test
    void illegalTransitionMessageUsesLowerCaseStatus() {
        assertThat(ReferralRules.illegalTransitionMessage("DECLINED", "accepted"))
                .isEqualTo("This referral is declined and cannot be accepted");
        assertThat(ReferralRules.illegalTransitionMessage("PENDING", "completed"))
                .isEqualTo("This referral is pending and cannot be completed");
    }

    @Test
    void specialistTypeIsDerivedFromTheColumns() {
        assertThat(ReferralRules.specialistType(referral("PENDING", SPECIALIST, null))).isEqualTo("PLATFORM");
        assertThat(ReferralRules.specialistType(referral("PENDING", null, "Dr. Peter Moyo"))).isEqualTo("EXTERNAL");
        assertThat(ReferralRules.specialistType(referral("PENDING", null, null))).isEqualTo("UNASSIGNED");
    }

    @Test
    void responderRuleForAPlatformSpecialist() {
        Referral platform = referral("PENDING", SPECIALIST, null);
        assertThat(ReferralRules.responder("DOCTOR", SPECIALIST, platform)).isEqualTo(Responder.SPECIALIST);
        assertThat(ReferralRules.responder("DOCTOR", REFERRING, platform)).isEqualTo(Responder.DENIED_NOT_SPECIALIST);
        assertThat(ReferralRules.responder("DOCTOR", OTHER_DOCTOR, platform)).isEqualTo(Responder.DENIED_NOT_SPECIALIST);
        assertThat(ReferralRules.responder("ADMIN", ADMIN, platform)).isEqualTo(Responder.ON_BEHALF);
        assertThat(ReferralRules.responder("PATIENT", PATIENT, platform)).isEqualTo(Responder.DENIED_ROLE);
        assertThat(ReferralRules.responder("CAREGIVER", "CGV-00001", platform)).isEqualTo(Responder.DENIED_ROLE);
        assertThat(Responder.DENIED_NOT_SPECIALIST.denialMessage())
                .isEqualTo("Only the specialist named on this referral can respond to it");
    }

    @Test
    void responderRuleWithoutAPlatformSpecialist() {
        for (Referral r : List.of(referral("PENDING", null, null), referral("PENDING", null, "Dr. Peter Moyo"))) {
            assertThat(ReferralRules.responder("DOCTOR", REFERRING, r)).isEqualTo(Responder.ON_BEHALF);
            assertThat(ReferralRules.responder("DOCTOR", OTHER_DOCTOR, r)).isEqualTo(Responder.DENIED_NOT_REFERRING);
            assertThat(ReferralRules.responder("ADMIN", ADMIN, r)).isEqualTo(Responder.ON_BEHALF);
        }
        assertThat(Responder.DENIED_NOT_REFERRING.denialMessage())
                .isEqualTo("Only the referring doctor can record the outcome of this referral");
        assertThat(Responder.DENIED_ROLE.denialMessage()).isEqualTo("Your role is not authorized to perform this action");
    }

    @Test
    void allowedActionsOfAPendingPlatformReferral() {
        Referral r = referral("PENDING", SPECIALIST, null);
        assertThat(ReferralRules.allowedActions("DOCTOR", REFERRING, r)).containsExactly("CANCEL", "ASSIGN_SPECIALIST");
        assertThat(ReferralRules.allowedActions("DOCTOR", SPECIALIST, r)).containsExactly("ACCEPT", "DECLINE");
        assertThat(ReferralRules.allowedActions("ADMIN", ADMIN, r)).containsExactly("ACCEPT", "DECLINE", "ASSIGN_SPECIALIST");
        assertThat(ReferralRules.allowedActions("DOCTOR", OTHER_DOCTOR, r)).isEmpty();
        assertThat(ReferralRules.allowedActions("PATIENT", PATIENT, r)).isEmpty();
        assertThat(ReferralRules.allowedActions("CAREGIVER", "CGV-00001", r)).isEmpty();
        assertThat(ReferralRules.outcomeNoteRequired("DOCTOR", SPECIALIST, r)).isFalse();
        assertThat(ReferralRules.outcomeNoteRequired("DOCTOR", REFERRING, r)).isFalse();
        assertThat(ReferralRules.outcomeNoteRequired("ADMIN", ADMIN, r)).isTrue();
    }

    @Test
    void allowedActionsOfAnUnassignedReferral() {
        Referral r = referral("PENDING", null, null);
        assertThat(ReferralRules.allowedActions("DOCTOR", REFERRING, r))
                .containsExactly("ACCEPT", "DECLINE", "CANCEL", "ASSIGN_SPECIALIST");
        assertThat(ReferralRules.outcomeNoteRequired("DOCTOR", REFERRING, r)).isTrue();
        assertThat(ReferralRules.allowedActions("DOCTOR", OTHER_DOCTOR, r)).isEmpty();
    }

    @Test
    void allowedActionsAfterAcceptanceAndInTerminalStates() {
        Referral accepted = referral("ACCEPTED", SPECIALIST, null);
        assertThat(ReferralRules.allowedActions("DOCTOR", SPECIALIST, accepted)).containsExactly("COMPLETE");
        assertThat(ReferralRules.allowedActions("DOCTOR", REFERRING, accepted)).isEmpty();
        assertThat(ReferralRules.allowedActions("ADMIN", ADMIN, accepted)).containsExactly("COMPLETE");

        for (String terminal : List.of("DECLINED", "COMPLETED", "CANCELLED")) {
            Referral r = referral(terminal, SPECIALIST, null);
            assertThat(ReferralRules.allowedActions("DOCTOR", SPECIALIST, r)).isEmpty();
            assertThat(ReferralRules.allowedActions("DOCTOR", REFERRING, r)).isEmpty();
            assertThat(ReferralRules.allowedActions("ADMIN", ADMIN, r)).isEmpty();
        }
    }
}
