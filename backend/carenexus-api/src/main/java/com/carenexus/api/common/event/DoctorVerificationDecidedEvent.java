package com.carenexus.api.common.event;

/**
 * Published by the admin module when a clinician verification was decided. {@code status} is the doctor's new
 * verification status (APPROVED, REJECTED, SUSPENDED); {@code reason} is set for rejections and suspensions.
 */
public record DoctorVerificationDecidedEvent(
        String doctorSystemId, String adminSystemId, String status, String reason) {
}
