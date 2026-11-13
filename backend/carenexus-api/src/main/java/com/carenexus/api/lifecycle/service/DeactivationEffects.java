package com.carenexus.api.lifecycle.service;

import java.util.EnumMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Counts of one clean-up run and the units that failed per step. {@link #summary()} is the text stored in the
 * audit row's {@code error_message}:
 * <pre>
 * cause=ADMIN; role=DOCTOR; appointmentsCancelled=2; grantsRevoked=0; remindersDeactivated=0; accessRequestsCancelled=0; pendingReferrals=1; failed=none
 * </pre>
 * All eight keys are always present, in this order; {@code failed} is {@code none} or {@code <STEP>:<failed units>}
 * pairs in step order, comma-separated.
 */
public final class DeactivationEffects {

    private final String cause;
    private final String role;
    private int appointmentsCancelled;
    private int grantsRevoked;
    private int remindersDeactivated;
    private int accessRequestsCancelled;
    private long pendingReferrals;
    private final Map<DeactivationStep, Integer> failures = new EnumMap<>(DeactivationStep.class);

    public DeactivationEffects(String cause, String role) {
        this.cause = cause;
        this.role = role;
    }

    void appointmentCancelled() {
        appointmentsCancelled++;
    }

    void grantRevoked() {
        grantsRevoked++;
    }

    void remindersDeactivated(int count) {
        remindersDeactivated += Math.max(count, 0);
    }

    void accessRequestsCancelled(int count) {
        accessRequestsCancelled += Math.max(count, 0);
    }

    void pendingReferrals(long count) {
        pendingReferrals = Math.max(count, 0);
    }

    void failed(DeactivationStep step) {
        failures.merge(step, 1, Integer::sum);
    }

    public String cause() {
        return cause;
    }

    public String role() {
        return role;
    }

    public int appointmentsCancelled() {
        return appointmentsCancelled;
    }

    public int grantsRevoked() {
        return grantsRevoked;
    }

    public int remindersDeactivated() {
        return remindersDeactivated;
    }

    public int accessRequestsCancelled() {
        return accessRequestsCancelled;
    }

    public long pendingReferrals() {
        return pendingReferrals;
    }

    /** Failed units of the step (0 when none failed). */
    public int failures(DeactivationStep step) {
        return failures.getOrDefault(step, 0);
    }

    public boolean anyFailed() {
        return !failures.isEmpty();
    }

    public String summary() {
        String failed = failures.isEmpty() ? "none"
                : failures.entrySet().stream()
                        .map(e -> e.getKey().name() + ":" + e.getValue())
                        .collect(Collectors.joining(","));
        return "cause=" + cause
                + "; role=" + role
                + "; appointmentsCancelled=" + appointmentsCancelled
                + "; grantsRevoked=" + grantsRevoked
                + "; remindersDeactivated=" + remindersDeactivated
                + "; accessRequestsCancelled=" + accessRequestsCancelled
                + "; pendingReferrals=" + pendingReferrals
                + "; failed=" + failed;
    }

    @Override
    public String toString() {
        return summary();
    }
}
