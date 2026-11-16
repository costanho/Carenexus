package com.carenexus.api.common.event;

import java.time.LocalDate;
import java.util.List;

/**
 * Published by PrescriptionService after a prescription was issued. The medication module builds the dose
 * schedule from {@code frequency}, the dates and {@code scheduleTimes} ("HH:mm" clinic wall-clock, may be empty).
 * {@code medicationName} labels schedule rows only: it must never be copied into notification text or logs.
 */
public record PrescriptionIssuedEvent(
        Integer prescriptionId, Integer consultationId, String patientSystemId, String doctorSystemId,
        String medicationName, String frequency, LocalDate startDate, LocalDate expiryDate,
        List<String> scheduleTimes) {

    /** The medication name is clinical detail: keep it out of logs. */
    @Override
    public String toString() {
        return "PrescriptionIssuedEvent[prescriptionId=" + prescriptionId + ", patientSystemId=" + patientSystemId
                + ", doctorSystemId=" + doctorSystemId + "]";
    }
}
