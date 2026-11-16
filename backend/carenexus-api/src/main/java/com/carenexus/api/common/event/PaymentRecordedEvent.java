package com.carenexus.api.common.event;

import java.math.BigDecimal;

/**
 * Published by the billing module when a payment reaches a final state. {@code status} is COMPLETED, FAILED or
 * REFUNDED; {@code initiatedBySystemId} is the patient, a caregiver paying on their behalf, or staff.
 */
public record PaymentRecordedEvent(
        Integer paymentId, Integer invoiceId, String patientSystemId, String initiatedBySystemId,
        BigDecimal amount, String currency, String method, String status) {

    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String REFUNDED = "REFUNDED";
}
