package com.carenexus.api.common.event;

import java.time.LocalDate;

/**
 * Published by the care-plans module. {@code kind} is one of the constants below; {@code reviewDate} may be null.
 */
public record CarePlanChangedEvent(
        Integer planId, String patientSystemId, String doctorSystemId, String actorSystemId, String kind,
        String status, LocalDate reviewDate) {

    public static final String CREATED = "CREATED";
    public static final String UPDATED = "UPDATED";
    public static final String REVIEWED = "REVIEWED";
    public static final String STATUS_CHANGED = "STATUS_CHANGED";
}
