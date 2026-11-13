package com.carenexus.api.communications.notify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The web deep-link contract: every target for every role (section key and fixed tab), the role segment, and the
 * patient, id and tab parameters. "-" means the role has no section for the target (informational notification).
 */
class NotificationLinksTest {

    private static final String PAT = "PAT-00001";

    /** The expected link of a target for one role, with patient PAT-00001 and resource id 7. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            APPOINTMENTS           | section=my-appointments&id=7                  | section=appointments&id=7                          | section=appointments&patient=PAT-00001&id=7                         | -
            CONSULTATIONS          | section=consultation-history&id=7             | section=consultations&id=7                         | section=documents-results&patient=PAT-00001&id=7&tab=consultations  | -
            PRESCRIPTIONS          | section=prescriptions&id=7                    | section=prescriptions&id=7                         | section=medications&patient=PAT-00001&id=7                          | -
            MEDICATION_SCHEDULE    | section=prescriptions&id=7&tab=schedule       | section=prescriptions&id=7                         | section=medications&patient=PAT-00001&id=7&tab=schedule             | -
            LAB_RESULTS            | section=lab-results&id=7                      | section=labs&id=7                                  | section=documents-results&patient=PAT-00001&id=7&tab=labs           | -
            IMAGING_RESULTS        | section=imaging-results&id=7                  | section=imaging&id=7                               | section=documents-results&patient=PAT-00001&id=7&tab=imaging        | -
            MEDICAL_RECORDS        | section=medical-records&id=7                  | section=medical-records&id=7                       | section=documents-results&patient=PAT-00001&id=7&tab=records        | -
            DOCUMENTS              | section=documents&id=7                        | section=documents&id=7                             | section=documents-results&patient=PAT-00001&id=7&tab=documents      | -
            REFERRALS              | section=referrals&id=7                        | section=referrals&id=7                             | section=documents-results&patient=PAT-00001&id=7&tab=referrals      | -
            CARE_PLANS             | section=care-plans&id=7                       | section=care-plans&id=7                            | section=care-plans&patient=PAT-00001&id=7                           | -
            HEALTH_TRACKING        | section=health-tracking&id=7                  | section=patients&patient=PAT-00001&id=7&tab=vitals | section=health-summary&patient=PAT-00001&id=7                       | -
            AI_ALERTS              | section=health-alerts&id=7                    | section=ai-alerts&id=7                             | section=health-alerts&patient=PAT-00001&id=7                        | -
            BILLING                | section=billing&id=7                          | section=billing&id=7                               | section=billing&patient=PAT-00001&id=7                              | section=billing&id=7
            CAREGIVER_ACCESS       | section=my-proxies&id=7                       | -                                                  | section=profile-settings&patient=PAT-00001&id=7                     | -
            CARE_TEAM              | section=care-team&id=7                        | section=patients&patient=PAT-00001&id=7            | section=patient-profile&patient=PAT-00001&id=7                      | -
            MESSAGES               | section=messages&id=7                         | section=messages&id=7                              | section=messages&patient=PAT-00001&id=7                             | section=messages&id=7
            ACCOUNT_SECURITY       | section=profile-settings&id=7&tab=security    | section=profile&id=7&tab=security                  | section=profile-settings&patient=PAT-00001&id=7&tab=security        | section=account&id=7
            CORRECTIONS            | section=medical-records&id=7&tab=corrections  | section=medical-records&id=7&tab=corrections       | section=documents-results&patient=PAT-00001&id=7&tab=corrections    | section=corrections&id=7
            ADMIN_CLINICIAN_QUEUE  | -                                             | -                                                  | -                                                                   | section=clinician-queue&id=7
            ADMIN_ACCOUNT_REQUESTS | -                                             | -                                                  | -                                                                   | section=account-requests&id=7
            ADMIN_BILLING          | -                                             | -                                                  | -                                                                   | section=billing&id=7
            """)
    void everyTarget_forEveryRole(NotificationTarget target, String patient, String doctor, String proxy, String admin) {
        assertEquals(expected("patient", patient), NotificationLinks.url("PATIENT", target, null, PAT, null, 7), "patient");
        assertEquals(expected("doctor", doctor), NotificationLinks.url("DOCTOR", target, null, PAT, null, 7), "doctor");
        assertEquals(expected("proxy", proxy), NotificationLinks.url("CAREGIVER", target, null, PAT, null, 7), "proxy");
        assertEquals(expected("admin", admin), NotificationLinks.url("ADMIN", target, null, PAT, null, 7), "admin");
    }

    private static String expected(String role, String query) {
        return "-".equals(query) ? null : "/dashboard/" + role + "?" + query;
    }

    @Test
    void theTableCoversEveryTarget() {
        // A target added to the enum without a table row would silently produce informational notifications
        for (NotificationTarget target : NotificationTarget.values()) {
            boolean linked = false;
            for (String role : new String[]{"PATIENT", "DOCTOR", "CAREGIVER", "ADMIN"}) {
                linked |= NotificationLinks.url(role, target, null, null) != null;
            }
            assertEquals(true, linked, target + " has no section for any role");
        }
    }

    @Test
    void roleSegment_caregiverIsProxy_caseIsIgnored_unknownRoleHasNoLink() {
        assertEquals("/dashboard/proxy?section=appointments", NotificationLinks.url("caregiver", NotificationTarget.APPOINTMENTS, null, null));
        assertEquals("/dashboard/patient?section=my-appointments", NotificationLinks.url(" Patient ", NotificationTarget.APPOINTMENTS, null, null));
        assertEquals("/dashboard/doctor?section=appointments", NotificationLinks.url("DOCTOR", NotificationTarget.APPOINTMENTS, null, null));
        assertEquals("/dashboard/admin?section=messages", NotificationLinks.url("ADMIN", NotificationTarget.MESSAGES, null, null));
        assertNull(NotificationLinks.url("NURSE", NotificationTarget.APPOINTMENTS, null, null));
        assertNull(NotificationLinks.url(null, NotificationTarget.APPOINTMENTS, null, null));
    }

    @Test
    void noTarget_isInformational() {
        assertNull(NotificationLinks.url("PATIENT", null, Map.of("id", "5"), PAT));
    }

    @Test
    void patientParameter_onlyForCaregivers_andForDoctorTargetsThatOpenOnePatient() {
        // Patient and admin recipients never carry it
        assertEquals("/dashboard/patient?section=lab-results", NotificationLinks.url("PATIENT", NotificationTarget.LAB_RESULTS, null, PAT));
        assertEquals("/dashboard/admin?section=billing", NotificationLinks.url("ADMIN", NotificationTarget.ADMIN_BILLING, null, PAT));
        // Caregiver: every target, but only when a patient is known
        assertEquals("/dashboard/proxy?section=medications&patient=PAT-00001&tab=schedule",
                NotificationLinks.url("CAREGIVER", NotificationTarget.MEDICATION_SCHEDULE, null, PAT));
        assertEquals("/dashboard/proxy?section=medications&tab=schedule",
                NotificationLinks.url("CAREGIVER", NotificationTarget.MEDICATION_SCHEDULE, null, null));
        assertEquals("/dashboard/proxy?section=appointments",
                NotificationLinks.url("CAREGIVER", NotificationTarget.APPOINTMENTS, null, "  "));
        // Doctor: only the targets that open one patient; elsewhere it would switch on the list filter
        assertEquals("/dashboard/doctor?section=patients&patient=PAT-00001&tab=vitals",
                NotificationLinks.url("DOCTOR", NotificationTarget.HEALTH_TRACKING, null, PAT));
        assertEquals("/dashboard/doctor?section=patients&patient=PAT-00001",
                NotificationLinks.url("DOCTOR", NotificationTarget.CARE_TEAM, null, PAT));
        assertEquals("/dashboard/doctor?section=referrals&id=12",
                NotificationLinks.url("DOCTOR", NotificationTarget.REFERRALS, null, PAT, "REFERRAL", 12));
        assertEquals("/dashboard/doctor?section=care-plans&id=3",
                NotificationLinks.url("DOCTOR", NotificationTarget.CARE_PLANS, Map.of("id", "3"), PAT));
    }

    @Test
    void idParameter_paramsIdWinsOverTheResourceId_andIsOmittedWhenBothAreMissing() {
        assertEquals("/dashboard/patient?section=my-appointments&id=412",
                NotificationLinks.url("PATIENT", NotificationTarget.APPOINTMENTS, null, PAT, "APPOINTMENT", 412));
        assertEquals("/dashboard/patient?section=my-appointments&id=99",
                NotificationLinks.url("PATIENT", NotificationTarget.APPOINTMENTS, Map.of("id", "99"), PAT, "APPOINTMENT", 412));
        assertEquals("/dashboard/patient?section=my-appointments",
                NotificationLinks.url("PATIENT", NotificationTarget.APPOINTMENTS, Map.of(), PAT, "APPOINTMENT", null));
        assertEquals("/dashboard/patient?section=my-appointments&id=412",
                NotificationLinks.url("PATIENT", NotificationTarget.APPOINTMENTS, Map.of("id", " "), PAT, null, 412));
    }

    @Test
    void tabParameter_theFixedTabWins_paramsTabIsUsedOnlyWhenTheTableFixesNone() {
        assertEquals("/dashboard/patient?section=prescriptions&tab=reminders",
                NotificationLinks.url("PATIENT", NotificationTarget.PRESCRIPTIONS, Map.of("tab", "reminders"), PAT));
        assertEquals("/dashboard/proxy?section=medications&patient=PAT-00001&tab=reminders",
                NotificationLinks.url("CAREGIVER", NotificationTarget.PRESCRIPTIONS, Map.of("tab", "reminders"), PAT));
        assertEquals("/dashboard/patient?section=prescriptions&tab=schedule",
                NotificationLinks.url("PATIENT", NotificationTarget.MEDICATION_SCHEDULE, Map.of("tab", "reminders"), PAT));
        assertEquals("/dashboard/patient?section=my-proxies&tab=requests",
                NotificationLinks.url("PATIENT", NotificationTarget.CAREGIVER_ACCESS, Map.of("tab", "requests"), PAT));
    }

    @Test
    void otherParams_arePassedThroughSortedByKey_sectionAndPatientKeysAreIgnored() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("thread", "77");
        params.put("section", "users");
        params.put("patient", "PAT-00002");
        params.put("mode", "video");
        params.put("appointmentId", "412");
        params.put("date", "2026-11-12");
        params.put("empty", " ");

        assertEquals("/dashboard/admin?section=messages&thread=77",
                NotificationLinks.url("ADMIN", NotificationTarget.MESSAGES, Map.of("thread", "77"), null));
        assertEquals("/dashboard/proxy?section=appointments&patient=PAT-00001&id=412"
                        + "&appointmentId=412&date=2026-11-12&mode=video&thread=77",
                NotificationLinks.url("CAREGIVER", NotificationTarget.APPOINTMENTS, params, PAT, "APPOINTMENT", 412));
        assertEquals("/dashboard/patient?section=my-appointments&id=412"
                        + "&appointmentId=412&date=2026-11-12&mode=video&thread=77",
                NotificationLinks.url("PATIENT", NotificationTarget.APPOINTMENTS, params, PAT, "APPOINTMENT", 412));
    }

    @Test
    void valuesArePercentEncoded_spaceIsPercent20() {
        assertEquals("/dashboard/doctor?section=appointments&date=2026-11-12T09%3A00&q=a%20b%26c",
                NotificationLinks.url("DOCTOR", NotificationTarget.APPOINTMENTS,
                        Map.of("date", "2026-11-12T09:00", "q", "a b&c"), null));
    }

    @Test
    void caregiverAccess_forACaregiver_opensAccessRequestsOnlyForAnAccessRequest() {
        assertEquals("/dashboard/proxy?section=access-requests&id=15",
                NotificationLinks.url("CAREGIVER", NotificationTarget.CAREGIVER_ACCESS, null, null, "ACCESS_REQUEST", 15));
        assertEquals("/dashboard/proxy?section=access-requests&id=15",
                NotificationLinks.url("CAREGIVER", NotificationTarget.CAREGIVER_ACCESS, null, null, "access_request", 15));
        assertEquals("/dashboard/proxy?section=profile-settings&patient=PAT-00001",
                NotificationLinks.url("CAREGIVER", NotificationTarget.CAREGIVER_ACCESS, null, PAT, "DEPENDENT_ACCESS", null));
        assertEquals("/dashboard/proxy?section=profile-settings&patient=PAT-00001",
                NotificationLinks.url("CAREGIVER", NotificationTarget.CAREGIVER_ACCESS, null, PAT));
        // The patient's link does not depend on the resource type, and a doctor has no such section
        assertEquals("/dashboard/patient?section=my-proxies&id=15",
                NotificationLinks.url("PATIENT", NotificationTarget.CAREGIVER_ACCESS, null, PAT, "ACCESS_REQUEST", 15));
        assertNull(NotificationLinks.url("DOCTOR", NotificationTarget.CAREGIVER_ACCESS, null, PAT, "ACCESS_REQUEST", 15));
    }
}
