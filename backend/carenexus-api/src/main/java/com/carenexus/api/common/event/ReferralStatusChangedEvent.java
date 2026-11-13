package com.carenexus.api.common.event;

/**
 * Published by the referrals module when a referral changes status.
 */
public record ReferralStatusChangedEvent(
        Integer referralId, String patientSystemId, String referringDoctorSystemId, String specialistSystemId,
        String previousStatus, String status) {
}
