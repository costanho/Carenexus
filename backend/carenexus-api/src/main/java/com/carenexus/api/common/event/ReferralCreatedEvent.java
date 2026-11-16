package com.carenexus.api.common.event;

/**
 * Published by the referrals module after a referral was created.
 */
public record ReferralCreatedEvent(
        Integer referralId, String patientSystemId, String referringDoctorSystemId, String specialistSystemId,
        String priority) {
}
