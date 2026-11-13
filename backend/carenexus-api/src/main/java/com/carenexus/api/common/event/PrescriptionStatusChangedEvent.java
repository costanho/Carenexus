package com.carenexus.api.common.event;

/**
 * Published by PrescriptionService (and the expiry sweeper) when a prescription changes status
 * (for example ACTIVE to COMPLETED, CANCELLED or EXPIRED).
 */
public record PrescriptionStatusChangedEvent(
        Integer prescriptionId, String patientSystemId, String doctorSystemId, String previousStatus, String status) {
}
