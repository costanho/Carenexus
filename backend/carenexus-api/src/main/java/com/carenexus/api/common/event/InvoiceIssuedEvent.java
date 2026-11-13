package com.carenexus.api.common.event;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Published by the billing module when an invoice is sent to the patient (status SENT).
 * {@code total} has scale 2; {@code currency} is an ISO-4217 code.
 */
public record InvoiceIssuedEvent(
        Integer invoiceId, String invoiceNo, String patientSystemId, String doctorSystemId, BigDecimal total,
        String currency, LocalDate dueDate) {
}
