package com.carenexus.api.releasegates;

import com.carenexus.api.ai.alert.AiAlertCommand;
import com.carenexus.api.ai.alert.AiAlertService;
import com.carenexus.api.ai.detection.DetectionTestSupport;
import com.carenexus.api.auth.model.User;
import com.carenexus.api.auth.support.AuthApi;
import com.carenexus.api.billing.claim.ClaimFixtures;
import com.carenexus.api.billing.invoice.InvoiceFixtures;
import com.carenexus.api.careplans.CarePlanFixtures;
import com.carenexus.api.corrections.CorrectionFixtures;
import com.carenexus.api.documents.DocumentFixtures;
import com.carenexus.api.medication.MedicationFixtures;
import com.carenexus.api.referrals.ReferralFixtures;
import com.carenexus.api.reminders.ReminderFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * Release gate for audit coverage (CONTRACTS 3.6). For every resource type of the contract list, one representative
 * read and one representative write are driven through HTTP, and each must leave a SUCCESS audit_log row with that
 * resource type, the expected action and the patient whose data was touched.
 * <ul>
 *   <li>A row without a patient is accepted only for the resource types on {@link #NON_PATIENT_RESOURCES}, each with
 *       the reason it carries none; account events about a person are then matched on target_system_id.</li>
 *   <li>A read or write that is deliberately not audited is on {@link #NOT_AUDITED}, with the reason.</li>
 *   <li>{@link #everyContractResourceType_isCoveredOrExplicitlyExempt} proves that every contract type has a
 *       test (marked {@link Covers}) or an exemption for both its read and its write, so the list cannot rot.</li>
 * </ul>
 * Each test takes the highest audit id first and looks only at rows written after it.
 */
class AuditCoverageTest extends CommittedIntegrationTest {

    /** CONTRACTS.v2 section 3.6: the resource types every slice audits under. */
    static final Set<String> CONTRACT_RESOURCE_TYPES = Set.of("CARE_TEAM", "REFERRAL", "CARE_PLAN", "DOCUMENT",
            "MEDICATION_SCHEDULE", "HEALTH_METRIC", "SYMPTOM_LOG", "AI_ALERT", "DISEASE_DETECTION", "INVOICE",
            "PAYMENT", "PAYMENT_METHOD", "INSURANCE_CLAIM", "REMINDER", "CONSENT", "ACCESS_REQUEST",
            "CORRECTION_REQUEST", "FACILITY", "SYSTEM_SETTING", "USER_SETTINGS", "DOCTOR_PROFILE", "AVAILABILITY",
            "AUDIT_LOG", "ACCOUNT", "DATA_EXPORT", "NOTIFICATION", "SUPPORT_THREAD", "LICENCE");

    /** Resource types whose rows may carry no patient, and why. */
    static final Map<String, String> NON_PATIENT_RESOURCES = Map.of(
            "FACILITY", "clinic locations are organisation data (API-NOTES: logClinicalAccess(..., 'FACILITY', id, null))",
            "SYSTEM_SETTING", "clinic-wide settings; the changed key is only in request_path",
            "SUPPORT_THREAD", "support conversations are between a user and the clinic, never about a patient record",
            "AUDIT_LOG", "an administrator's audit search spans every patient (a patient's caregiver-activity read "
                    + "does carry the patient)",
            "DOCTOR_PROFILE", "a doctor's own professional profile; the doctor is the target",
            "AVAILABILITY", "a doctor's own weekly hours and time off; the doctor is the target",
            "LICENCE", "a doctor's licence details; the doctor is the target");

    /** Reads or writes ("TYPE READ" / "TYPE WRITE") that are deliberately not audited, and why. */
    static final Map<String, String> NOT_AUDITED = Map.ofEntries(
            Map.entry("FACILITY READ", "facility lists and details are public clinic data (API-NOTES: facility "
                    + "reads are not audited)"),
            Map.entry("SYSTEM_SETTING READ", "settings are configuration, not personal data"),
            Map.entry("DOCTOR_PROFILE READ", "the directory and profiles show public professional fields only"),
            Map.entry("AVAILABILITY READ", "weekly hours and free slots are what booking shows to every user"),
            Map.entry("LICENCE READ", "the admin doctor lists that show licences are documented 'Not audited'; "
                    + "licence edits are audited"),
            Map.entry("ACCOUNT READ", "the admin account list and detail are documented 'Not audited'; every account "
                    + "change and every read of an account's audit trail is audited"),
            Map.entry("CONSENT WRITE", "ledger rows are written by grant, approval and revocation, which are audited "
                    + "under DEPENDENT_ACCESS and ACCESS_REQUEST; the ledger itself has no write endpoint"),
            Map.entry("AUDIT_LOG WRITE", "audit rows are never created or changed through the API"));

    /** Marks a test as the coverage of one resource type. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface Covers {
        String value();

        boolean read() default true;

        boolean write() default true;
    }

    private static final DateTimeFormatter WALL_CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Autowired
    private AiAlertService aiAlertService;

    // ==================== HELPERS ====================

    private long mark() {
        Long max = jdbc.queryForObject("SELECT COALESCE(MAX(audit_id), 0) FROM audit_log", Long.class);
        return max == null ? 0 : max;
    }

    /**
     * Asserts a SUCCESS row written after {@code since} with this resource type and action about {@code subject}:
     * its patient_system_id for patient data; for a type on the non-patient allow-list, a null patient and (when a
     * subject is given) target_system_id = subject.
     */
    private void assertAudited(long since, String resourceType, String action, User subject) {
        boolean nonPatient = NON_PATIENT_RESOURCES.containsKey(resourceType);
        String sql = "SELECT COUNT(*) FROM audit_log WHERE audit_id > ? AND resource_type = ? AND action = ? "
                + "AND status = 'SUCCESS' AND ";
        Long count;
        if (nonPatient) {
            if (subject == null) {
                count = jdbc.queryForObject(sql + "patient_system_id IS NULL", Long.class, since, resourceType, action);
            } else {
                count = jdbc.queryForObject(sql + "patient_system_id IS NULL AND target_system_id = ?", Long.class,
                        since, resourceType, action, subject.getSystemId());
            }
        } else {
            assertTrue(subject != null, resourceType + " is patient data: the expected patient is required");
            count = jdbc.queryForObject(sql + "patient_system_id = ?", Long.class, since, resourceType, action,
                    subject.getSystemId());
        }
        if (count == null || count == 0) {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT action, resource_type, resource_id, "
                    + "patient_system_id, target_system_id, status, request_path FROM audit_log WHERE audit_id > ? "
                    + "ORDER BY audit_id", since);
            fail("no " + action + " " + resourceType + " row about "
                    + (subject == null ? "nobody" : subject.getSystemId()) + "; rows written: " + rows);
        }
    }

    private Map<String, Object> accessRequestBody() {
        Map<String, Object> ask = new HashMap<>();
        ask.put("patientEmail", patient.getEmail());
        ask.put("relationship", "daughter");
        ask.put("accessLevel", "CUSTOM");
        ask.put("canViewLabResults", true);
        return ask;
    }

    private JsonNode ok(MvcResult result) throws Exception {
        int status = result.getResponse().getStatus();
        assertTrue(status >= 200 && status < 300, "expected 2xx, got " + status + ": "
                + result.getResponse().getContentAsString());
        return json(result);
    }

    // ==================== META ====================

    @Test
    void everyContractResourceType_isCoveredOrExplicitlyExempt() {
        Set<String> reads = new TreeSet<>();
        Set<String> writes = new TreeSet<>();
        for (Method method : AuditCoverageTest.class.getDeclaredMethods()) {
            Covers covers = method.getAnnotation(Covers.class);
            if (covers != null) {
                assertTrue(method.isAnnotationPresent(Test.class), method.getName() + " is not a test");
                if (covers.read()) {
                    reads.add(covers.value());
                }
                if (covers.write()) {
                    writes.add(covers.value());
                }
            }
        }
        Set<String> gaps = new TreeSet<>();
        for (String type : CONTRACT_RESOURCE_TYPES) {
            if (!reads.contains(type) && !NOT_AUDITED.containsKey(type + " READ")) {
                gaps.add(type + " READ");
            }
            if (!writes.contains(type) && !NOT_AUDITED.containsKey(type + " WRITE")) {
                gaps.add(type + " WRITE");
            }
        }
        assertEquals(Set.of(), gaps, "contract resource types without an audit test or an explicit exemption");
        for (String exemption : NOT_AUDITED.keySet()) {
            String type = exemption.substring(0, exemption.indexOf(' '));
            assertTrue(CONTRACT_RESOURCE_TYPES.contains(type), exemption + " is not a contract resource type");
            boolean tested = exemption.endsWith("READ") ? reads.contains(type) : writes.contains(type);
            assertTrue(!tested, exemption + " is both exempt and tested");
        }
        assertTrue(CONTRACT_RESOURCE_TYPES.containsAll(NON_PATIENT_RESOURCES.keySet()));
    }

    // ==================== CLINICAL RESOURCES ====================

    @Test
    @Covers("CARE_TEAM")
    void careTeam_assignAndList() throws Exception {
        String url = "/api/patients/" + patient.getSystemId() + "/care-team";
        long write = mark();
        ok(doPost(url, doctor, Map.of("providerSystemId", otherDoctor.getSystemId(), "role", "DOCTOR")));
        assertAudited(write, "CARE_TEAM", "CREATE", patient);

        long read = mark();
        ok(doGet(url, patient));
        assertAudited(read, "CARE_TEAM", "READ", patient);
    }

    @Test
    @Covers("REFERRAL")
    void referral_createAndRead() throws Exception {
        long write = mark();
        int id = ok(doPost(ReferralFixtures.BASE, doctor, ReferralFixtures.createBody(patient.getSystemId(), null, null)))
                .path("referralId").asInt();
        assertAudited(write, "REFERRAL", "CREATE", patient);

        long read = mark();
        ok(doGet(ReferralFixtures.BASE + "/" + id, patient));
        assertAudited(read, "REFERRAL", "READ", patient);
    }

    @Test
    @Covers("CARE_PLAN")
    void carePlan_createAndRead() throws Exception {
        long write = mark();
        int id = ok(doPost("/api/care-plans", doctor, CarePlanFixtures.createBody(patient.getSystemId())))
                .path("planId").asInt();
        assertAudited(write, "CARE_PLAN", "CREATE", patient);

        long read = mark();
        ok(doGet("/api/care-plans/" + id, patient));
        assertAudited(read, "CARE_PLAN", "READ", patient);
    }

    @Test
    @Covers("DOCUMENT")
    void document_uploadAndRead() throws Exception {
        long write = mark();
        MvcResult uploaded = call(multipart("/api/documents").file(DocumentFixtures.pdf("referral-letter.pdf"))
                .param("patientSystemId", patient.getSystemId())
                .param("documentType", "OTHER")
                .param("title", "Referral letter"), patient, null);
        int id = ok(uploaded).path("documentId").asInt();
        assertAudited(write, "DOCUMENT", "CREATE", patient);

        long read = mark();
        ok(doGet("/api/documents/" + id, doctor));
        assertAudited(read, "DOCUMENT", "READ", patient);
    }

    @Test
    @Covers("MEDICATION_SCHEDULE")
    void medicationSchedule_markADoseAndReadTheDay() throws Exception {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        int rx = MedicationFixtures.insertPrescription(jdbc, patient.getSystemId(), doctor.getSystemId(), "Metformin",
                "Twice daily", "ACTIVE", yesterday.minusDays(5), null);
        int dose = MedicationFixtures.insertDose(jdbc, rx, patient.getSystemId(), yesterday, LocalTime.of(20, 0),
                "PENDING", null);

        long write = mark();
        ok(doPatch("/api/medication-schedules/" + dose + "/taken", patient, null));
        assertAudited(write, "MEDICATION_SCHEDULE", "UPDATE", patient);

        long read = mark();
        ok(doGet("/api/medication-schedules/patient/" + patient.getSystemId() + "?date=" + yesterday, doctor));
        assertAudited(read, "MEDICATION_SCHEDULE", "READ", patient);
    }

    @Test
    @Covers("HEALTH_METRIC")
    void healthMetric_recordAndReadLatest() throws Exception {
        String at = LocalDateTime.now().minusMinutes(5).withNano(0).format(WALL_CLOCK);
        long write = mark();
        ok(doPost("/api/health-metrics", patient, Map.of("readings",
                List.of(Map.of("type", "WEIGHT", "value", 72.5, "recordedAt", at)))));
        assertAudited(write, "HEALTH_METRIC", "CREATE", patient);

        long read = mark();
        ok(doGet("/api/health-metrics/patient/" + patient.getSystemId() + "/latest", doctor));
        assertAudited(read, "HEALTH_METRIC", "READ", patient);
    }

    @Test
    @Covers("SYMPTOM_LOG")
    void symptomLog_logAndRead() throws Exception {
        long write = mark();
        int id = ok(doPost("/api/symptom-logs", patient, Map.of("symptoms", List.of("Headache"), "severity", "MILD",
                "durationDays", 1))).path("symptomId").asInt();
        assertAudited(write, "SYMPTOM_LOG", "CREATE", patient);

        long read = mark();
        ok(doGet("/api/symptom-logs/" + id, doctor));
        assertAudited(read, "SYMPTOM_LOG", "READ", patient);
    }

    @Test
    @Covers("AI_ALERT")
    void aiAlert_listAndAcknowledge() throws Exception {
        int alertId = aiAlertService.raise(new AiAlertCommand(patient.getSystemId(), null, "CRITICAL_RESULT",
                "CRITICAL", null, "Automated screening flagged this record for review.", true, "Review the record.",
                "LAB_RESULT", 87, null, "audit-coverage:1")).orElseThrow();

        long read = mark();
        ok(doGet("/api/ai-alerts", doctor));
        assertAudited(read, "AI_ALERT", "READ", patient);

        long write = mark();
        ok(doPost("/api/ai-alerts/" + alertId + "/acknowledge", doctor, null));
        assertAudited(write, "AI_ALERT", "UPDATE", patient);
    }

    @Test
    @Covers("DISEASE_DETECTION")
    void diseaseDetection_readAndReview() throws Exception {
        int detectionId = DetectionTestSupport.insertDetection(jdbc, patient.getSystemId(), "PENDING_REVIEW",
                LocalDateTime.now().minusHours(1), null);

        long read = mark();
        ok(doGet("/api/disease-detections/" + detectionId, doctor));
        assertAudited(read, "DISEASE_DETECTION", "READ", patient);

        long write = mark();
        ok(doPost("/api/disease-detections/" + detectionId + "/review", doctor, Map.of("decision", "CONFIRM")));
        assertAudited(write, "DISEASE_DETECTION", "UPDATE", patient);
    }

    @Test
    @Covers("CORRECTION_REQUEST")
    void correctionRequest_createAndRead() throws Exception {
        Integer recordId = new CorrectionFixtures(jdbc).medicalRecord(patient, doctor);
        long write = mark();
        int id = ok(doPost("/api/correction-requests", patient, CorrectionFixtures.body(patient.getSystemId(),
                "MEDICAL_RECORD", recordId, "The visit date should be the 3rd, not the 4th."))).path("requestId").asInt();
        assertAudited(write, "CORRECTION_REQUEST", "CREATE", patient);

        long read = mark();
        ok(doGet("/api/correction-requests/" + id, patient));
        assertAudited(read, "CORRECTION_REQUEST", "READ", patient);
    }

    @Test
    @Covers("REMINDER")
    void reminder_createAndList() throws Exception {
        long write = mark();
        ok(doPost("/api/reminders", patient, ReminderFixtures.createBody(patient.getSystemId())));
        assertAudited(write, "REMINDER", "CREATE", patient);

        long read = mark();
        ok(doGet("/api/reminders/patient/" + patient.getSystemId(), patient));
        assertAudited(read, "REMINDER", "READ", patient);
    }

    // ==================== BILLING ====================

    @Test
    @Covers("INVOICE")
    void invoice_generateAndRead() throws Exception {
        InvoiceFixtures.Visit visit = new InvoiceFixtures(jdbc).completedVisit(patient, doctor, "IN_PERSON");
        long write = mark();
        int id = ok(doPost("/api/billing/invoices", doctor, Map.of("consultationId", visit.consultationId())))
                .path("invoiceId").asInt();
        assertAudited(write, "INVOICE", "CREATE", patient);

        // A DRAFT is visible to its issuing doctor only (patients see an invoice once it is sent).
        long read = mark();
        ok(doGet("/api/billing/invoices/" + id, doctor));
        assertAudited(read, "INVOICE", "READ", patient);
    }

    @Test
    @Covers("PAYMENT")
    void payment_startAndRead() throws Exception {
        Integer invoiceId = new ClaimFixtures(jdbc).invoice(patient, doctor, "SENT", "1000.00", "0.00");
        long write = mark();
        int paymentId = ok(doPost("/api/billing/invoices/" + invoiceId + "/payments", patient, Map.of("method", "CARD")))
                .path("paymentId").asInt();
        assertAudited(write, "PAYMENT", "CREATE", patient);

        long read = mark();
        ok(doGet("/api/billing/payments/" + paymentId, patient));
        assertAudited(read, "PAYMENT", "READ", patient);
    }

    @Test
    @Covers("PAYMENT_METHOD")
    void paymentMethod_saveAndList() throws Exception {
        Map<String, Object> card = new HashMap<>();
        card.put("type", "CARD");
        card.put("provider", "VISA");
        card.put("lastFour", "4242");
        long write = mark();
        ok(doPost("/api/billing/payment-methods", patient, card));
        assertAudited(write, "PAYMENT_METHOD", "CREATE", patient);

        long read = mark();
        ok(doGet("/api/billing/payment-methods", patient));
        assertAudited(read, "PAYMENT_METHOD", "READ", patient);
    }

    @Test
    @Covers("INSURANCE_CLAIM")
    void insuranceClaim_submitAndRead() throws Exception {
        Integer invoiceId = new ClaimFixtures(jdbc).invoice(patient, doctor, "SENT", "1000.00", "0.00");
        Map<String, Object> claim = new HashMap<>();
        claim.put("insuranceProvider", "Jubilee Health");
        claim.put("policyNumber", "JH-88213-K");
        claim.put("memberNumber", "0045712");
        long write = mark();
        int claimId = ok(doPost("/api/billing/invoices/" + invoiceId + "/claims", patient, claim)).path("claimId").asInt();
        assertAudited(write, "INSURANCE_CLAIM", "CREATE", patient);

        long read = mark();
        ok(doGet("/api/billing/claims/" + claimId, patient));
        assertAudited(read, "INSURANCE_CLAIM", "READ", patient);
    }

    // ==================== CAREGIVER ACCESS ====================

    @Test
    @Covers(value = "CONSENT", write = false)
    void consentLedger_read() throws Exception {
        // The ledger gets its row when the patient approves a caregiver's request.
        MvcResult asked = doPost("/api/caregiver/access-requests", caregiver, accessRequestBody());
        assertEquals(202, asked.getResponse().getStatus(), asked.getResponse().getContentAsString());
        int requestId = ok(doGet("/api/patients/me/caregiver-access/requests", patient)).path("content").get(0)
                .path("requestId").asInt();
        ok(doPost("/api/patients/me/caregiver-access/requests/" + requestId + "/approve", patient,
                Map.of("accessLevel", "CUSTOM", "canViewLabResults", true)));

        long read = mark();
        ok(doGet("/api/patients/me/consents", patient));
        assertAudited(read, "CONSENT", "READ", patient);
    }

    @Test
    @Covers("ACCESS_REQUEST")
    void accessRequest_askAndList() throws Exception {
        long write = mark();
        MvcResult asked = doPost("/api/caregiver/access-requests", caregiver, accessRequestBody());
        assertEquals(202, asked.getResponse().getStatus(), asked.getResponse().getContentAsString());
        assertAudited(write, "ACCESS_REQUEST", "CREATE", patient);

        long read = mark();
        ok(doGet("/api/patients/me/caregiver-access/requests", patient));
        assertAudited(read, "ACCESS_REQUEST", "READ", patient);
    }

    // ==================== ACCOUNT, SETTINGS AND COMMUNICATION ====================

    @Test
    @Covers("USER_SETTINGS")
    void userSettings_readAndUpdate() throws Exception {
        long read = mark();
        ok(doGet("/api/profile/settings", patient));
        assertAudited(read, "USER_SETTINGS", "SETTINGS_READ", patient);

        long write = mark();
        ok(doPut("/api/profile/settings", patient, Map.of("smsNotifications", false)));
        assertAudited(write, "USER_SETTINGS", "SETTINGS_UPDATE", patient);
    }

    @Test
    @Covers("DATA_EXPORT")
    void dataExport_requestAndDownload() throws Exception {
        long write = mark();
        int id = ok(doPost("/api/users/me/data-export", patient, Map.of("password", AuthApi.FIXTURE_PASSWORD)))
                .path("requestId").asInt();
        assertAudited(write, "DATA_EXPORT", "DATA_EXPORT_REQUEST", patient);

        long read = mark();
        MvcResult download = doGet("/api/users/me/data-export/" + id + "/download", patient);
        assertEquals(200, download.getResponse().getStatus(), download.getResponse().getContentAsString());
        assertAudited(read, "DATA_EXPORT", "DATA_EXPORT_DOWNLOAD", patient);
    }

    @Test
    @Covers(value = "ACCOUNT", read = false)
    void account_deactivationByAnAdministrator() throws Exception {
        long write = mark();
        ok(doPost("/api/admin/users/" + otherPatient.getSystemId() + "/deactivate", admin,
                Map.of("reason", "Moved to another clinic")));
        assertAudited(write, "ACCOUNT", "USER_DEACTIVATE", otherPatient);
    }

    @Test
    @Covers("NOTIFICATION")
    void notification_listAndMarkRead() throws Exception {
        Integer id = jdbc.queryForObject("INSERT INTO notifications (system_id, type, title, message, priority, category, "
                        + "is_read, created_at, patient_system_id) VALUES (?, 'APPOINTMENT', 'Appointment booked', "
                        + "'Your appointment was booked', 'NORMAL', 'APPOINTMENT_CHANGE', FALSE, now(), ?) "
                        + "RETURNING notification_id", Integer.class, patient.getSystemId(), patient.getSystemId());

        long read = mark();
        ok(doGet("/api/notifications", patient));
        assertAudited(read, "NOTIFICATION", "READ", patient);

        long write = mark();
        ok(doPost("/api/notifications/" + id + "/read", patient, null));
        assertAudited(write, "NOTIFICATION", "UPDATE", patient);
    }

    @Test
    @Covers("SUPPORT_THREAD")
    void supportThread_openAndAdminInbox() throws Exception {
        long write = mark();
        ok(doPost("/api/communications/support-thread", caregiver, Map.of("subject", "Cannot see my invoice")));
        assertAudited(write, "SUPPORT_THREAD", "CREATE", null);

        long read = mark();
        ok(doGet("/api/admin/support/threads", admin));
        assertAudited(read, "SUPPORT_THREAD", "READ", null);
    }

    // ==================== ADMINISTRATION AND DOCTOR SELF-SERVICE (non-patient) ====================

    @Test
    @Covers(value = "FACILITY", read = false)
    void facility_create() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Avondale Clinic");
        body.put("type", "Clinic");
        body.put("address", "1 Main Road, Harare");
        body.put("phone", "+263 24 270 0000");
        body.put("email", "desk@clinic.example");
        long write = mark();
        ok(doPost("/api/admin/facilities", admin, body));
        assertAudited(write, "FACILITY", "CREATE", null);
    }

    @Test
    @Covers(value = "SYSTEM_SETTING", read = false)
    void systemSetting_update() throws Exception {
        long write = mark();
        ok(doPut("/api/admin/settings/clinic.name", admin, Map.of("value", "Harare Family Clinic")));
        assertAudited(write, "SYSTEM_SETTING", "SETTINGS_UPDATE", null);
    }

    @Test
    @Covers(value = "AUDIT_LOG", write = false)
    void auditLog_readsAreAudited() throws Exception {
        long adminRead = mark();
        ok(doGet("/api/admin/audit-logs", admin));
        assertAudited(adminRead, "AUDIT_LOG", "READ", null);

        // A patient reading what a caregiver did with their data: the same resource type, with the patient.
        int accessId = grantAccess(caregiver, patient, a -> a.setCanViewRecords(true)).getAccessId();
        long patientRead = mark();
        ok(doGet("/api/patients/me/caregiver-access/" + accessId + "/activity", patient));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE audit_id > ? "
                + "AND resource_type = 'AUDIT_LOG' AND action = 'READ' AND patient_system_id = ?", Long.class,
                patientRead, patient.getSystemId()));
    }

    @Test
    @Covers(value = "DOCTOR_PROFILE", read = false)
    void doctorProfile_update() throws Exception {
        long write = mark();
        ok(doPut("/api/doctors/profile", doctor, Map.of("bio", "Family physician with an interest in diabetes care.")));
        assertAudited(write, "DOCTOR_PROFILE", "PROFILE_UPDATE", doctor);
    }

    @Test
    @Covers(value = "AVAILABILITY", read = false)
    void availability_update() throws Exception {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("dayOfWeek", 1);
        block.put("startTime", "08:00");
        block.put("endTime", "12:00");
        long write = mark();
        ok(doPut("/api/doctors/availability", doctor, Map.of("slotMinutes", 30, "blocks", List.of(block))));
        assertAudited(write, "AVAILABILITY", "AVAILABILITY_UPDATE", doctor);
    }

    @Test
    @Covers(value = "LICENCE", read = false)
    void licence_update() throws Exception {
        long write = mark();
        ok(doPut("/api/admin/doctors/" + doctor.getSystemId() + "/licence", admin, Map.of(
                "licenseNo", "MDPCZ-2019-04471", "licensingBody", "Medical and Dental Practitioners Council of Zimbabwe",
                "licenseCountry", "ZW", "specialization", "General Practice")));
        assertAudited(write, "LICENCE", "LICENCE_UPDATE", doctor);
    }
}
