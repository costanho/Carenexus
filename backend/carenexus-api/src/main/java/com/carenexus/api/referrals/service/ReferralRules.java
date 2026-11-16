package com.carenexus.api.referrals.service;

import com.carenexus.api.common.service.AuthorizationService;
import com.carenexus.api.referrals.model.Referral;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The referral workflow rules, free of I/O so they are unit-tested on their own and shared by the write
 * endpoints and the {@code allowedActions} of every response.
 *
 * Transitions: PENDING to ACCEPTED, DECLINED or CANCELLED; ACCEPTED to COMPLETED. Everything else is illegal
 * (409), except a request for the current status, which the service treats as a no-op.
 *
 * Responder rule for accept, decline and complete, in this order:
 * <ol>
 *   <li>role neither DOCTOR nor ADMIN: denied;</li>
 *   <li>ADMIN: allowed for any referral, on the specialist's behalf;</li>
 *   <li>DOCTOR, referral with a platform specialist: only that specialist (acting as themself);</li>
 *   <li>DOCTOR, referral without a platform specialist (unassigned or external): only the referring doctor, on the
 *       specialist's behalf.</li>
 * </ol>
 */
public final class ReferralRules {

    public static final String TYPE_PLATFORM = "PLATFORM";
    public static final String TYPE_EXTERNAL = "EXTERNAL";
    public static final String TYPE_UNASSIGNED = "UNASSIGNED";

    public static final String ACTION_ACCEPT = "ACCEPT";
    public static final String ACTION_DECLINE = "DECLINE";
    public static final String ACTION_COMPLETE = "COMPLETE";
    public static final String ACTION_CANCEL = "CANCEL";
    public static final String ACTION_ASSIGN_SPECIALIST = "ASSIGN_SPECIALIST";

    static final String ROLE_DENIED = "Your role is not authorized to perform this action";
    static final String ONLY_NAMED_SPECIALIST = "Only the specialist named on this referral can respond to it";
    static final String ONLY_REFERRING_DOCTOR_OUTCOME = "Only the referring doctor can record the outcome of this referral";

    /** Allowed target statuses per current status. */
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            Referral.STATUS_PENDING, Set.of(Referral.STATUS_ACCEPTED, Referral.STATUS_DECLINED, Referral.STATUS_CANCELLED),
            Referral.STATUS_ACCEPTED, Set.of(Referral.STATUS_COMPLETED),
            Referral.STATUS_DECLINED, Set.of(),
            Referral.STATUS_COMPLETED, Set.of(),
            Referral.STATUS_CANCELLED, Set.of());

    /** Outcome of the responder rule for one caller and referral. */
    public enum Responder {
        /** The named platform specialist acts as themself (note optional). */
        SPECIALIST,
        /** An administrator, or the referring doctor of a referral without a platform specialist (note required). */
        ON_BEHALF,
        /** Role other than DOCTOR or ADMIN. */
        DENIED_ROLE,
        /** A doctor other than the named platform specialist. */
        DENIED_NOT_SPECIALIST,
        /** A doctor other than the referring doctor, on a referral without a platform specialist. */
        DENIED_NOT_REFERRING;

        public boolean allowed() {
            return this == SPECIALIST || this == ON_BEHALF;
        }

        /** Client message of a denial; null when allowed. */
        public String denialMessage() {
            return switch (this) {
                case DENIED_ROLE -> ROLE_DENIED;
                case DENIED_NOT_SPECIALIST -> ONLY_NAMED_SPECIALIST;
                case DENIED_NOT_REFERRING -> ONLY_REFERRING_DOCTOR_OUTCOME;
                default -> null;
            };
        }
    }

    private ReferralRules() {
    }

    /** PLATFORM, EXTERNAL or UNASSIGNED. */
    public static String specialistType(Referral referral) {
        if (referral.getSpecialistSystemId() != null) {
            return TYPE_PLATFORM;
        }
        if (referral.getExternalSpecialistName() != null) {
            return TYPE_EXTERNAL;
        }
        return TYPE_UNASSIGNED;
    }

    /** The responder rule (accept, decline, complete) for this caller. */
    public static Responder responder(String role, String callerSystemId, Referral referral) {
        if (AuthorizationService.ADMIN.equals(role)) {
            return Responder.ON_BEHALF;
        }
        if (!AuthorizationService.DOCTOR.equals(role)) {
            return Responder.DENIED_ROLE;
        }
        if (referral.getSpecialistSystemId() != null) {
            return referral.getSpecialistSystemId().equals(callerSystemId)
                    ? Responder.SPECIALIST : Responder.DENIED_NOT_SPECIALIST;
        }
        return referral.getReferringDoctorSystemId().equals(callerSystemId)
                ? Responder.ON_BEHALF : Responder.DENIED_NOT_REFERRING;
    }

    /** The caller is the DOCTOR who created the referral. */
    public static boolean isReferringDoctor(String role, String callerSystemId, Referral referral) {
        return AuthorizationService.DOCTOR.equals(role) && referral.getReferringDoctorSystemId().equals(callerSystemId);
    }

    /** True when {@code from -> to} is in the transition table (a same-status request is not a transition). */
    public static boolean canTransition(String from, String to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    /** {@code "This referral is declined and cannot be accepted"}. */
    public static String illegalTransitionMessage(String currentStatus, String verbPastParticiple) {
        return "This referral is " + currentStatus.toLowerCase(Locale.ROOT) + " and cannot be " + verbPastParticiple;
    }

    /**
     * What the caller may do now: a subset of ACCEPT, DECLINE, COMPLETE, CANCEL, ASSIGN_SPECIALIST in that order.
     * Patients and caregivers always get an empty list.
     */
    public static List<String> allowedActions(String role, String callerSystemId, Referral referral) {
        List<String> actions = new ArrayList<>(5);
        String status = referral.getStatus();
        boolean responds = responder(role, callerSystemId, referral).allowed();
        boolean referring = isReferringDoctor(role, callerSystemId, referral);
        boolean pending = Referral.STATUS_PENDING.equals(status);
        if (pending && responds) {
            actions.add(ACTION_ACCEPT);
            actions.add(ACTION_DECLINE);
        }
        if (Referral.STATUS_ACCEPTED.equals(status) && responds) {
            actions.add(ACTION_COMPLETE);
        }
        if (pending && referring) {
            actions.add(ACTION_CANCEL);
        }
        if (pending && (referring || AuthorizationService.ADMIN.equals(role))) {
            actions.add(ACTION_ASSIGN_SPECIALIST);
        }
        return actions;
    }

    /** True when the caller would act on the specialist's behalf (a note is then required on accept and complete). */
    public static boolean outcomeNoteRequired(String role, String callerSystemId, Referral referral) {
        return responder(role, callerSystemId, referral) == Responder.ON_BEHALF;
    }
}
