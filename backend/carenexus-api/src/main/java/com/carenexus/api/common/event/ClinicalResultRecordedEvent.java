package com.carenexus.api.common.event;

/**
 * Published by LabResultService and ImagingResultService on create, update, status change and review.
 * {@code kind} is {@link #LAB} or {@link #IMAGING}; {@code created} is true for a new row; {@code newlyCritical} is true
 * only when this change made the result critical. Carries no result values.
 */
public record ClinicalResultRecordedEvent(
        String kind, Integer resultId, String patientSystemId, String doctorSystemId, String actorSystemId,
        String status, boolean critical, boolean newlyCritical, boolean created) {

    public static final String LAB = "LAB";
    public static final String IMAGING = "IMAGING";
}
