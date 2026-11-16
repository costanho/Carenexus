package com.carenexus.api.common.event;

/**
 * Published by the AI module after an alert was raised for a patient. {@code doctorSystemId} is the doctor the
 * alert is addressed to and may be null (then the care team is notified).
 */
public record AiAlertRaisedEvent(
        Integer alertId, String patientSystemId, String doctorSystemId, String alertType, String priority) {
}
