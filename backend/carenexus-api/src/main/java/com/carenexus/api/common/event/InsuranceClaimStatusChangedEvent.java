package com.carenexus.api.common.event;

/**
 * Published by the billing module when an insurance claim changes status.
 */
public record InsuranceClaimStatusChangedEvent(
        Integer claimId, Integer invoiceId, String patientSystemId, String previousStatus, String status) {
}
