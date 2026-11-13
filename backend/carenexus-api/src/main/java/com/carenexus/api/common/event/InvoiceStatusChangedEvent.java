package com.carenexus.api.common.event;

/**
 * Published by the billing module when an invoice changes status (for example SENT to PAID, OVERDUE or CANCELLED).
 */
public record InvoiceStatusChangedEvent(
        Integer invoiceId, String invoiceNo, String patientSystemId, String previousStatus, String status) {
}
