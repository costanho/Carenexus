package com.carenexus.api.releasegates;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.event.PrescriptionIssuedEvent;
import com.carenexus.api.common.event.PrescriptionStatusChangedEvent;
import com.carenexus.api.common.model.AuditLog;
import com.carenexus.api.core.model.Appointment;
import com.carenexus.api.core.model.CareTeam;
import com.carenexus.api.core.model.Consultation;
import com.carenexus.api.core.model.Prescription;
import com.carenexus.api.core.prescriptions.PrescriptionFixtures;
import com.carenexus.api.core.repository.AppointmentRepository;
import com.carenexus.api.core.repository.ConsultationRepository;
import com.carenexus.api.core.repository.PrescriptionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static com.carenexus.api.core.prescriptions.PrescriptionFixtures.clinicToday;
import static com.carenexus.api.core.prescriptions.PrescriptionFixtures.createBody;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Release gates for prescriptions: who may issue, read, edit and change the status of a prescription; the request
 * validation envelope; the status machine and its events; DELETE as cancel; idempotent replay; the paged list and
 * the legacy arrays; caregiver access with and without the medications permission, after revocation and expiry.
 * The expiry job is covered by PrescriptionExpirySweeperTest.
 */
@RecordApplicationEvents
class PrescriptionReleaseGateTest extends CommittedIntegrationTest {

    private static final String RX = "/api/prescriptions";
    private static final String DOCTOR_RX = "/api/doctors/prescriptions";

    private static final String A1 = "Your role is not authorized to perform this action";
    private static final String A2 = "Only the doctor who created this item can change it";
    private static final String A3 = "Patients can only access their own data";
    private static final String A4 = "You are not part of this patient's care team";
    private static final String A5 = "You don't have active proxy access to this patient";
    private static final String A6 = "Your proxy access for this patient does not allow viewing medications";
    private static final String A8 = "You can only access your own doctor data";
    private static final String A9 = "You don't have permission to access this resource";

    @Autowired
    private PrescriptionRepository prescriptionRepository;

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private ConsultationRepository consultationRepository;

    @Autowired
    private ApplicationEvents events;

    // ==================== helpers ====================

    private MvcResult issue(User as, Map<String, Object> body) throws Exception {
        return doPost(RX, as, body);
    }

    /** Issues a prescription for the fixture patient as the fixture doctor and returns its id. */
    private int issued() throws Exception {
        MvcResult result = issue(doctor, createBody(patient));
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return json(result).path("prescriptionId").asInt();
    }

    private void assertError(MvcResult result, int status, String message) throws Exception {
        assertEquals(status, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(message, message(result));
    }

    private void assertValidation(MvcResult result, String message) throws Exception {
        assertError(result, 400, message);
        assertEquals("VALIDATION_FAILED", json(result).path("code").asText());
    }

    private List<AuditLog> auditRows(String action) {
        return auditLogRepository.findAll().stream()
                .filter(row -> "PRESCRIPTION".equals(row.getResourceType()) && action.equals(row.getAction()))
                .toList();
    }

    private List<PrescriptionStatusChangedEvent> statusEvents() {
        return events.stream(PrescriptionStatusChangedEvent.class).toList();
    }

    private String storedStatus(int id) {
        return prescriptionRepository.findById(id).orElseThrow().getStatus();
    }

    private List<Integer> ids(JsonNode array) {
        List<Integer> ids = new ArrayList<>();
        Iterator<JsonNode> it = array.elements();
        while (it.hasNext()) {
            ids.add(it.next().path("prescriptionId").asInt());
        }
        return ids;
    }

    private Prescription stored(User forPatient, User byDoctor, String name, String status, LocalDate prescribed,
                                LocalDate expiry) {
        return prescriptionRepository.saveAndFlush(
                PrescriptionFixtures.row(forPatient, byDoctor, name, status, prescribed, expiry));
    }

    // ==================== issue ====================

    @Test
    void treatingDoctorIssues_bodyDoctorAndStatusIgnored_timesNormalisedOnTheEvent() throws Exception {
        Map<String, Object> body = createBody(patient);
        body.put("doctorSystemId", otherDoctor.getSystemId());
        body.put("status", "COMPLETED");
        body.put("route", " inhaled ");
        body.put("medicationName", "  Salbutamol  ");
        body.put("quantity", "   ");
        body.put("scheduleTimes", List.of("20:00", "08:00", "20:00"));

        MvcResult result = issue(doctor, body);

        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode rx = json(result);
        assertEquals(doctor.getSystemId(), rx.path("doctorSystemId").asText());
        assertEquals("Dr. DOCTOR User2", rx.path("doctorName").asText());
        assertEquals("ACTIVE", rx.path("status").asText());
        assertEquals("ACTIVE", rx.path("effectiveStatus").asText());
        assertEquals("INHALED", rx.path("route").asText());
        assertEquals("Salbutamol", rx.path("medicationName").asText());
        assertTrue(rx.has("quantity") && rx.path("quantity").isNull(), "blank optional text is stored as null");
        assertTrue(rx.has("consultationId") && rx.path("consultationId").isNull());
        assertTrue(rx.has("expiryDate") && rx.path("expiryDate").isNull());
        assertFalse(rx.has("scheduleTimes"), "schedule times are not returned");
        assertEquals(clinicToday().toString(), rx.path("prescribedDate").asText());

        int id = rx.path("prescriptionId").asInt();
        Prescription row = prescriptionRepository.findById(id).orElseThrow();
        assertEquals(doctor.getSystemId(), row.getDoctorSystemId());
        assertEquals("ACTIVE", row.getStatus());

        List<PrescriptionIssuedEvent> issuedEvents = events.stream(PrescriptionIssuedEvent.class).toList();
        assertEquals(1, issuedEvents.size());
        PrescriptionIssuedEvent event = issuedEvents.get(0);
        assertEquals(id, event.prescriptionId());
        assertEquals(patient.getSystemId(), event.patientSystemId());
        assertEquals(doctor.getSystemId(), event.doctorSystemId());
        assertEquals(List.of("08:00", "20:00"), event.scheduleTimes());
        assertEquals(clinicToday(), event.startDate());

        List<AuditLog> created = auditRows("CREATE");
        assertEquals(1, created.size());
        assertEquals(id, created.get(0).getResourceId());
        assertEquals(patient.getSystemId(), created.get(0).getPatientSystemId());
    }

    @Test
    void issuingWithoutScheduleTimesPublishesAnEmptyList() throws Exception {
        issued();
        PrescriptionIssuedEvent event = events.stream(PrescriptionIssuedEvent.class).findFirst().orElseThrow();
        assertEquals(List.of(), event.scheduleTimes());
    }

    @Test
    void onlyATreatingDoctorMayIssue() throws Exception {
        assertError(issue(otherDoctor, createBody(patient)), 403, A4);
        assertError(issue(patient, createBody(patient)), 403, A1);
        assertError(issue(caregiver, createBody(patient)), 403, A1);
        assertError(issue(admin, createBody(patient)), 403, A1);

        Map<String, Object> unknownPatient = createBody(patient);
        unknownPatient.put("patientSystemId", "PAT-09999");
        assertError(issue(doctor, unknownPatient), 404, "Patient not found");

        assertEquals(0, prescriptionRepository.count());
        assertEquals(0, events.stream(PrescriptionIssuedEvent.class).count());
    }

    @Test
    void consultationMustBelongToThePatient() throws Exception {
        Appointment appointment = appointmentRepository.saveAndFlush(Appointment.builder()
                .patientSystemId(otherPatient.getSystemId()).doctorSystemId(otherDoctor.getSystemId())
                .scheduledAt(LocalDateTime.now().minusDays(1)).type("IN_PERSON").status("COMPLETED").build());
        Consultation othersConsultation = consultationRepository.saveAndFlush(Consultation.builder()
                .appointmentId(appointment.getAppointmentId()).patientSystemId(otherPatient.getSystemId())
                .doctorSystemId(otherDoctor.getSystemId()).chiefComplaint("Cough").status("COMPLETED").build());

        Map<String, Object> body = createBody(patient);
        body.put("consultationId", othersConsultation.getConsultationId());
        assertError(issue(doctor, body), 400, "Consultation does not belong to this patient");

        body.put("consultationId", 987654);
        assertError(issue(doctor, body), 404, "Consultation not found");
        assertEquals(0, prescriptionRepository.count());
    }

    @Test
    void invalidCreateBodiesGetTheValidationEnvelope() throws Exception {
        Map<String, Object> tooLong = createBody(patient);
        tooLong.put("medicationName", "x".repeat(201));
        MvcResult result = issue(doctor, tooLong);
        assertValidation(result, "medicationName: must be at most 200 characters");
        JsonNode error = json(result).path("errors").get(0);
        assertEquals("medicationName", error.path("field").asText());
        assertEquals("must be at most 200 characters", error.path("message").asText());

        Map<String, Object> dosage = createBody(patient);
        dosage.put("dosage", "y".repeat(101));
        assertValidation(issue(doctor, dosage), "dosage: must be at most 100 characters");

        Map<String, Object> instructions = createBody(patient);
        instructions.put("specialInstructions", "z".repeat(2001));
        assertValidation(issue(doctor, instructions), "specialInstructions: must be at most 2000 characters");

        Map<String, Object> route = createBody(patient);
        route.put("route", "ORALLY");
        assertValidation(issue(doctor, route),
                "route: must be one of ORAL, TOPICAL, INJECTION, INHALED, SUBLINGUAL, RECTAL, IV");

        Map<String, Object> refills = createBody(patient);
        refills.put("refillsAllowed", -1);
        assertValidation(issue(doctor, refills), "refillsAllowed: must be between 0 and 99");

        Map<String, Object> missing = createBody(patient);
        missing.remove("patientSystemId");
        assertValidation(issue(doctor, missing), "patientSystemId: is required");

        Map<String, Object> badId = createBody(patient);
        badId.put("patientSystemId", "patient-1");
        assertValidation(issue(doctor, badId), "patientSystemId: must be a valid system id");

        Map<String, Object> blankName = createBody(patient);
        blankName.put("medicationName", "   ");
        assertValidation(issue(doctor, blankName), "medicationName: is required");

        Map<String, Object> consultation = createBody(patient);
        consultation.put("consultationId", 0);
        assertValidation(issue(doctor, consultation), "consultationId: must be a positive number");

        Map<String, Object> tooManyTimes = createBody(patient);
        tooManyTimes.put("scheduleTimes", List.of("01:00", "02:00", "03:00", "04:00", "05:00", "06:00", "07:00",
                "08:00", "09:00"));
        assertValidation(issue(doctor, tooManyTimes), "scheduleTimes: must contain at most 8 times");

        Map<String, Object> badTime = createBody(patient);
        badTime.put("scheduleTimes", List.of("08:00", "8:00"));
        MvcResult timeResult = issue(doctor, badTime);
        assertValidation(timeResult, "scheduleTimes[1]: must be a time in HH:mm format");
        assertEquals("scheduleTimes[1]", json(timeResult).path("errors").get(0).path("field").asText());

        Map<String, Object> future = createBody(patient);
        future.put("prescribedDate", clinicToday().plusDays(1).toString());
        assertValidation(issue(doctor, future), "prescribedDate: must not be in the future");

        Map<String, Object> expiry = createBody(patient);
        expiry.put("expiryDate", clinicToday().minusDays(1).toString());
        assertValidation(issue(doctor, expiry), "expiryDate: must not be before prescribedDate");

        Map<String, Object> badDate = createBody(patient);
        badDate.put("expiryDate", "10/11/2026");
        assertError(issue(doctor, badDate), 400, "Malformed request body");

        assertEquals(0, prescriptionRepository.count());
    }

    @Test
    void idempotentReplayReturnsTheFirstResponseAndRunsNothingAgain() throws Exception {
        String key = "rx-" + clientId();
        MvcResult first = doPostWithHeaders(RX, doctor, createBody(patient), Map.of("Idempotency-Key", key));
        assertEquals(201, first.getResponse().getStatus(), first.getResponse().getContentAsString());

        Map<String, Object> changed = createBody(patient);
        changed.put("medicationName", "Something else");
        MvcResult replay = doPostWithHeaders(RX, doctor, changed, Map.of("Idempotency-Key", key));
        assertEquals(200, replay.getResponse().getStatus(), replay.getResponse().getContentAsString());
        assertEquals(json(first), json(replay));

        assertEquals(1, prescriptionRepository.count());
        assertEquals(1, events.stream(PrescriptionIssuedEvent.class).count());
        assertEquals(1, auditRows("CREATE").size());

        MvcResult malformed = doPostWithHeaders(RX, doctor, createBody(patient), Map.of("Idempotency-Key", "short"));
        assertEquals(400, malformed.getResponse().getStatus());
        assertEquals(1, prescriptionRepository.count());
    }

    // ==================== read ====================

    @Test
    void patientReadsOwnPrescriptionsOnly() throws Exception {
        int mine = issued();
        Prescription others = stored(otherPatient, otherDoctor, "Metformin", "ACTIVE", clinicToday(), null);

        MvcResult page = doGet(RX, patient);
        assertEquals(200, page.getResponse().getStatus(), page.getResponse().getContentAsString());
        assertEquals(List.of(mine), ids(json(page).path("content")));

        assertEquals(200, doGet(RX + "/" + mine, patient).getResponse().getStatus());
        assertError(doGet(RX + "/" + others.getPrescriptionId(), patient), 403, A3);
        assertError(doGet(RX + "?patientSystemId=" + otherPatient.getSystemId(), patient), 403, A3);
        assertError(doGet(RX + "/patient/" + otherPatient.getSystemId(), patient), 403, A3);
        assertEquals(List.of(mine), ids(json(doGet(RX + "/patient/" + patient.getSystemId(), patient))));
        assertError(doGet(RX + "/doctor/" + doctor.getSystemId(), patient), 403, A8);
        assertError(doGet(RX + "/999999", patient), 404, "Prescription not found");

        List<AuditLog> reads = auditRows("READ");
        assertTrue(reads.stream().anyMatch(r -> Integer.valueOf(mine).equals(r.getResourceId())
                && patient.getSystemId().equals(r.getPatientSystemId())), "single read is audited with its id");
        assertTrue(reads.stream().noneMatch(r -> otherPatient.getSystemId().equals(r.getPatientSystemId())),
                "a refused read writes no READ row");
    }

    @Test
    void caregiverNeedsTheMedicationsPermission_andLosesItOnRevocationOrExpiry() throws Exception {
        int id = issued();
        String list = RX + "?patientSystemId=" + patient.getSystemId();

        assertError(doGet(list, caregiver), 403, A5);
        assertError(doGet(RX + "/" + id, caregiver), 403, A5);

        grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));
        assertError(doGet(list, caregiver), 403, A6);
        assertError(doGet(RX + "/" + id, caregiver), 403, A6);
        assertError(doGet(RX + "/patient/" + patient.getSystemId(), caregiver), 403, A6);

        grantAccess(caregiver, patient, a -> a.setCanViewMedications(true));
        MvcResult allowed = doGet(list, caregiver);
        assertEquals(200, allowed.getResponse().getStatus(), allowed.getResponse().getContentAsString());
        assertEquals(List.of(id), ids(json(allowed).path("content")));
        assertEquals(200, doGet(RX + "/" + id, caregiver).getResponse().getStatus());
        assertEquals(1, json(doGet(RX + "/patient/" + patient.getSystemId(), caregiver)).size());
        assertValidation(doGet(RX, caregiver), "patientSystemId: is required");

        grantAccess(caregiver, patient, a -> {
            a.setCanViewMedications(true);
            a.setIsActive(false);
            a.setRevokedAt(LocalDateTime.now());
        });
        assertError(doGet(list, caregiver), 403, A5);

        grantAccess(caregiver, patient, a -> {
            a.setCanViewMedications(true);
            a.setExpiresAt(LocalDateTime.now().minusMinutes(5));
        });
        assertError(doGet(list, caregiver), 403, A5);
        assertError(doGet(RX + "/" + id, caregiver), 403, A5);

        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        assertEquals(200, doGet(list, caregiver).getResponse().getStatus());

        assertError(doGet(RX + "?patientSystemId=" + patient.getSystemId(), otherCaregiver), 403, A5);
        assertError(issue(caregiver, createBody(patient)), 403, A1);
    }

    @Test
    void doctorListScopes() throws Exception {
        careTeamRepository.saveAndFlush(CareTeam.builder()
                .patientSystemId(patient.getSystemId()).providerSystemId(otherDoctor.getSystemId())
                .role("PRIMARY_DOCTOR").assignedAt(LocalDateTime.now()).isActive(true).build());
        int mine = issued();
        MvcResult byOther = issue(otherDoctor, createBody(patient));
        assertEquals(201, byOther.getResponse().getStatus(), byOther.getResponse().getContentAsString());
        int theirs = json(byOther).path("prescriptionId").asInt();

        assertEquals(List.of(mine), ids(json(doGet(RX, doctor)).path("content")));
        assertEquals(List.of(mine), ids(json(doGet(RX + "?doctorSystemId=" + doctor.getSystemId(), doctor)).path("content")));
        assertError(doGet(RX + "?doctorSystemId=" + otherDoctor.getSystemId(), doctor), 403, A8);
        assertEquals(List.of(theirs, mine),
                ids(json(doGet(RX + "?patientSystemId=" + patient.getSystemId(), doctor)).path("content")));

        careTeamRepository.findAll().stream()
                .filter(c -> otherDoctor.getSystemId().equals(c.getProviderSystemId())
                        && patient.getSystemId().equals(c.getPatientSystemId()))
                .forEach(careTeamRepository::delete);
        assertError(doGet(RX + "?patientSystemId=" + patient.getSystemId(), otherDoctor), 403, A4);
        // Rows a doctor wrote stay visible to that doctor without a care relationship.
        assertEquals(List.of(theirs), ids(json(doGet(RX + "?doctorSystemId=" + otherDoctor.getSystemId()
                + "&patientSystemId=" + patient.getSystemId(), otherDoctor)).path("content")));

        JsonNode all = json(doGet(RX, admin));
        assertEquals(2, all.path("totalElements").asInt());

        assertEquals(List.of(mine), ids(json(doGet(RX + "/doctor/" + doctor.getSystemId(), doctor))));
        assertError(doGet(RX + "/doctor/" + doctor.getSystemId(), otherDoctor), 403, A8);
        assertEquals(List.of(mine), ids(json(doGet(DOCTOR_RX, doctor))));
        assertEquals(0, json(doGet(DOCTOR_RX, admin)).size());
        assertError(doGet(DOCTOR_RX, patient), 403, A9);
        assertError(doGet(DOCTOR_RX, caregiver), 403, A9);
    }

    @Test
    void listIsPagedNewestFirst_filtersOnEffectiveStatus_andValidatesParameters() throws Exception {
        LocalDate today = clinicToday();
        Prescription oldest = stored(patient, doctor, "Lisinopril", "ACTIVE", today.minusDays(30), null);
        Prescription lapsed = stored(patient, doctor, "Amoxicillin", "ACTIVE", today.minusDays(20), today.minusDays(1));
        Prescription held = stored(patient, doctor, "Metformin_XR", "ON_HOLD", today.minusDays(10), today);
        Prescription done = stored(patient, doctor, "Ibuprofen", "COMPLETED", today.minusDays(5), null);

        JsonNode page = json(doGet(RX + "?size=2", patient));
        assertEquals(4, page.path("totalElements").asInt());
        assertEquals(2, page.path("totalPages").asInt());
        assertEquals(2, page.path("size").asInt());
        assertEquals(0, page.path("page").asInt());
        assertEquals(List.of(done.getPrescriptionId(), held.getPrescriptionId()), ids(page.path("content")));
        JsonNode second = json(doGet(RX + "?size=2&page=1", patient));
        assertEquals(List.of(lapsed.getPrescriptionId(), oldest.getPrescriptionId()), ids(second.path("content")));
        assertEquals(100, json(doGet(RX + "?size=1000", patient)).path("size").asInt());

        JsonNode lapsedRow = json(doGet(RX + "/" + lapsed.getPrescriptionId(), patient));
        assertEquals("ACTIVE", lapsedRow.path("status").asText());
        assertEquals("EXPIRED", lapsedRow.path("effectiveStatus").asText());
        assertEquals("ON_HOLD", json(doGet(RX + "/" + held.getPrescriptionId(), patient)).path("effectiveStatus").asText(),
                "a prescription is valid through its expiry date");

        assertEquals(List.of(oldest.getPrescriptionId()), ids(json(doGet(RX + "?status=active", patient)).path("content")));
        assertEquals(List.of(lapsed.getPrescriptionId()), ids(json(doGet(RX + "?status=EXPIRED", patient)).path("content")));
        assertEquals(List.of(held.getPrescriptionId()), ids(json(doGet(RX + "?status=ON_HOLD", patient)).path("content")));
        assertEquals(1, json(doGet(RX + "?status=COMPLETED&size=1", patient)).path("totalElements").asInt());
        assertEquals(List.of(lapsed.getPrescriptionId()),
                ids(json(doGet(DOCTOR_RX + "?status=expired", doctor))));

        assertEquals(List.of(held.getPrescriptionId()), ids(json(doGet(RX + "?q=metformin_", patient)).path("content")));
        assertEquals(List.of(), ids(json(doGet(RX + "?q=%25", patient)).path("content")), "% matches literally");

        assertValidation(doGet(RX + "?status=DONE", patient),
                "status: must be one of ACTIVE, ON_HOLD, COMPLETED, CANCELLED, EXPIRED");
        assertValidation(doGet(RX + "?q=" + "a".repeat(101), patient), "q: must be at most 100 characters");
        assertValidation(doGet(RX + "?consultationId=0", patient), "consultationId: must be a positive number");
        assertValidation(doGet(RX + "?patientSystemId=nobody", doctor), "patientSystemId: must be a valid system id");
        assertError(doGet(RX + "?page=abc", patient), 400, "Invalid value for parameter 'page'");
        assertError(doGet(RX + "/abc", patient), 400, "Invalid value for parameter 'id'");
        assertEquals(0, json(doGet(RX + "?consultationId=4242", patient)).path("totalElements").asInt());
    }

    // ==================== edit ====================

    @Test
    void onlyThePrescriberEdits_andOnlyTheEditableFieldsChange() throws Exception {
        int id = issued();
        Map<String, Object> edit = new java.util.LinkedHashMap<>();
        edit.put("refillsAllowed", 3);
        edit.put("expiryDate", clinicToday().plusMonths(3).toString());
        edit.put("specialInstructions", "  Avoid grapefruit.  ");
        edit.put("medicationName", "Changed");
        edit.put("dosage", "500 mg");
        edit.put("status", "COMPLETED");

        assertError(doPut(RX + "/" + id, otherDoctor, edit), 403, A2);
        assertError(doPut(RX + "/" + id, admin, edit), 403, A2);
        assertError(doPut(RX + "/" + id, patient, edit), 403, A2);
        assertError(doPut(RX + "/" + id, caregiver, edit), 403, A2);
        assertError(doPut(RX + "/999999", doctor, edit), 404, "Prescription not found");

        MvcResult result = doPut(RX + "/" + id, doctor, edit);
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode rx = json(result);
        assertEquals(3, rx.path("refillsAllowed").asInt());
        assertEquals(clinicToday().plusMonths(3).toString(), rx.path("expiryDate").asText());
        assertEquals("Avoid grapefruit.", rx.path("specialInstructions").asText());
        assertEquals("Amlodipine", rx.path("medicationName").asText());
        assertEquals("5 mg", rx.path("dosage").asText());
        assertEquals("ACTIVE", rx.path("status").asText());
        assertEquals(1, auditRows("UPDATE").size());
        assertTrue(statusEvents().isEmpty(), "an edit publishes no status event");

        Map<String, Object> clear = new java.util.LinkedHashMap<>();
        clear.put("refillsAllowed", 0);
        clear.put("specialInstructions", " ");
        JsonNode cleared = json(doPut(RX + "/" + id, doctor, clear));
        assertTrue(cleared.path("expiryDate").isNull(), "absent expiryDate clears the stored date");
        assertTrue(cleared.path("specialInstructions").isNull(), "blank instructions clear the stored text");

        assertValidation(doPut(RX + "/" + id, doctor, Map.of("specialInstructions", "x")), "refillsAllowed: is required");
        assertValidation(doPut(RX + "/" + id, doctor, Map.of("refillsAllowed", 100)), "refillsAllowed: must be between 0 and 99");
        assertValidation(doPut(RX + "/" + id, doctor, Map.of("refillsAllowed", 1,
                "expiryDate", clinicToday().minusDays(1).toString())), "expiryDate: must not be before prescribedDate");

        assertEquals(200, doPatch(DOCTOR_RX + "/" + id + "/mark-completed", doctor, null).getResponse().getStatus());
        assertError(doPut(RX + "/" + id, doctor, Map.of("refillsAllowed", 1)), 409,
                "Prescription is COMPLETED and can no longer be changed");
    }

    // ==================== status ====================

    @Test
    void statusMachine_eventsAndAudit() throws Exception {
        int id = issued();
        String base = DOCTOR_RX + "/" + id;

        MvcResult hold = doPatch(base + "/mark-on-hold", doctor, null);
        assertEquals(200, hold.getResponse().getStatus(), hold.getResponse().getContentAsString());
        assertEquals("ON_HOLD", json(hold).path("status").asText());

        MvcResult again = doPatch(base + "/mark-on-hold", doctor, null);
        assertEquals(200, again.getResponse().getStatus());
        assertEquals("ON_HOLD", json(again).path("status").asText());

        assertEquals("ACTIVE", json(doPatch(base + "/mark-active", doctor, null)).path("status").asText());
        assertEquals("ON_HOLD", json(doPatch(RX + "/" + id + "/status?status=on_hold", doctor, null)).path("status").asText());
        assertEquals("COMPLETED", json(doPatch(base + "/mark-completed", doctor, null)).path("status").asText());

        String terminal = "Prescription is COMPLETED and can no longer be changed";
        assertError(doPatch(base + "/mark-active", doctor, null), 409, terminal);
        assertError(doPatch(base + "/mark-on-hold", doctor, null), 409, terminal);
        assertError(doPatch(base + "/mark-cancelled", doctor, null), 409, terminal);
        assertError(doPatch(base + "/mark-expired", doctor, null), 409, terminal);
        assertError(doPatch(base + "/status?status=ACTIVE", doctor, null), 409, terminal);
        assertEquals(200, doPatch(base + "/mark-completed", doctor, null).getResponse().getStatus(), "same status is a no-op");
        assertError(doDelete(RX + "/" + id, doctor), 409, terminal);
        assertEquals("COMPLETED", storedStatus(id));

        List<PrescriptionStatusChangedEvent> changes = statusEvents();
        assertEquals(List.of("ACTIVE>ON_HOLD", "ON_HOLD>ACTIVE", "ACTIVE>ON_HOLD", "ON_HOLD>COMPLETED"),
                changes.stream().map(e -> e.previousStatus() + ">" + e.status()).toList());
        assertTrue(changes.stream().allMatch(e -> e.prescriptionId() == id
                && doctor.getSystemId().equals(e.doctorSystemId())
                && patient.getSystemId().equals(e.patientSystemId())));
        assertEquals(4, auditRows("UPDATE").size());

        int other = issued();
        assertEquals("EXPIRED", json(doPatch(DOCTOR_RX + "/" + other + "/mark-expired", doctor, null)).path("status").asText());
        assertError(doPatch(DOCTOR_RX + "/" + other + "/mark-active", doctor, null), 409,
                "Prescription is EXPIRED and can no longer be changed");
    }

    @Test
    void onlyThePrescriberChangesStatus() throws Exception {
        int id = issued();
        String base = DOCTOR_RX + "/" + id;

        assertError(doPatch(base + "/mark-on-hold", otherDoctor, null), 403, A2);
        assertError(doPatch(base + "/mark-cancelled", admin, null), 403, A2);
        assertError(doPatch(RX + "/" + id + "/status?status=COMPLETED", admin, null), 403, A2);
        assertError(doPatch(RX + "/" + id + "/status?status=COMPLETED", patient, null), 403, A2);
        assertError(doPatch(base + "/mark-on-hold", patient, null), 403, A9);
        assertError(doPatch(base + "/mark-on-hold", caregiver, null), 403, A9);
        assertError(doPatch(DOCTOR_RX + "/999999/mark-on-hold", doctor, null), 404, "Prescription not found");

        assertValidation(doPatch(RX + "/" + id + "/status?status=DONE", doctor, null),
                "status: must be one of ACTIVE, ON_HOLD, COMPLETED, CANCELLED, EXPIRED");
        assertValidation(doPatch(base + "/status?status=", doctor, null),
                "status: must be one of ACTIVE, ON_HOLD, COMPLETED, CANCELLED, EXPIRED");
        assertError(doPatch(RX + "/" + id + "/status", doctor, null), 400, "Missing required parameter 'status'");
        assertError(doPatch(DOCTOR_RX + "/abc/mark-on-hold", doctor, null), 400, "Invalid value for parameter 'prescriptionId'");

        assertEquals("ACTIVE", storedStatus(id));
        assertTrue(statusEvents().isEmpty());
    }

    @Test
    void deleteCancelsAndNeverRemovesTheRow() throws Exception {
        int id = issued();
        assertError(doDelete(RX + "/" + id, otherDoctor), 403, A2);
        assertError(doDelete(RX + "/" + id, patient), 403, A2);
        assertError(doDelete(RX + "/" + id, caregiver), 403, A2);

        MvcResult byAdmin = doDelete(RX + "/" + id, admin);
        assertEquals(204, byAdmin.getResponse().getStatus(), byAdmin.getResponse().getContentAsString());
        assertEquals("CANCELLED", storedStatus(id));
        assertEquals(1, statusEvents().size());
        PrescriptionStatusChangedEvent event = statusEvents().get(0);
        assertEquals("ACTIVE", event.previousStatus());
        assertEquals("CANCELLED", event.status());
        assertEquals(doctor.getSystemId(), event.doctorSystemId(), "the event names the prescriber, not the admin");
        assertEquals(1, auditRows("DELETE").size());

        assertEquals(204, doDelete(RX + "/" + id, doctor).getResponse().getStatus(), "cancelling again is a no-op");
        assertEquals(1, statusEvents().size());
        assertEquals(1, auditRows("DELETE").size());
        assertError(doDelete(RX + "/999999", doctor), 404, "Prescription not found");

        int byDoctor = issued();
        assertEquals("CANCELLED", json(doPatch(DOCTOR_RX + "/" + byDoctor + "/mark-cancelled", doctor, null))
                .path("status").asText());
        assertEquals(2, prescriptionRepository.count());
    }

    @Test
    void removedLegacyRoutesAreGone() throws Exception {
        issued();
        assertEquals(404, doGet(RX + "/me/active", patient).getResponse().getStatus());
        assertEquals(404, doGet(RX + "/consultation/1", doctor).getResponse().getStatus());
        assertEquals(404, doGet(DOCTOR_RX + "/stats", doctor).getResponse().getStatus());
        assertEquals(404, doGet(DOCTOR_RX + "/route/oral", doctor).getResponse().getStatus());
    }
}
