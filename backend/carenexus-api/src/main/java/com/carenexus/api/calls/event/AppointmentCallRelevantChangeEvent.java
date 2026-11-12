package com.carenexus.api.calls.event;

/**
 * Published by AppointmentService when an appointment change affects its video call: a VIDEO appointment was
 * created, the appointment was cancelled, its start time changed, only its duration changed (DURATION_CHANGED: the
 * join window is recomputed, room and participants are kept), or its type changed (when both type and time
 * change, only TYPE_CHANGED is published). Consumed BEFORE_COMMIT by AppointmentCallListener so call session and
 * job rows commit atomically with the appointment change.
 */
public record AppointmentCallRelevantChangeEvent(Integer appointmentId, Kind kind) {
    public enum Kind { CREATED_VIDEO, CANCELLED, RESCHEDULED, DURATION_CHANGED, TYPE_CHANGED }
}
