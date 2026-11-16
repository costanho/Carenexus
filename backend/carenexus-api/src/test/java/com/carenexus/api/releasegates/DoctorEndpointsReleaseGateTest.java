package com.carenexus.api.releasegates;

import com.carenexus.api.billing.claim.ClaimFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Release gate: one doctor can never reach another doctor's schedule, patients, prescriptions, records or invoices
 * through any doctor route.
 * <p>
 * The other doctor (DOC-00002) gets a full practice with their own patient (PAT-00002): a booked and a completed
 * visit, a consultation, a prescription, a medical record, a lab and an imaging result, an invoice and a time-off
 * block; every free-text field carries {@link #MARKER}. The calling doctor (DOC-00001) has their own patient
 * (PAT-00001) with the same kinds of rows. The routes are not hand-picked: every GET handler mapping under
 * {@code /api/doctors} and every route whose path names a doctor ({@code /doctor/...}) is called with the other
 * doctor's and the other patient's ids in every path variable and in the {@code doctorSystemId} /
 * {@code patientSystemId} query filters. A 2xx answer must not contain the other patient's id, name or any marker
 * text; a 5xx answer fails the gate. Every write route under {@code /api/doctors} aimed at the other doctor's rows
 * must answer 403 or 404 and leave the rows unchanged.
 */
class DoctorEndpointsReleaseGateTest extends CommittedIntegrationTest {

    private static final String MARKER = "LEAKMARK";
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}:]+)(?::[^}]*)?}");
    private static final DateTimeFormatter WALL_CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    /** The other doctor's rows. */
    private int otherFutureAppointment;
    private int otherPastAppointment;
    private int otherConsultation;
    private int otherPrescription;
    private int otherRecord;
    private int otherLab;
    private int otherImaging;
    private int otherInvoice;
    private int otherTimeOff;

    @BeforeEach
    void twoPractices() {
        LocalDateTime tomorrow = LocalDateTime.now().plusDays(1).truncatedTo(ChronoUnit.HOURS).withHour(10);
        LocalDateTime lastWeek = LocalDateTime.now().minusDays(7).truncatedTo(ChronoUnit.HOURS).withHour(10);
        ClaimFixtures invoices = new ClaimFixtures(jdbc);

        // The other doctor's practice, every text marked.
        otherFutureAppointment = appointment(otherPatient.getSystemId(), otherDoctor.getSystemId(), tomorrow, "SCHEDULED");
        otherPastAppointment = appointment(otherPatient.getSystemId(), otherDoctor.getSystemId(), lastWeek, "COMPLETED");
        otherConsultation = consultation(otherPastAppointment, otherPatient.getSystemId(), otherDoctor.getSystemId());
        otherPrescription = prescription(otherPatient.getSystemId(), otherDoctor.getSystemId());
        otherRecord = medicalRecord(otherPatient.getSystemId(), otherDoctor.getSystemId());
        otherLab = labResult(otherPatient.getSystemId(), otherDoctor.getSystemId());
        otherImaging = imagingResult(otherPatient.getSystemId(), otherDoctor.getSystemId());
        otherInvoice = invoices.invoice(otherPatient, otherDoctor, "SENT", "1500.00", "0.00");
        otherTimeOff = jdbc.queryForObject("INSERT INTO doctor_time_off (doctor_system_id, starts_at, ends_at, reason, "
                        + "created_at) VALUES (?, ?, ?, ?, now()) RETURNING time_off_id", Integer.class,
                otherDoctor.getSystemId(), tomorrow.plusDays(2), tomorrow.plusDays(3), MARKER + " leave");

        // The calling doctor's own practice, so the lists are not trivially empty.
        appointment(patient.getSystemId(), doctor.getSystemId(), tomorrow.plusHours(1), "SCHEDULED");
        int ownPast = appointment(patient.getSystemId(), doctor.getSystemId(), lastWeek.plusHours(1), "COMPLETED");
        consultation(ownPast, patient.getSystemId(), doctor.getSystemId());
        prescription(patient.getSystemId(), doctor.getSystemId());
        medicalRecord(patient.getSystemId(), doctor.getSystemId());
        invoices.invoice(patient, doctor, "SENT", "900.00", "0.00");
        // Own rows must not carry the marker.
        jdbc.update("UPDATE appointments SET reason_for_visit = 'Own follow-up' WHERE doctor_system_id = ?",
                doctor.getSystemId());
        jdbc.update("UPDATE prescriptions SET medication_name = 'Own medication' WHERE doctor_system_id = ?",
                doctor.getSystemId());
        jdbc.update("UPDATE medical_records SET title = 'Own record', description = NULL WHERE doctor_system_id = ?",
                doctor.getSystemId());
        jdbc.update("UPDATE consultations SET chief_complaint = 'Own complaint', diagnosis = NULL "
                + "WHERE doctor_system_id = ?", doctor.getSystemId());
    }

    // ==================== FIXTURES ====================

    private int appointment(String patientId, String doctorId, LocalDateTime at, String status) {
        return jdbc.queryForObject("INSERT INTO appointments (patient_system_id, doctor_system_id, scheduled_at, "
                        + "duration_minutes, type, status, reason_for_visit) VALUES (?, ?, ?, 30, 'IN_PERSON', ?, ?) "
                        + "RETURNING appointment_id", Integer.class, patientId, doctorId, at, status,
                MARKER + " visit reason");
    }

    private int consultation(int appointmentId, String patientId, String doctorId) {
        return jdbc.queryForObject("INSERT INTO consultations (appointment_id, patient_system_id, doctor_system_id, "
                        + "status, chief_complaint, diagnosis, started_at, completed_at) VALUES (?, ?, ?, 'COMPLETED', ?, "
                        + "?, now() - interval '7 days', now() - interval '7 days' + interval '20 minutes') "
                        + "RETURNING consultation_id", Integer.class, appointmentId, patientId, doctorId,
                MARKER + " complaint", MARKER + " diagnosis");
    }

    private int prescription(String patientId, String doctorId) {
        return jdbc.queryForObject("INSERT INTO prescriptions (patient_system_id, doctor_system_id, medication_name, "
                        + "dosage, frequency, route, refills_allowed, status, prescribed_date, created_at) VALUES (?, ?, ?, "
                        + "'500 mg', 'Twice daily', 'ORAL', 0, 'ACTIVE', CURRENT_DATE, now()) RETURNING prescription_id",
                Integer.class, patientId, doctorId, MARKER + "zole");
    }

    private int medicalRecord(String patientId, String doctorId) {
        return jdbc.queryForObject("INSERT INTO medical_records (patient_system_id, doctor_system_id, record_type, title, "
                        + "description) VALUES (?, ?, 'NOTE', ?, ?) RETURNING record_id", Integer.class, patientId, doctorId,
                MARKER + " record", MARKER + " description");
    }

    private int labResult(String patientId, String doctorId) {
        return jdbc.queryForObject("INSERT INTO lab_results (patient_system_id, doctor_system_id, test_name, test_type, "
                        + "status, test_date) VALUES (?, ?, ?, 'BLOOD', 'COMPLETED', CURRENT_DATE) RETURNING result_id",
                Integer.class, patientId, doctorId, MARKER + " blood count");
    }

    private int imagingResult(String patientId, String doctorId) {
        return jdbc.queryForObject("INSERT INTO imaging_results (patient_system_id, doctor_system_id, imaging_type, "
                        + "body_part, status, image_date) VALUES (?, ?, 'XRAY', ?, 'COMPLETED', CURRENT_DATE) "
                        + "RETURNING imaging_id", Integer.class, patientId, doctorId, MARKER + " chest");
    }

    // ==================== ROUTE DISCOVERY ====================

    /** A route of the doctor surface: method and path pattern. */
    private record Route(String method, String pattern) {
        @Override
        public String toString() {
            return method + " " + pattern;
        }
    }

    /** Doctor-scoped routes: everything under /api/doctors plus every route whose path names a doctor. */
    private Set<Route> doctorRoutes(boolean reads) {
        Set<Route> routes = new TreeSet<>((a, b) -> a.toString().compareTo(b.toString()));
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            Set<RequestMethod> methods = entry.getKey().getMethodsCondition().getMethods();
            for (String pattern : entry.getKey().getPatternValues()) {
                boolean doctorSurface = pattern.startsWith("/api/doctors/") || pattern.equals("/api/doctors")
                        || (reads && pattern.startsWith("/api/") && pattern.contains("/doctor"));
                if (!doctorSurface) {
                    continue;
                }
                for (RequestMethod method : methods) {
                    boolean isRead = method == RequestMethod.GET;
                    if (isRead == reads && method != RequestMethod.HEAD && method != RequestMethod.OPTIONS) {
                        routes.add(new Route(method.name(), pattern));
                    }
                }
            }
        }
        return routes;
    }

    /** The concrete path: every path variable points at the other doctor's practice. */
    private String otherPracticePath(String pattern) {
        Matcher m = PATH_VARIABLE.matcher(pattern);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(valueFor(pattern, m.group(1))));
        }
        m.appendTail(out);
        return out.toString();
    }

    private String valueFor(String pattern, String variable) {
        String name = variable.toLowerCase(Locale.ROOT);
        if (name.contains("patient")) {
            return otherPatient.getSystemId();
        }
        if (name.contains("doctor") || name.equals("systemid")) {
            return otherDoctor.getSystemId();
        }
        if (name.contains("appointment")) {
            return String.valueOf(otherFutureAppointment);
        }
        if (name.contains("consultation")) {
            return String.valueOf(otherConsultation);
        }
        if (name.contains("prescription")) {
            return String.valueOf(otherPrescription);
        }
        if (name.contains("invoice")) {
            return String.valueOf(otherInvoice);
        }
        if (name.equals("id")) {
            if (pattern.contains("time-off")) {
                return String.valueOf(otherTimeOff);
            }
            if (pattern.contains("invoice")) {
                return String.valueOf(otherInvoice);
            }
            if (pattern.contains("prescription")) {
                return String.valueOf(otherPrescription);
            }
            if (pattern.contains("consultation")) {
                return String.valueOf(otherConsultation);
            }
            if (pattern.contains("medical-record")) {
                return String.valueOf(otherRecord);
            }
            if (pattern.contains("lab-result")) {
                return String.valueOf(otherLab);
            }
            if (pattern.contains("imaging")) {
                return String.valueOf(otherImaging);
            }
            return String.valueOf(otherFutureAppointment);
        }
        return "1";
    }

    private MvcResult send(String method, String path, Map<String, String> params, Object body) throws Exception {
        MockHttpServletRequestBuilder builder = request(org.springframework.http.HttpMethod.valueOf(method), path);
        params.forEach(builder::param);
        return call(builder, doctor, body);
    }

    /** Filters every list honours: the other doctor and patient, and a window around today. */
    private Map<String, String> otherPracticeFilters() {
        LocalDate today = LocalDate.now();
        Map<String, String> params = new HashMap<>();
        params.put("doctorSystemId", otherDoctor.getSystemId());
        params.put("patientSystemId", otherPatient.getSystemId());
        params.put("from", today.minusDays(10).atStartOfDay().format(WALL_CLOCK));
        params.put("to", today.plusDays(10).atStartOfDay().format(WALL_CLOCK));
        params.put("date", today.plusDays(1).toString());
        params.put("size", "100");
        return params;
    }

    private static List<String> leaksIn(String body, List<String> secrets) {
        return secrets.stream().filter(body::contains).toList();
    }

    // ==================== READS ====================

    @Test
    void noDoctorReadRoute_revealsAnotherDoctorsPatientsOrRecords() throws Exception {
        List<String> secrets = List.of(otherPatient.getSystemId(),
                otherPatient.getFirstName() + " " + otherPatient.getLastName(), MARKER);
        Set<Route> routes = doctorRoutes(true);
        assertTrue(routes.size() > 30, "the doctor surface was not discovered: " + routes);

        Map<String, String> violations = new TreeMap<>();
        Map<String, Integer> statuses = new TreeMap<>();
        for (Route route : routes) {
            String path = otherPracticePath(route.pattern());
            MvcResult result = send(route.method(), path, otherPracticeFilters(), null);
            int status = result.getResponse().getStatus();
            statuses.put(route.toString(), status);
            String body = result.getResponse().getContentAsString();
            if (status >= 500) {
                violations.put(route.toString(), "answered " + status + ": " + body);
            } else if (status >= 200 && status < 300) {
                List<String> leaked = leaksIn(body, secrets);
                if (!leaked.isEmpty()) {
                    violations.put(route.toString(), "answered " + status + " with " + leaked + " (GET " + path + ")");
                }
            }
        }
        if (!violations.isEmpty()) {
            StringBuilder message = new StringBuilder(violations.size() + " doctor routes leak another practice:");
            violations.forEach((route, why) -> message.append("\n  ").append(route).append(": ").append(why));
            fail(message.toString());
        }

        // The main lists did answer (with the caller's own practice), so the check above was not vacuous.
        for (String own : List.of("GET /api/doctors/patients", "GET /api/doctors/schedule",
                "GET /api/doctors/appointments", "GET /api/doctors/prescriptions", "GET /api/doctors/medical-records",
                "GET /api/doctors/consultations", "GET /api/doctors/billing/invoices",
                "GET /api/doctors/schedule/dashboard")) {
            assertEquals(200, statuses.get(own), own + " should answer 200 for the caller's own practice");
        }
    }

    @Test
    void ownListsShowTheCallersPractice_butNeverTheOtherOne() throws Exception {
        String patients = doGet("/api/doctors/patients", doctor).getResponse().getContentAsString();
        assertTrue(patients.contains(patient.getSystemId()), patients);
        assertFalse(patients.contains(otherPatient.getSystemId()), patients);

        String prescriptions = doGet("/api/doctors/prescriptions", doctor).getResponse().getContentAsString();
        assertTrue(prescriptions.contains("Own medication"), prescriptions);
        assertFalse(prescriptions.contains(MARKER), prescriptions);

        String invoices = doGet("/api/doctors/billing/invoices", doctor).getResponse().getContentAsString();
        assertTrue(invoices.contains(patient.getSystemId()), invoices);
        assertFalse(invoices.contains(otherPatient.getSystemId()), invoices);

        String records = doGet("/api/doctors/medical-records", doctor).getResponse().getContentAsString();
        assertTrue(records.contains("Own record"), records);
        assertFalse(records.contains(MARKER), records);

        // The marked rows are visible to their own doctor, so the leak checks look for text that does show up.
        for (String own : List.of("/api/doctors/patients", "/api/doctors/prescriptions", "/api/doctors/medical-records",
                "/api/doctors/billing/invoices", "/api/doctors/schedule/window?from="
                        + LocalDate.now().minusDays(10).atStartOfDay().format(WALL_CLOCK) + "&to="
                        + LocalDate.now().plusDays(10).atStartOfDay().format(WALL_CLOCK))) {
            String body = doGet(own, otherDoctor).getResponse().getContentAsString();
            assertTrue(body.contains(otherPatient.getSystemId()) || body.contains(MARKER),
                    own + " as the other doctor shows neither their patient nor a marker: " + body);
        }
    }

    @Test
    void explicitOtherDoctorAndOtherPatientIds_areRefused() throws Exception {
        String other = otherDoctor.getSystemId();
        for (String url : List.of(
                "/api/doctors/consultations/" + other,
                "/api/consultations/doctor/" + other,
                "/api/prescriptions/doctor/" + other,
                "/api/medical-records/doctor/" + other,
                "/api/lab-results/doctor/" + other,
                "/api/appointments/doctor/" + other,
                "/api/doctors/patients/" + otherPatient.getSystemId() + "/detail",
                "/api/billing/invoices/" + otherInvoice,
                "/api/prescriptions/" + otherPrescription,
                "/api/medical-records/" + otherRecord,
                "/api/lab-results/" + otherLab,
                "/api/imaging-results/" + otherImaging,
                "/api/consultations/" + otherConsultation,
                "/api/appointments/" + otherFutureAppointment)) {
            MvcResult result = doGet(url, doctor);
            int status = result.getResponse().getStatus();
            assertTrue(status == 403 || status == 404, "GET " + url + " answered " + status + ": "
                    + result.getResponse().getContentAsString());
        }
        // The resource roots filtered by the other doctor: refused or empty, never their rows.
        for (String root : List.of("/api/appointments", "/api/consultations", "/api/prescriptions", "/api/lab-results",
                "/api/imaging-results", "/api/medical-records")) {
            MvcResult result = doGet(root + "?doctorSystemId=" + other, doctor);
            int status = result.getResponse().getStatus();
            String body = result.getResponse().getContentAsString();
            assertTrue(status == 403 || (status == 200 && !body.contains(otherPatient.getSystemId())),
                    root + "?doctorSystemId=" + other + " answered " + status + ": " + body);
        }
    }

    // ==================== WRITES ====================

    @Test
    void noDoctorWriteRoute_changesAnotherDoctorsRows() throws Exception {
        Map<String, Object> before = otherPracticeState();
        Set<Route> routes = doctorRoutes(false);
        assertTrue(routes.size() > 10, "the doctor write surface was not discovered: " + routes);

        Map<String, String> violations = new TreeMap<>();
        for (Route route : routes) {
            if (!PATH_VARIABLE.matcher(route.pattern()).find()) {
                continue; // acts on the caller's own data only (own profile, own availability, own fees)
            }
            String path = otherPracticePath(route.pattern());
            Map<String, String> params = new HashMap<>();
            Object body = null;
            if (route.pattern().endsWith("/status")) {
                params.put("status", route.pattern().contains("prescription") ? "COMPLETED" : "CANCELLED");
            }
            if (route.pattern().endsWith("/health-status")) {
                body = Map.of("healthStatus", "CRITICAL");
            } else if (route.pattern().endsWith("/mark-rescheduled")) {
                body = Map.of("scheduledAt", LocalDateTime.now().plusDays(5).truncatedTo(ChronoUnit.HOURS)
                        .withHour(11).format(WALL_CLOCK));
            } else if (route.pattern().startsWith("/api/doctors/profile/")) {
                body = Map.of("bio", MARKER + " overwritten");
            }
            MvcResult result = send(route.method(), path, params, body);
            int status = result.getResponse().getStatus();
            if (status != 403 && status != 404) {
                violations.put(route.toString(), route.method() + " " + path + " answered " + status + ": "
                        + result.getResponse().getContentAsString());
            }
        }
        if (!violations.isEmpty()) {
            StringBuilder message = new StringBuilder(violations.size()
                    + " doctor write routes were not refused for another practice:");
            violations.forEach((route, why) -> message.append("\n  ").append(why));
            fail(message.toString());
        }
        assertEquals(before, otherPracticeState(), "the other practice changed");
    }

    private Map<String, Object> otherPracticeState() {
        Map<String, Object> state = new TreeMap<>();
        state.put("appointments", jdbc.queryForList("SELECT appointment_id, status, scheduled_at FROM appointments "
                + "WHERE doctor_system_id = ? ORDER BY appointment_id", otherDoctor.getSystemId()).toString());
        state.put("consultations", jdbc.queryForList("SELECT consultation_id, status FROM consultations "
                + "WHERE doctor_system_id = ? ORDER BY consultation_id", otherDoctor.getSystemId()).toString());
        state.put("prescriptions", jdbc.queryForList("SELECT prescription_id, status FROM prescriptions "
                + "WHERE doctor_system_id = ? ORDER BY prescription_id", otherDoctor.getSystemId()).toString());
        state.put("patient", jdbc.queryForList("SELECT health_status FROM patients WHERE system_id = ?",
                otherPatient.getSystemId()).toString());
        state.put("timeOff", jdbc.queryForList("SELECT time_off_id FROM doctor_time_off WHERE doctor_system_id = ?",
                otherDoctor.getSystemId()).toString());
        state.put("profile", jdbc.queryForList("SELECT bio, is_active FROM doctors WHERE system_id = ?",
                otherDoctor.getSystemId()).toString());
        state.put("reminders", jdbc.queryForList("SELECT COUNT(*) FROM notifications WHERE system_id = ?",
                otherPatient.getSystemId()).toString());
        return state;
    }
}
