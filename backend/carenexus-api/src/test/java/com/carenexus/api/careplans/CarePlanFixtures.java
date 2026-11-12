package com.carenexus.api.careplans;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Request bodies for the care-plan tests, built as JSON-ready maps so a test can also send what the DTOs cannot
 * express (null items, malformed values, unknown fields).
 */
public final class CarePlanFixtures {

    /** The clinic zone used by the test profile (carenexus.scheduling.time-zone default). */
    public static final ZoneId CLINIC_ZONE = ZoneId.of("Africa/Harare");

    private CarePlanFixtures() {
    }

    /** Today in the clinic zone. */
    public static LocalDate today() {
        return LocalDate.now(CLINIC_ZONE);
    }

    /** A valid create body: starts today, target in 6 months, review in 1 month, two goals, two interventions. */
    public static Map<String, Object> createBody(String patientSystemId) {
        return createBody(patientSystemId, "Blood pressure management");
    }

    public static Map<String, Object> createBody(String patientSystemId, String title) {
        LocalDate start = today();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("patientSystemId", patientSystemId);
        body.put("title", title);
        body.put("description", "Six-month plan agreed at the visit.");
        body.put("startDate", start.toString());
        body.put("targetDate", start.plusMonths(6).toString());
        body.put("reviewDate", start.plusMonths(1).toString());
        body.put("goals", new ArrayList<>(List.of(
                goal("Walk 30 minutes five days a week", start.plusMonths(2)),
                goal("Record blood pressure every morning", null))));
        body.put("interventions", new ArrayList<>(List.of(
                intervention("Home blood pressure monitoring", "Daily", "Patient"),
                intervention("Dietitian session on salt intake", "Once", "Dietitian"))));
        return body;
    }

    public static Map<String, Object> goal(String description, LocalDate targetDate) {
        Map<String, Object> goal = new LinkedHashMap<>();
        goal.put("description", description);
        goal.put("targetDate", targetDate == null ? null : targetDate.toString());
        return goal;
    }

    public static Map<String, Object> goal(Integer goalId, String description, LocalDate targetDate) {
        Map<String, Object> goal = new LinkedHashMap<>();
        goal.put("goalId", goalId);
        goal.put("description", description);
        goal.put("targetDate", targetDate == null ? null : targetDate.toString());
        return goal;
    }

    public static Map<String, Object> intervention(String description, String frequency, String responsible) {
        Map<String, Object> intervention = new LinkedHashMap<>();
        intervention.put("description", description);
        intervention.put("frequency", frequency);
        intervention.put("responsible", responsible);
        return intervention;
    }

    /** A PUT body with the given goals and interventions and the create body's dates. */
    public static Map<String, Object> updateBody(String title, List<Map<String, Object>> goals,
                                                 List<Map<String, Object>> interventions) {
        LocalDate start = today();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("description", "Revised plan.");
        body.put("startDate", start.toString());
        body.put("targetDate", start.plusMonths(6).toString());
        body.put("reviewDate", start.plusMonths(2).toString());
        body.put("goals", goals);
        body.put("interventions", interventions);
        return body;
    }

    public static Map<String, Object> body(Object... keyValues) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            body.put((String) keyValues[i], keyValues[i + 1]);
        }
        return body;
    }
}
