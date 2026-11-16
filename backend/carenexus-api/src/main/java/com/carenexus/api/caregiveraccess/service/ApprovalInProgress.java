package com.carenexus.api.caregiveraccess.service;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Marks "an access request for this caregiver and patient is being approved" on the current thread while
 * CaregiverService creates the grant. The consent-ledger listener runs synchronously inside that call and skips the
 * ACCESS_CHANGE "Access granted" notification for the marked pair, because the approval sends its own request
 * notification instead. The mark is always removed in {@code finally}, so it never leaks to a later request served
 * by the same thread.
 */
public final class ApprovalInProgress {

    private static final ThreadLocal<Set<String>> PAIRS = ThreadLocal.withInitial(HashSet::new);

    private ApprovalInProgress() {
    }

    /** Runs {@code body} with the pair marked. */
    public static <T> T during(String caregiverSystemId, String patientSystemId, Supplier<T> body) {
        String key = key(caregiverSystemId, patientSystemId);
        Set<String> pairs = PAIRS.get();
        boolean added = pairs.add(key);
        try {
            return body.get();
        } finally {
            if (added) {
                pairs.remove(key);
            }
            if (pairs.isEmpty()) {
                PAIRS.remove();
            }
        }
    }

    /** True while an approval for this caregiver and patient runs on the current thread. */
    public static boolean isMarked(String caregiverSystemId, String patientSystemId) {
        Set<String> pairs = PAIRS.get();
        boolean marked = pairs.contains(key(caregiverSystemId, patientSystemId));
        if (pairs.isEmpty()) {
            PAIRS.remove();
        }
        return marked;
    }

    private static String key(String caregiverSystemId, String patientSystemId) {
        return caregiverSystemId + '|' + patientSystemId;
    }
}
