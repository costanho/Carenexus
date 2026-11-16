package com.carenexus.api.health;

import com.carenexus.api.common.event.HealthMetricRecordedEvent;
import com.carenexus.api.health.model.HealthMetric;
import com.carenexus.api.health.service.HealthMetricIngestService;
import com.carenexus.api.health.service.MetricReading;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * POST / GET / DELETE /api/health-metrics (CONTRACT be-health-tracking 5.1 - 5.5): recording rules and severity,
 * the access matrix of 1.1, field and rule validation, paging and filters, latest with derived BMI, trend bounds and
 * aggregation, removal rules, idempotency, events and audit.
 */
@RecordApplicationEvents
class HealthMetricApiTest extends HealthApiTestBase {

    @Autowired
    private ApplicationEvents events;

    @Autowired
    private HealthMetricIngestService ingestService;

    private List<HealthMetricRecordedEvent> recordedEvents() {
        return events.stream(HealthMetricRecordedEvent.class).toList();
    }

    // ==================== RECORD ====================

    @Test
    void patientRecordsASession_storedAsManualWithOneGroupAndServerSeverity() throws Exception {
        LocalDateTime at = LocalDateTime.now().minusHours(1).withNano(0);
        Map<String, Object> systolic = reading("BP_SYSTOLIC", 148, at);
        systolic.put("unit", "mmHg");
        systolic.put("notes", "  After the morning walk  ");
        systolic.put("source", "WEARABLE"); // unknown property: ignored
        Map<String, Object> diastolic = reading("bp_diastolic", 92, at);
        diastolic.put("notes", "   ");
        Map<String, Object> body = session(null, systolic, diastolic, reading("HEART_RATE", 76));
        body.put("deviceId", "cuff-1");

        LocalDateTime before = LocalDateTime.now().withNano(0);
        JsonNode response = recordOk(patient, body);

        assertEquals(patient.getSystemId(), response.path("patientSystemId").asText());
        String group = response.path("readingGroupId").asText();
        assertEquals(36, group.length());
        JsonNode metrics = response.path("metrics");
        assertEquals(3, metrics.size());

        JsonNode sys = metrics.get(0);
        assertEquals("BP_SYSTOLIC", sys.path("type").asText());
        assertEquals(0, new BigDecimal("148").compareTo(sys.path("value").decimalValue()));
        assertEquals("mmHg", sys.path("unit").asText());
        assertEquals("MANUAL", sys.path("source").asText());
        assertTrue(sys.path("deviceId").isNull());
        assertEquals(group, sys.path("readingGroupId").asText());
        assertEquals("ABNORMAL", sys.path("severity").asText());
        assertTrue(sys.path("isAbnormal").asBoolean());
        assertEquals("After the morning walk", sys.path("notes").asText());
        assertEquals(wallClock(at), sys.path("recordedAt").asText());
        assertEquals(patient.getSystemId(), sys.path("recordedBySystemId").asText());
        assertEquals(nameOf(patient), sys.path("recordedByName").asText());
        assertTrue(sys.path("canDelete").asBoolean());

        JsonNode dia = metrics.get(1);
        assertEquals("BP_DIASTOLIC", dia.path("type").asText());
        assertEquals("ABNORMAL", dia.path("severity").asText());
        assertTrue(dia.path("notes").isNull(), "blank notes are stored as null");
        assertTrue(dia.has("notes"), "null fields are serialised");

        JsonNode hr = metrics.get(2);
        assertEquals("HEART_RATE", hr.path("type").asText());
        assertEquals("bpm", hr.path("unit").asText(), "an omitted unit is the unit of the type");
        assertEquals("NORMAL", hr.path("severity").asText());
        assertFalse(hr.path("isAbnormal").asBoolean());
        LocalDateTime recordedNow = LocalDateTime.parse(hr.path("recordedAt").asText());
        assertFalse(recordedNow.isBefore(before), "an omitted recordedAt is the server's clinic time");
        assertFalse(recordedNow.isAfter(LocalDateTime.now()));

        assertEquals(3, count("SELECT COUNT(*) FROM health_metrics WHERE reading_group_id = ? AND source = 'MANUAL' "
                + "AND device_id IS NULL AND patient_system_id = ?", group, patient.getSystemId()));
        assertEquals(3, auditRows("CREATE", "HEALTH_METRIC", patient.getSystemId()));

        List<HealthMetricRecordedEvent> published = recordedEvents();
        assertEquals(3, published.size(), "one event per stored row");
        for (HealthMetricRecordedEvent event : published) {
            assertEquals(patient.getSystemId(), event.patientSystemId());
            assertEquals(patient.getSystemId(), event.recordedBySystemId());
            assertEquals("MANUAL", event.source());
            assertEquals(!"NORMAL".equals(event.severity()), event.abnormal());
        }
        assertEquals(List.of("BP_SYSTOLIC", "BP_DIASTOLIC", "HEART_RATE"),
                published.stream().map(HealthMetricRecordedEvent::type).toList());
        assertEquals(List.of(true, true, false), published.stream().map(HealthMetricRecordedEvent::abnormal).toList());
    }

    @Test
    void criticalReadingIsClassifiedCritical() throws Exception {
        JsonNode response = recordOk(patient, session(null, reading("SPO2", 86), reading("TEMPERATURE", 36.8)));
        assertEquals("CRITICAL", response.path("metrics").get(0).path("severity").asText());
        assertTrue(response.path("metrics").get(0).path("isAbnormal").asBoolean());
        assertEquals("NORMAL", response.path("metrics").get(1).path("severity").asText());
        assertEquals("CRITICAL", recordedEvents().get(0).severity());
        assertEquals(1, count("SELECT COUNT(*) FROM health_metrics WHERE severity = 'CRITICAL' AND is_abnormal"));
    }

    // ==================== WRITE ACCESS MATRIX ====================

    @Test
    void writeAccessMatrix() throws Exception {
        Map<String, Object> body = session(patient.getSystemId(), reading("HEART_RATE", 70));

        assertEquals(201, record(patient, body).getResponse().getStatus());
        JsonNode byDoctor = recordOk(doctor, body);
        assertEquals(doctor.getSystemId(), byDoctor.path("metrics").get(0).path("recordedBySystemId").asText());

        MvcResult otherPatientCall = record(otherPatient, body);
        assertError(otherPatientCall, 403, "Patients can only access their own data");
        assertEquals("HEALTH_METRIC", json(otherPatientCall).path("resourceType").asText());
        assertError(record(otherDoctor, body), 403, "You are not part of this patient's care team");
        assertError(record(caregiver, body), 403, "You don't have active proxy access to this patient");
        assertError(record(admin, body), 403, "Your role is not authorized to perform this action");

        grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));
        assertError(record(caregiver, body), 403,
                "Your proxy access for this patient does not allow recording health readings");
        grantAccess(caregiver, patient, a -> {
            a.setCanManageCarePlan(true);
            a.setAccessLevel("VIEW_ONLY");
        });
        assertError(record(caregiver, body), 403,
                "Your proxy access for this patient does not allow recording health readings");
        grantAccess(caregiver, patient, a -> a.setCanManageCarePlan(true));
        JsonNode byCaregiver = recordOk(caregiver, body);
        assertEquals(caregiver.getSystemId(), byCaregiver.path("metrics").get(0).path("recordedBySystemId").asText());
        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        assertEquals(201, record(caregiver, body).getResponse().getStatus());

        grantAccess(caregiver, patient, a -> {
            a.setAccessLevel("FULL_ACCESS");
            a.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        });
        assertError(record(caregiver, body), 403, "You don't have active proxy access to this patient");
        grantAccess(caregiver, patient, a -> {
            a.setAccessLevel("FULL_ACCESS");
            a.setIsActive(false);
            a.setRevokedAt(LocalDateTime.now());
        });
        assertError(record(caregiver, body), 403, "You don't have active proxy access to this patient");

        assertEquals(4, count("SELECT COUNT(*) FROM health_metrics WHERE patient_system_id = ?", patient.getSystemId()));
        assertEquals(0, count("SELECT COUNT(*) FROM health_metrics WHERE recorded_by_system_id IN (?, ?, ?, ?)",
                otherPatient.getSystemId(), otherDoctor.getSystemId(), admin.getSystemId(), otherCaregiver.getSystemId()));
    }

    @Test
    void patientIdRules() throws Exception {
        // Doctors and caregivers must name the patient; a patient defaults to themself
        assertRuleError(record(doctor, session(null, reading("HEART_RATE", 70))), "patientSystemId is required");
        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        assertRuleError(record(caregiver, session(null, reading("HEART_RATE", 70))), "patientSystemId is required");
        assertError(record(admin, session(null, reading("HEART_RATE", 70))), 403,
                "Your role is not authorized to perform this action");
        // Role is checked before the rule validation of the readings
        assertError(record(admin, session(patient.getSystemId(), reading("BMI", 20))), 403,
                "Your role is not authorized to perform this action");
        // Access is checked before the rule validation of the readings
        assertError(record(otherDoctor, session(patient.getSystemId(), reading("NOPE", 20))), 403,
                "You are not part of this patient's care team");
        // A patient that does not exist: no relationship
        assertError(record(doctor, session("PAT-99999", reading("HEART_RATE", 70))), 403,
                "You are not part of this patient's care team");
        assertFieldError(record(patient, session("patient-1", reading("HEART_RATE", 70))),
                "patientSystemId", "must be a valid system id");
        assertEquals(0, count("SELECT COUNT(*) FROM health_metrics"));
        assertEquals(0, recordedEvents().size());
    }

    // ==================== VALIDATION ====================

    @Test
    void fieldValidation() throws Exception {
        assertFieldError(record(patient, session(null)), "readings", "must contain 1 to 10 readings");
        Map<String, Object> noReadings = new LinkedHashMap<>();
        assertFieldError(record(patient, noReadings), "readings", "must contain 1 to 10 readings");
        List<Map<String, Object>> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(reading("HEART_RATE", 70));
        }
        assertFieldError(record(patient, Map.of("readings", eleven)), "readings", "must contain 1 to 10 readings");

        Map<String, Object> noType = reading("HEART_RATE", 70);
        noType.remove("type");
        assertFieldError(record(patient, session(null, noType)), "readings[0].type", "must not be blank");
        Map<String, Object> noValue = reading("HEART_RATE", 70);
        noValue.remove("value");
        assertFieldError(record(patient, session(null, reading("SPO2", 97), noValue)), "readings[1].value",
                "must not be null");
        assertFieldError(record(patient, session(null, reading("GLUCOSE", new BigDecimal("5.1234")))),
                "readings[0].value", "must be a number with at most 3 decimals");
        assertFieldError(record(patient, session(null, reading("STEPS", new BigDecimal("12345678")))),
                "readings[0].value", "must be a number with at most 3 decimals");
        Map<String, Object> longUnit = reading("HEART_RATE", 70);
        longUnit.put("unit", "b".repeat(31));
        assertFieldError(record(patient, session(null, longUnit)), "readings[0].unit", "size must be between 0 and 30");
        Map<String, Object> longNotes = reading("HEART_RATE", 70);
        longNotes.put("notes", "n".repeat(501));
        assertFieldError(record(patient, session(null, longNotes)), "readings[0].notes",
                "size must be between 0 and 500");

        assertEquals(0, count("SELECT COUNT(*) FROM health_metrics"));
    }

    @Test
    void malformedBodies() throws Exception {
        assertError(raw("{\"readings\": [{\"type\": \"HEART_RATE\", \"value\": \"fast\"}]}"), 400,
                "Malformed request body");
        assertError(raw("{\"readings\": [{\"type\": \"HEART_RATE\", \"value\": 70, \"recordedAt\": \"yesterday\"}]}"),
                400, "Malformed request body");
        assertError(raw("{\"readings\": "), 400, "Malformed request body");
    }

    private MvcResult raw(String json) throws Exception {
        return mockMvc.perform(post(METRICS).header("Authorization", tokenFor(patient))
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    @Test
    void ruleValidation() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        assertRuleError(record(patient, session(null, reading("PULSE", 70))), "Unknown health metric type");
        assertRuleError(record(patient, session(null, reading("BMI", 22))),
                "BMI is calculated from height and weight and cannot be entered");
        assertRuleError(record(patient, session(null, reading("HEART_RATE", 70), reading("heart_rate", 72))),
                "Only one reading per type is allowed in a request");
        Map<String, Object> wrongUnit = reading("HEART_RATE", 70);
        wrongUnit.put("unit", "BPM");
        assertRuleError(record(patient, session(null, wrongUnit)), "Unit for HEART_RATE must be bpm");
        Map<String, Object> fahrenheit = reading("TEMPERATURE", 98.6);
        fahrenheit.put("unit", "F");
        assertRuleError(record(patient, session(null, fahrenheit)), "Unit for TEMPERATURE must be C");

        assertRuleError(record(patient, session(null, reading("HEART_RATE", 19))),
                "Heart rate must be between 20 and 300 bpm");
        assertRuleError(record(patient, session(null, reading("HEART_RATE", 300.5))),
                "Heart rate must be between 20 and 300 bpm");
        assertRuleError(record(patient, session(null, reading("GLUCOSE", 0.5))),
                "Blood glucose must be between 1 and 50 mmol/L");
        assertRuleError(record(patient, session(null, reading("TEMPERATURE", 98.6))),
                "Temperature must be between 25 and 45 C");
        assertRuleError(record(patient, session(null, reading("SPO2", 101))),
                "Oxygen saturation must be between 50 and 100 %");

        assertRuleError(record(patient, session(null, reading("BP_SYSTOLIC", 120))),
                "Blood pressure needs both systolic and diastolic readings");
        assertRuleError(record(patient, session(null, reading("BP_DIASTOLIC", 80), reading("HEART_RATE", 70))),
                "Blood pressure needs both systolic and diastolic readings");
        assertRuleError(record(patient, session(null, reading("BP_SYSTOLIC", 80), reading("BP_DIASTOLIC", 80))),
                "Systolic pressure must be higher than diastolic pressure");
        assertRuleError(record(patient, session(null, reading("BP_SYSTOLIC", 120, now.minusMinutes(10)),
                        reading("BP_DIASTOLIC", 80, now.minusMinutes(9)))),
                "Systolic and diastolic readings must have the same time");
        assertRuleError(record(patient, session(null, reading("BP_SYSTOLIC", 120, now.minusMinutes(10)),
                        reading("BP_DIASTOLIC", 80))),
                "Systolic and diastolic readings must have the same time");

        assertRuleError(record(patient, session(null, reading("WEIGHT", 70, now.plusMinutes(10)))),
                "recordedAt cannot be in the future");
        assertEquals(0, count("SELECT COUNT(*) FROM health_metrics"));

        // Five minutes of device-clock tolerance; no lower bound
        recordOk(patient, session(null, reading("WEIGHT", 70, now.plusMinutes(3))));
        recordOk(patient, session(null, reading("HEIGHT", 170, now.minusYears(3))));
        // Bounds of the plausible range are inclusive
        recordOk(patient, session(null, reading("STEPS", 0), reading("SPO2", 100), reading("GLUCOSE", 1)));
        assertEquals(5, count("SELECT COUNT(*) FROM health_metrics"));
    }

    // ==================== READ ACCESS MATRIX ====================

    @Test
    void readAccessMatrix() throws Exception {
        recordOk(patient, session(null, reading("HEART_RATE", 70)));
        List<String> reads = List.of(
                METRICS + "/patient/" + patient.getSystemId(),
                METRICS + "/patient/" + patient.getSystemId() + "/latest",
                METRICS + "/patient/" + patient.getSystemId() + "/trend?type=HEART_RATE");

        for (String url : reads) {
            assertEquals(200, doGet(url, patient).getResponse().getStatus(), url);
            assertEquals(200, doGet(url, doctor).getResponse().getStatus(), url);
            assertEquals(200, doGet(url, admin).getResponse().getStatus(), url);
            MvcResult denied = doGet(url, otherPatient);
            assertError(denied, 403, "Patients can only access their own data");
            assertEquals("HEALTH_METRIC", json(denied).path("resourceType").asText());
            assertError(doGet(url, otherDoctor), 403, "You are not part of this patient's care team");
            assertError(doGet(url, caregiver), 403, "You don't have active proxy access to this patient");
        }

        grantAccess(caregiver, patient, a -> a.setCanManageCarePlan(true));
        for (String url : reads) {
            assertError(doGet(url, caregiver), 403,
                    "Your proxy access for this patient does not allow viewing health readings");
        }
        grantAccess(caregiver, patient, a -> {
            a.setCanViewRecords(true);
            a.setAccessLevel("VIEW_ONLY");
        });
        for (String url : reads) {
            assertEquals(200, doGet(url, caregiver).getResponse().getStatus(), url);
        }
        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        for (String url : reads) {
            assertEquals(200, doGet(url, caregiver).getResponse().getStatus(), url);
        }
        grantAccess(caregiver, patient, a -> {
            a.setCanViewRecords(true);
            a.setExpiresAt(LocalDateTime.now().minusSeconds(5));
        });
        for (String url : reads) {
            assertError(doGet(url, caregiver), 403, "You don't have active proxy access to this patient");
        }
        // A caregiver of another patient learns nothing about this one
        grantAccess(otherCaregiver, otherPatient, a -> a.setAccessLevel("FULL_ACCESS"));
        assertError(doGet(reads.get(0), otherCaregiver), 403, "You don't have active proxy access to this patient");

        // Unknown patient: 403 for the related roles, empty for an admin
        assertError(doGet(METRICS + "/patient/PAT-99999", doctor), 403, "You are not part of this patient's care team");
        JsonNode adminEmpty = json(doGet(METRICS + "/patient/PAT-99999", admin));
        assertEquals(0, adminEmpty.path("totalElements").asInt());
    }

    // ==================== HISTORY ====================

    @Test
    void historyIsPagedNewestFirstWithTypeAndDayFilters() throws Exception {
        LocalDateTime today = LocalDate.now().atStartOfDay().plusSeconds(1);
        LocalDateTime threeDaysAgo = today.minusDays(3).withHour(9);
        LocalDateTime tenDaysAgo = today.minusDays(10).withHour(9);
        JsonNode bp = recordBloodPressure(patient, null, 128, 82, threeDaysAgo);
        recordOk(patient, session(null, reading("HEART_RATE", 72, tenDaysAgo)));
        recordOk(doctor, session(patient.getSystemId(), reading("WEIGHT", 70.5, today), reading("HEIGHT", 172, today)));
        recordOk(otherPatient, session(null, reading("HEART_RATE", 90)));

        String base = METRICS + "/patient/" + patient.getSystemId();
        JsonNode all = json(doGet(base, patient));
        assertEquals(5, all.path("totalElements").asInt());
        assertEquals(0, all.path("page").asInt());
        assertEquals(20, all.path("size").asInt());
        assertEquals(1, all.path("totalPages").asInt());
        List<String> types = new ArrayList<>();
        all.path("content").forEach(m -> types.add(m.path("type").asText()));
        // recordedAt DESC then metricId DESC: today's height then weight, the pair (diastolic first), heart rate
        assertEquals(List.of("HEIGHT", "WEIGHT", "BP_DIASTOLIC", "BP_SYSTOLIC", "HEART_RATE"), types);
        JsonNode weight = all.path("content").get(1);
        assertEquals(nameOf(doctor), weight.path("recordedByName").asText());
        assertTrue(weight.path("canDelete").asBoolean(), "the patient may remove any manual reading about them");
        assertEquals(0, new BigDecimal("70.5").compareTo(weight.path("value").decimalValue()));

        JsonNode asDoctor = json(doGet(base, doctor));
        assertTrue(asDoctor.path("content").get(1).path("canDelete").asBoolean(), "the doctor recorded the weight");
        assertFalse(asDoctor.path("content").get(2).path("canDelete").asBoolean(), "the patient recorded the pair");
        JsonNode asAdmin = json(doGet(base, admin));
        asAdmin.path("content").forEach(m -> assertFalse(m.path("canDelete").asBoolean()));

        JsonNode pair = json(doGet(base + "?type=bp_systolic,BP_DIASTOLIC", patient));
        assertEquals(2, pair.path("totalElements").asInt());
        pair.path("content").forEach(m ->
                assertEquals(bp.path("readingGroupId").asText(), m.path("readingGroupId").asText()));

        JsonNode recent = json(doGet(base + "?from=" + LocalDate.now().minusDays(3) + "&to=" + LocalDate.now(), patient));
        assertEquals(4, recent.path("totalElements").asInt(), "from and to are inclusive clinic days");
        JsonNode oneDay = json(doGet(base + "?from=" + LocalDate.now().minusDays(10)
                + "&to=" + LocalDate.now().minusDays(10), patient));
        assertEquals(1, oneDay.path("totalElements").asInt());
        assertEquals("HEART_RATE", oneDay.path("content").get(0).path("type").asText());

        JsonNode page1 = json(doGet(base + "?page=1&size=2", patient));
        assertEquals(1, page1.path("page").asInt());
        assertEquals(2, page1.path("size").asInt());
        assertEquals(3, page1.path("totalPages").asInt());
        assertEquals("BP_DIASTOLIC", page1.path("content").get(0).path("type").asText());
        JsonNode clamped = json(doGet(base + "?page=-4&size=500", patient));
        assertEquals(0, clamped.path("page").asInt());
        assertEquals(100, clamped.path("size").asInt());
        assertEquals(1, json(doGet(base + "?size=0", patient)).path("size").asInt());

        assertRuleError(doGet(base + "?type=HEART_RATE,PULSE", patient), "Unknown health metric type");
        assertRuleError(doGet(base + "?from=" + LocalDate.now() + "&to=" + LocalDate.now().minusDays(1), patient),
                "from must not be after to");
        assertError(doGet(base + "?from=10-11-2026", patient), 400, "Invalid value for parameter 'from'");
        assertError(doGet(base + "?page=first", patient), 400, "Invalid value for parameter 'page'");
        assertTrue(auditRows("READ", "HEALTH_METRIC", patient.getSystemId()) > 0);
    }

    // ==================== LATEST AND BMI ====================

    @Test
    void latestIsOnePerTypeInCanonicalOrderWithDerivedBmi() throws Exception {
        String url = METRICS + "/patient/" + patient.getSystemId() + "/latest";
        JsonNode empty = json(doGet(url, patient));
        assertEquals(0, empty.path("metrics").size());
        assertTrue(empty.path("bmi").isNull());
        assertTrue(empty.has("bmi"));

        LocalDateTime now = LocalDateTime.now().withNano(0);
        recordOk(patient, session(null, reading("HEART_RATE", 64, now.minusDays(2))));
        recordOk(patient, session(null, reading("HEART_RATE", 88, now.minusHours(1))));
        recordBloodPressure(patient, null, 150, 95, now.minusHours(3));
        recordOk(patient, session(null, reading("WEIGHT", 80, now.minusDays(30))));
        assertTrue(json(doGet(url, patient)).path("bmi").isNull(), "no height yet");

        JsonNode heightRow = recordOk(doctor, session(patient.getSystemId(), reading("HEIGHT", 172, now.minusDays(40))));
        JsonNode weightRow = recordOk(doctor, session(patient.getSystemId(), reading("WEIGHT", 71.5, now.minusDays(2))));

        JsonNode latest = json(doGet(url, patient));
        List<String> types = new ArrayList<>();
        latest.path("metrics").forEach(m -> types.add(m.path("type").asText()));
        assertEquals(List.of("BP_SYSTOLIC", "BP_DIASTOLIC", "HEART_RATE", "WEIGHT", "HEIGHT"), types);
        assertEquals(0, new BigDecimal("88").compareTo(latest.path("metrics").get(2).path("value").decimalValue()));
        assertEquals(0, new BigDecimal("71.5").compareTo(latest.path("metrics").get(3).path("value").decimalValue()));

        JsonNode bmi = latest.path("bmi");
        assertEquals(0, new BigDecimal("24.2").compareTo(bmi.path("value").decimalValue()));
        assertEquals("kg/m2", bmi.path("unit").asText());
        assertEquals("NORMAL", bmi.path("severity").asText());
        assertFalse(bmi.path("isAbnormal").asBoolean());
        assertEquals(weightRow.path("metrics").get(0).path("metricId").asInt(), bmi.path("weightMetricId").asInt());
        assertEquals(heightRow.path("metrics").get(0).path("metricId").asInt(), bmi.path("heightMetricId").asInt());
        assertEquals(weightRow.path("metrics").get(0).path("recordedAt").asText(), bmi.path("recordedAt").asText());

        // A heavier weight makes the BMI abnormal; removing it falls back to the previous one
        JsonNode heavy = recordOk(patient, session(null, reading("WEIGHT", 95)));
        JsonNode heavier = json(doGet(url, patient)).path("bmi");
        assertEquals(0, new BigDecimal("32.1").compareTo(heavier.path("value").decimalValue()));
        assertEquals("ABNORMAL", heavier.path("severity").asText());
        assertTrue(heavier.path("isAbnormal").asBoolean());
        assertEquals(204, doDelete(METRICS + "/" + heavy.path("metrics").get(0).path("metricId").asInt(), patient)
                .getResponse().getStatus());
        assertEquals(0, new BigDecimal("24.2").compareTo(json(doGet(url, patient)).path("bmi").path("value").decimalValue()));

        // BMI is never stored and never published
        assertEquals(0, count("SELECT COUNT(*) FROM health_metrics WHERE type = 'BMI'"));
        assertTrue(recordedEvents().stream().noneMatch(e -> "BMI".equals(e.type())));
    }

    // ==================== TREND ====================

    @Test
    void trendAggregatesClinicDaysAndOmitsEmptyDays() throws Exception {
        LocalDate today = LocalDate.now();
        recordBloodPressure(patient, null, 134, 84, today.minusDays(2).atTime(8, 0));
        recordBloodPressure(patient, null, 139, 88, today.minusDays(2).atTime(20, 15));
        recordBloodPressure(patient, null, 131, 80, today.minusDays(5).atTime(9, 0));
        recordBloodPressure(patient, null, 148, 92, today.atTime(0, 1));
        recordBloodPressure(patient, null, 120, 75, today.minusDays(7).atTime(23, 59));
        recordBloodPressure(patient, null, 110, 70, today.minusDays(40).atTime(12, 0));

        String base = METRICS + "/patient/" + patient.getSystemId() + "/trend";
        JsonNode week = json(doGet(base + "?type=bp_systolic&days=7", patient));
        assertEquals(patient.getSystemId(), week.path("patientSystemId").asText());
        assertEquals("BP_SYSTOLIC", week.path("type").asText());
        assertEquals("mmHg", week.path("unit").asText());
        assertEquals(7, week.path("days").asInt());
        assertEquals(today.minusDays(6).toString(), week.path("from").asText());
        assertEquals(today.toString(), week.path("to").asText());
        JsonNode points = week.path("points");
        assertEquals(3, points.size(), points.toString());
        assertEquals(today.minusDays(5).toString(), points.get(0).path("date").asText());
        JsonNode twoReadings = points.get(1);
        assertEquals(today.minusDays(2).toString(), twoReadings.path("date").asText());
        assertEquals(0, new BigDecimal("136.5").compareTo(twoReadings.path("avg").decimalValue()));
        assertEquals(0, new BigDecimal("134").compareTo(twoReadings.path("min").decimalValue()));
        assertEquals(0, new BigDecimal("139").compareTo(twoReadings.path("max").decimalValue()));
        assertEquals(2, twoReadings.path("count").asInt());
        assertFalse(twoReadings.path("abnormal").asBoolean());
        assertEquals(today.toString(), points.get(2).path("date").asText());
        assertTrue(points.get(2).path("abnormal").asBoolean());

        JsonNode defaults = json(doGet(base + "?type=BP_SYSTOLIC", patient));
        assertEquals(30, defaults.path("days").asInt());
        assertEquals(4, defaults.path("points").size());
        assertEquals(5, json(doGet(base + "?type=BP_SYSTOLIC&days=366", patient)).path("points").size());
        JsonNode one = json(doGet(base + "?type=BP_DIASTOLIC&days=1", patient));
        assertEquals(1, one.path("points").size());
        assertEquals(today.toString(), one.path("from").asText());
        assertEquals(0, json(doGet(base + "?type=BMI", patient)).path("points").size());

        // Averages are rounded HALF_UP to two decimals
        recordOk(patient, session(null, reading("HEART_RATE", 70, today.atTime(0, 2)),
                reading("SPO2", 97, today.atTime(0, 2))));
        recordOk(patient, session(null, reading("HEART_RATE", 70, today.atTime(0, 3))));
        recordOk(patient, session(null, reading("HEART_RATE", 71, today.atTime(0, 4))));
        JsonNode hr = json(doGet(base + "?type=HEART_RATE&days=1", patient)).path("points").get(0);
        assertEquals(0, new BigDecimal("70.33").compareTo(hr.path("avg").decimalValue()), hr.toString());
        assertEquals(3, hr.path("count").asInt());

        assertFieldError(doGet(base + "?type=HEART_RATE&days=0", patient), "days",
                "must be greater than or equal to 1");
        assertFieldError(doGet(base + "?type=HEART_RATE&days=367", patient), "days",
                "must be less than or equal to 366");
        assertError(doGet(base + "?type=HEART_RATE&days=week", patient), 400, "Invalid value for parameter 'days'");
        assertError(doGet(base, patient), 400, "Missing required parameter 'type'");
        assertRuleError(doGet(base + "?type=PULSE", patient), "Unknown health metric type");
        assertRuleError(doGet(base + "?type=BP_SYSTOLIC,BP_DIASTOLIC", patient), "Unknown health metric type");
        // One READ row per successful trend call (6 above), none for the refused ones
        assertEquals(6, count("SELECT COUNT(*) FROM audit_log WHERE action = 'READ' AND resource_type = 'HEALTH_METRIC' "
                + "AND resource_id IS NULL AND system_id = ? AND request_path LIKE '%/trend'", patient.getSystemId()));
    }

    // ==================== DELETE ====================

    @Test
    void patientRemovesABloodPressurePairInOneCall() throws Exception {
        JsonNode pair = recordBloodPressure(doctor, patient.getSystemId(), 150, 95, LocalDateTime.now().minusHours(2));
        JsonNode hr = recordOk(patient, session(null, reading("HEART_RATE", 70)));
        int diastolicId = pair.path("metrics").get(1).path("metricId").asInt();
        events.clear();

        assertEquals(204, doDelete(METRICS + "/" + diastolicId, patient).getResponse().getStatus());
        assertEquals(0, count("SELECT COUNT(*) FROM health_metrics WHERE reading_group_id = ?",
                pair.path("readingGroupId").asText()), "the partner is removed too");
        assertEquals(1, count("SELECT COUNT(*) FROM health_metrics WHERE patient_system_id = ?", patient.getSystemId()));
        assertEquals(2, auditRows("DELETE", "HEALTH_METRIC", patient.getSystemId()));
        assertEquals(0, recordedEvents().size(), "no event on delete");

        assertError(doDelete(METRICS + "/" + diastolicId, patient), 404, "Health metric not found");
        assertError(doDelete(METRICS + "/abc", patient), 400, "Invalid value for parameter 'metricId'");
        JsonNode latest = json(doGet(METRICS + "/patient/" + patient.getSystemId() + "/latest", patient));
        assertEquals(1, latest.path("metrics").size());
        assertEquals(hr.path("metrics").get(0).path("metricId").asInt(), latest.path("metrics").get(0).path("metricId").asInt());
    }

    @Test
    void deleteRules() throws Exception {
        int byPatient = recordOk(patient, session(null, reading("HEART_RATE", 70))).path("metrics").get(0).path("metricId").asInt();
        int byDoctor = recordOk(doctor, session(patient.getSystemId(), reading("WEIGHT", 70))).path("metrics").get(0).path("metricId").asInt();
        grantAccess(caregiver, patient, a -> a.setCanManageCarePlan(true));
        int byCaregiver = recordOk(caregiver, session(patient.getSystemId(), reading("SPO2", 97))).path("metrics").get(0).path("metricId").asInt();
        int byCaregiver2 = recordOk(caregiver, session(patient.getSystemId(), reading("GLUCOSE", 5.5))).path("metrics").get(0).path("metricId").asInt();
        String notOwner = "Only the patient or the person who recorded a reading can delete it";

        // Nobody but the patient and the recorder (ADMIN included)
        assertError(doDelete(METRICS + "/" + byPatient, doctor), 403, notOwner);
        assertError(doDelete(METRICS + "/" + byPatient, caregiver), 403, notOwner);
        assertError(doDelete(METRICS + "/" + byPatient, admin), 403, notOwner);
        assertError(doDelete(METRICS + "/" + byPatient, otherPatient), 403, notOwner);
        assertError(doDelete(METRICS + "/" + byDoctor, otherDoctor), 403, notOwner);
        assertEquals(5, count("SELECT COUNT(*) FROM audit_log WHERE action = 'ACCESS_DENIED' "
                + "AND resource_type = 'HEALTH_METRIC' AND status = 'FAILURE'"));

        // The recorder may delete while they still hold write access
        assertEquals(204, doDelete(METRICS + "/" + byDoctor, doctor).getResponse().getStatus());
        assertEquals(204, doDelete(METRICS + "/" + byCaregiver, caregiver).getResponse().getStatus());
        grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));
        assertError(doDelete(METRICS + "/" + byCaregiver2, caregiver), 403,
                "Your proxy access for this patient does not allow recording health readings");
        // ... and the patient may delete what anybody entered about them
        assertEquals(204, doDelete(METRICS + "/" + byCaregiver2, patient).getResponse().getStatus());
        assertEquals(204, doDelete(METRICS + "/" + byPatient, patient).getResponse().getStatus());
        assertEquals(0, count("SELECT COUNT(*) FROM health_metrics"));
    }

    @Test
    void ingestedReadingsCannotBeDeleted_andCarryTheirSourceAndDevice() throws Exception {
        List<HealthMetric> stored = ingestService.ingest(patient.getSystemId(), "wearable", "  watch-77  ", List.of(
                new MetricReading("HEART_RATE", new BigDecimal("135"), "bpm", LocalDateTime.now().minusMinutes(30), null),
                new MetricReading("STEPS", new BigDecimal("8200"), null, null, "daily total")));
        assertEquals(2, stored.size());
        assertEquals("WEARABLE", stored.get(0).getSource());
        assertEquals("watch-77", stored.get(0).getDeviceId());
        assertNull(stored.get(0).getRecordedBySystemId());
        assertEquals("CRITICAL", stored.get(0).getSeverity());
        assertEquals(stored.get(0).getReadingGroupId(), stored.get(1).getReadingGroupId());

        List<HealthMetricRecordedEvent> published = recordedEvents();
        assertEquals(2, published.size());
        assertEquals("WEARABLE", published.get(0).source());
        assertNull(published.get(0).recordedBySystemId());
        assertTrue(published.get(0).abnormal());

        JsonNode history = json(doGet(METRICS + "/patient/" + patient.getSystemId(), patient));
        history.path("content").forEach(m -> {
            assertFalse(m.path("canDelete").asBoolean());
            assertTrue(m.path("recordedByName").isNull());
        });
        assertError(doDelete(METRICS + "/" + stored.get(0).getMetricId(), patient), 409,
                "Only manually entered readings can be deleted");
        assertEquals(2, count("SELECT COUNT(*) FROM health_metrics"));
    }

    @Test
    void ingestAppliesTheSameRules() {
        String patientId = patient.getSystemId();
        var unit = assertThrows(com.carenexus.api.common.exception.BadRequestException.class, () ->
                ingestService.ingest(patientId, "DEVICE", null,
                        List.of(new MetricReading("GLUCOSE", new BigDecimal("100"), "mg/dL", null, null))));
        assertEquals("Unit for GLUCOSE must be mmol/L", unit.getMessage());
        var range = assertThrows(com.carenexus.api.common.exception.BadRequestException.class, () ->
                ingestService.ingest(patientId, "DEVICE", null,
                        List.of(new MetricReading("SPO2", new BigDecimal("40"), null, null, null))));
        assertEquals("Oxygen saturation must be between 50 and 100 %", range.getMessage());
        var future = assertThrows(com.carenexus.api.common.exception.BadRequestException.class, () ->
                ingestService.ingest(patientId, "INTEGRATION", null, List.of(new MetricReading("WEIGHT",
                        new BigDecimal("70"), null, LocalDateTime.now().plusHours(1), null))));
        assertEquals("recordedAt cannot be in the future", future.getMessage());
        assertThrows(IllegalArgumentException.class, () -> ingestService.ingest(patientId, "MANUAL", null,
                List.of(new MetricReading("WEIGHT", new BigDecimal("70"), null, null, null))));
        assertThrows(IllegalArgumentException.class, () -> ingestService.ingest("PAT-99999", "DEVICE", null,
                List.of(new MetricReading("WEIGHT", new BigDecimal("70"), null, null, null))));
        assertEquals(0, count("SELECT COUNT(*) FROM health_metrics"));
        assertEquals(0, recordedEvents().size());
    }

    // ==================== IDEMPOTENCY ====================

    @Test
    void idempotencyKeyReplaysTheFirstAnswer() throws Exception {
        Map<String, Object> body = session(null, reading("HEART_RATE", 70), reading("SPO2", 98));
        Map<String, String> key = Map.of("Idempotency-Key", "vitals-form-0001");

        MvcResult first = doPostWithHeaders(METRICS, patient, body, key);
        assertEquals(201, first.getResponse().getStatus(), first.getResponse().getContentAsString());
        MvcResult replay = doPostWithHeaders(METRICS, patient, session(null, reading("HEART_RATE", 99)), key);
        assertEquals(200, replay.getResponse().getStatus(), replay.getResponse().getContentAsString());
        assertEquals(json(first).path("readingGroupId").asText(), json(replay).path("readingGroupId").asText());
        assertEquals(2, json(replay).path("metrics").size());
        assertEquals(2, count("SELECT COUNT(*) FROM health_metrics"));
        assertEquals(2, recordedEvents().size(), "nothing is published again on a replay");

        // Another caller with the same key is a different request
        assertEquals(201, doPostWithHeaders(METRICS, doctor, session(patient.getSystemId(), reading("WEIGHT", 70)), key)
                .getResponse().getStatus());
        assertError(doPostWithHeaders(METRICS, patient, body, Map.of("Idempotency-Key", "short")), 400,
                "Idempotency-Key must be 8 to 64 characters: letters, digits, '_' or '-'");
        assertEquals(3, count("SELECT COUNT(*) FROM health_metrics"));
    }

    @Test
    void unauthenticatedCallsAre401() throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get(METRICS + "/patient/" + patient.getSystemId())).andReturn();
        assertEquals(401, result.getResponse().getStatus());
        assertEquals("Authentication required", message(result));
    }
}
