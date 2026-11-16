package com.carenexus.api.common.event;

/**
 * Published by the health module after a symptom entry was stored. Carries no symptom text.
 */
public record SymptomLoggedEvent(
        Integer symptomId, String patientSystemId, String loggedBySystemId, String severity) {
}
