package com.carenexus.api.referrals;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Request bodies for referral tests.
 */
public final class ReferralFixtures {

    public static final String BASE = "/api/referrals";

    private ReferralFixtures() {
    }

    /** A create body for the patient with the given platform specialist (null: unassigned). */
    public static Map<String, Object> createBody(String patientSystemId, String specialistSystemId, String priority) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("patientSystemId", patientSystemId);
        body.put("reason", "Recurrent chest pain on exertion");
        body.put("clinicalSummary", "Hypertension. ECG changes in V4 to V6.");
        if (priority != null) {
            body.put("priority", priority);
        }
        if (specialistSystemId != null) {
            body.put("specialistSystemId", specialistSystemId);
        }
        return body;
    }

    public static Map<String, Object> externalSpecialist(String name, String organisation, String contact) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("externalSpecialistName", name);
        body.put("externalSpecialistOrganisation", organisation);
        body.put("externalSpecialistContact", contact);
        return body;
    }

    public static Map<String, Object> note(String note) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("note", note);
        return body;
    }

    public static Map<String, Object> reason(String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        return body;
    }

    public static String url(int referralId) {
        return BASE + "/" + referralId;
    }

    public static String url(int referralId, String action) {
        return BASE + "/" + referralId + "/" + action;
    }
}
