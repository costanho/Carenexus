package com.carenexus.api.ai.alert;

/**
 * What a generator or the detection pipeline asks {@link AiAlertService#raise} to store (CONTRACTS.v2 6.1, frozen).
 *
 * @param patientSystemId the patient the alert is about (required, an existing patient)
 * @param doctorSystemId  the doctor it is addressed to; null = one alert for every doctor related to the patient
 * @param alertType       one of {@code AiAlertType} (required)
 * @param priority        LOW, MEDIUM, HIGH or CRITICAL (required)
 * @param title           short heading, at most 160 characters (longer is cut); null or blank = default of the type
 * @param message         alert text (required). For MISSED_MEDICATION, FOLLOW_UP_DUE and confirmed DISEASE_RISK
 *                        alerts it is shown verbatim to the patient and permitted caregivers
 * @param actionRequired  the raiser marks the alert as needing clinician action
 * @param suggestedAction clinician-directed suggestion; never shown to patients or caregivers
 * @param sourceType      one of {@code AiAlertSourceType}; set together with {@code sourceId}, or both null
 * @param sourceId        id of the source record
 * @param detectionId     {@code disease_detection.detection_id}; required for DISEASE_RISK
 * @param dedupeKey       at most 160 characters; a repeat inserts nothing. Null always inserts
 */
public record AiAlertCommand(String patientSystemId, String doctorSystemId, String alertType,
                             String priority, String title, String message, boolean actionRequired,
                             String suggestedAction, String sourceType, Integer sourceId, Integer detectionId,
                             String dedupeKey) {
}
