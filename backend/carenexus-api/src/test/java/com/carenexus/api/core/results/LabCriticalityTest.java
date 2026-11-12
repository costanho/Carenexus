package com.carenexus.api.core.results;

import com.carenexus.api.common.exception.BadRequestException;
import com.carenexus.api.core.service.LabResultService;
import com.carenexus.api.core.service.LabResultService.Criticality;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The lab criticality rule (contract be-lab-imaging 2.5), row by row of its table, plus the edges around it.
 */
class LabCriticalityTest {

    private static Criticality normalise(String storedStatus, boolean storedCritical, String status, Boolean critical) {
        return LabResultService.normaliseCriticality(storedStatus, storedCritical, status, critical);
    }

    @Test
    void createCompletedWithFlagBecomesCritical() {
        assertEquals(new Criticality("CRITICAL", true), normalise("PENDING", false, "COMPLETED", true));
    }

    @Test
    void createCriticalSetsFlag() {
        assertEquals(new Criticality("CRITICAL", true), normalise("PENDING", false, "CRITICAL", null));
    }

    @Test
    void createFlagWithoutStatusStaysPending() {
        assertEquals(new Criticality("PENDING", true), normalise("PENDING", false, null, true));
    }

    @Test
    void createCriticalWithFlagFalseIsRejected() {
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> normalise("PENDING", false, "CRITICAL", false));
        assertEquals("isCritical: cannot be false when status is CRITICAL", e.getMessage());
        assertEquals("VALIDATION_FAILED", e.getCode());
    }

    @Test
    void flaggedPendingSetToCompletedBecomesCritical() {
        assertEquals(new Criticality("CRITICAL", true), normalise("PENDING", true, "COMPLETED", null));
    }

    @Test
    void completedFlaggedBecomesCritical() {
        assertEquals(new Criticality("CRITICAL", true), normalise("COMPLETED", false, null, true));
    }

    @Test
    void leavingCriticalClearsFlag() {
        assertEquals(new Criticality("COMPLETED", false), normalise("CRITICAL", true, "COMPLETED", null));
        assertEquals(new Criticality("REQUIRES_REVIEW", false), normalise("CRITICAL", true, "REQUIRES_REVIEW", null));
    }

    @Test
    void unflaggingCriticalWithoutStatusCompletesIt() {
        assertEquals(new Criticality("COMPLETED", false), normalise("CRITICAL", true, null, false));
    }

    @Test
    void reviewTargetsDecideTheFlag() {
        assertEquals(new Criticality("COMPLETED", false), normalise("REQUIRES_REVIEW", true, "COMPLETED", false));
        assertEquals(new Criticality("CRITICAL", true), normalise("REQUIRES_REVIEW", false, "CRITICAL", true));
    }

    @Test
    void flagMayStayOnWhileAwaitingReview() {
        assertEquals(new Criticality("REQUIRES_REVIEW", true), normalise("PENDING", true, "REQUIRES_REVIEW", null));
    }

    @Test
    void nothingRequestedKeepsTheStoredState() {
        assertEquals(new Criticality("COMPLETED", false), normalise("COMPLETED", false, null, null));
        assertEquals(new Criticality("CRITICAL", true), normalise("CRITICAL", true, null, null));
    }
}
