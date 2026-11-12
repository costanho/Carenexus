package com.carenexus.api.common.event;

/**
 * Published by the documents module after a file was stored and attached to the patient's record.
 */
public record DocumentUploadedEvent(
        Integer documentId, String patientSystemId, String uploadedBySystemId, String documentType) {
}
