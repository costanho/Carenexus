package com.carenexus.api.scheduling;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.carenexus.api.scheduling.AvailabilityFixtures.block;
import static com.carenexus.api.scheduling.AvailabilityFixtures.clinicNow;
import static com.carenexus.api.scheduling.AvailabilityFixtures.insertAppointment;
import static com.carenexus.api.scheduling.AvailabilityFixtures.insertTimeOff;
import static com.carenexus.api.scheduling.AvailabilityFixtures.template;
import static com.carenexus.api.scheduling.AvailabilityFixtures.timeOff;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The doctor's own availability endpoints with committed requests: weekly template (GET, PUT and every validation
 * rule) and time off (list, create with idempotency, delete), route and service authorization for every role, and
 * the audit rows.
 */
public class DoctorAvailabilityApiTest extends CommittedIntegrationTest {

    private static final String URL = "/api/doctors/availability";
    private static final String TIME_OFF_URL = URL + "/time-off";
    private static final DateTimeFormatter ISO_MINUTES = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static final String S1 = "You don't have permission to access this resource";
    private static final String S2 = "Your role is not authorized to perform this action";

    private static String iso(LocalDateTime t) {
        return t.format(ISO_MINUTES);
    }

    private List<Map<String, Object>> auditRows(String action) {
        return jdbc.queryForList("SELECT system_id, action, resource_type, target_system_id, status FROM audit_log "
                + "WHERE action = ? ORDER BY audit_id", action);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private void assertValidation(MvcResult result, String expectedMessage) throws Exception {
        assertEquals(400, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(expectedMessage, message(result));
        assertEquals("VALIDATION_FAILED", json(result).path("code").asText());
    }

    private MvcResult putRaw(String rawJson, User as) throws Exception {
        return mockMvc.perform(put(URL).header("Authorization", tokenFor(as))
                .contentType(MediaType.APPLICATION_JSON).content(rawJson)).andReturn();
    }

    // ==================== WEEKLY TEMPLATE ====================

    @Test
    void doctorWithoutTemplate_getsClinicHours() throws Exception {
        MvcResult result = doGet(URL, doctor);

        assertEquals(200, result.getResponse().getStatus());
        JsonNode body = json(result);
        assertEquals("DOC-00001", body.path("doctorSystemId").asText());
        assertEquals("Africa/Harare", body.path("timeZone").asText());
        assertEquals("CLINIC_HOURS", body.path("source").asText());
        assertEquals(30, body.path("slotMinutes").asInt());
        assertEquals(0, body.path("blocks").size());
    }

    @Test
    void put_replacesTheWholeTemplate_returnsItOrdered_andIsAudited() throws Exception {
        List<Map<String, Object>> blocks = List.of(
                block(4, "09:00", "12:00", true),
                block(1, "14:00", "18:00"),
                block(6, "09:00", "12:00", false),
                block(1, "08:00", "13:00"));

        MvcResult result = doPut(URL, doctor, template(20, blocks));

        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode body = json(result);
        assertEquals("TEMPLATE", body.path("source").asText());
        assertEquals(20, body.path("slotMinutes").asInt());
        JsonNode stored = body.path("blocks");
        assertEquals(4, stored.size());
        assertEquals("1 08:00 13:00 true", line(stored.get(0)));
        assertEquals("1 14:00 18:00 true", line(stored.get(1)));
        assertEquals("4 09:00 12:00 true", line(stored.get(2)));
        assertEquals("6 09:00 12:00 false", line(stored.get(3)));
        assertEquals(body, json(doGet(URL, doctor)), "GET returns what PUT stored");

        // A second PUT replaces every row (same start times included), with its own slot length.
        MvcResult replaced = doPut(URL, doctor, template(30, List.of(block(1, "08:00", "10:00"))));
        assertEquals(200, replaced.getResponse().getStatus());
        assertEquals(1, json(replaced).path("blocks").size());
        assertEquals(30, json(replaced).path("slotMinutes").asInt());
        assertEquals(1, count("SELECT COUNT(*) FROM doctor_availability WHERE doctor_system_id = 'DOC-00001'"));
        assertEquals(30, count("SELECT MIN(slot_minutes) FROM doctor_availability WHERE doctor_system_id = 'DOC-00001'"));

        // An empty template returns the doctor to clinic hours.
        MvcResult emptied = doPut(URL, doctor, template(45, List.of()));
        assertEquals(200, emptied.getResponse().getStatus());
        assertEquals("CLINIC_HOURS", json(emptied).path("source").asText());
        assertEquals(30, json(emptied).path("slotMinutes").asInt());
        assertEquals(0, count("SELECT COUNT(*) FROM doctor_availability"));

        List<Map<String, Object>> audit = auditRows("AVAILABILITY_UPDATE");
        assertEquals(3, audit.size());
        assertEquals("DOC-00001", audit.get(0).get("system_id"));
        assertEquals("AVAILABILITY", audit.get(0).get("resource_type"));
        assertEquals("DOC-00001", audit.get(0).get("target_system_id"));
        assertEquals("SUCCESS", audit.get(0).get("status"));

        // The other doctor's template was never touched and a doctor id in the body is ignored.
        Map<String, Object> withForeignId = template(30, List.of(block(2, "09:00", "10:00")));
        withForeignId.put("doctorSystemId", otherDoctor.getSystemId());
        assertEquals(200, doPut(URL, doctor, withForeignId).getResponse().getStatus());
        assertEquals("CLINIC_HOURS", json(doGet(URL, otherDoctor)).path("source").asText());
    }

    private static String line(JsonNode b) {
        return b.path("dayOfWeek").asInt() + " " + b.path("startTime").asText() + " " + b.path("endTime").asText()
                + " " + b.path("isActive").asBoolean();
    }

    @Test
    void put_beanValidationFailures_areReportedPerField() throws Exception {
        assertValidation(doPut(URL, doctor, template(null, List.of())), "slotMinutes: is required");
        assertValidation(doPut(URL, doctor, template(7, List.of())), "slotMinutes: must be between 5 and 240 in steps of 5");
        assertValidation(doPut(URL, doctor, template(245, List.of())), "slotMinutes: must be between 5 and 240 in steps of 5");
        assertValidation(doPut(URL, doctor, template(30, null)), "blocks: is required");

        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 43; i++) {
            tooMany.add(block(1, "08:00", "09:00"));
        }
        assertValidation(doPut(URL, doctor, template(30, tooMany)), "blocks: must contain at most 42 blocks");

        Map<String, Object> noDay = block(1, "08:00", "09:00");
        noDay.remove("dayOfWeek");
        assertValidation(doPut(URL, doctor, template(30, List.of(noDay))), "blocks[0].dayOfWeek: is required");
        assertValidation(doPut(URL, doctor, template(30, List.of(block(1, "08:00", "09:00"), block(8, "08:00", "09:00")))),
                "blocks[1].dayOfWeek: must be between 1 (Monday) and 7 (Sunday)");
        assertValidation(doPut(URL, doctor, template(30, List.of(block(1, "", "09:00")))), "blocks[0].startTime: is required");
        assertValidation(doPut(URL, doctor, template(30, List.of(block(1, "8:00", "09:00")))),
                "blocks[0].startTime: must be a time in HH:mm format");
        assertValidation(doPut(URL, doctor, template(30, List.of(block(1, "08:00", "24:00")))),
                "blocks[0].endTime: must be a time in HH:mm format");

        MvcResult result = doPut(URL, doctor, template(30, List.of(block(0, "8:00", "09:00"))));
        assertEquals(2, json(result).path("errors").size(), "every failing field is listed");
        assertEquals(0, count("SELECT COUNT(*) FROM doctor_availability"));
        assertTrue(auditRows("AVAILABILITY_UPDATE").isEmpty());
    }

    @Test
    void put_wrongJsonTypes_areMalformed() throws Exception {
        assertEquals("Malformed request body", message(putRaw("{\"slotMinutes\":\"30\",\"blocks\":[]}", doctor)));
        assertEquals("Malformed request body", message(putRaw("{\"slotMinutes\":30.5,\"blocks\":[]}", doctor)));
        assertEquals("Malformed request body", message(putRaw(
                "{\"slotMinutes\":30,\"blocks\":[{\"dayOfWeek\":1,\"startTime\":800,\"endTime\":\"13:00\"}]}", doctor)));
        assertEquals("Malformed request body", message(putRaw(
                "{\"slotMinutes\":30,\"blocks\":[{\"dayOfWeek\":1,\"startTime\":\"08:00\",\"endTime\":\"13:00\",\"isActive\":\"yes\"}]}",
                doctor)));
        assertEquals(400, putRaw("not json", doctor).getResponse().getStatus());
    }

    @Test
    void put_serviceRules_areCheckedInContractOrder() throws Exception {
        // Rule 1: 5-minute boundaries.
        assertValidation(doPut(URL, doctor, template(30, List.of(block(1, "08:03", "09:00")))),
                "blocks[0].startTime: must be on a 5-minute boundary");
        assertValidation(doPut(URL, doctor, template(30, List.of(block(1, "08:00", "09:59")))),
                "blocks[0].endTime: must be on a 5-minute boundary");
        // Rule 2: end after start.
        assertValidation(doPut(URL, doctor, template(30, List.of(block(1, "10:00", "10:00")))),
                "blocks[0].endTime: must be after startTime");
        assertValidation(doPut(URL, doctor, template(30, List.of(block(1, "11:00", "10:00")))),
                "blocks[0].endTime: must be after startTime");
        // Rule 3: at least one slot long.
        assertValidation(doPut(URL, doctor, template(60, List.of(block(1, "09:00", "09:45")))),
                "blocks[0].endTime: block must be at least as long as the slot length");
        // Rules 5 and 6: clinic hours 08:00-18:00 in the test profile.
        assertValidation(doPut(URL, doctor, template(30, List.of(block(1, "07:30", "09:00")))),
                "blocks[0].startTime: must not be before the clinic opens at 08:00");
        assertValidation(doPut(URL, doctor, template(30, List.of(block(1, "17:00", "18:30")))),
                "blocks[0].endTime: must not be after the clinic closes at 18:00");
        // Rules run block by block: block 0 is checked completely before block 1.
        assertValidation(doPut(URL, doctor, template(30, List.of(block(1, "07:00", "09:00"), block(1, "08:03", "09:00")))),
                "blocks[0].startTime: must not be before the clinic opens at 08:00");

        // Rule 7: at most 6 blocks per weekday.
        List<Map<String, Object>> seven = new ArrayList<>();
        for (int h = 8; h < 15; h++) {
            seven.add(block(2, String.format("%02d:00", h), String.format("%02d:30", h)));
        }
        assertValidation(doPut(URL, doctor, template(30, seven)), "blocks: at most 6 blocks per day");

        // Rule 8: overlap on one weekday (inactive blocks count), reported at the later block.
        assertValidation(doPut(URL, doctor, template(30, List.of(
                        block(1, "08:00", "12:00"),
                        block(2, "09:00", "10:00"),
                        block(1, "11:30", "13:00", false)))),
                "blocks[2].startTime: overlaps another block on the same day");
        assertValidation(doPut(URL, doctor, template(30, List.of(block(3, "09:00", "10:00"), block(3, "08:00", "17:00")))),
                "blocks[1].startTime: overlaps another block on the same day");

        // Touching blocks are allowed.
        MvcResult touching = doPut(URL, doctor, template(30, List.of(block(1, "08:00", "12:00"), block(1, "12:00", "13:00"))));
        assertEquals(200, touching.getResponse().getStatus(), touching.getResponse().getContentAsString());

        // A refused PUT leaves the stored template unchanged.
        assertValidation(doPut(URL, doctor, template(30, List.of(block(1, "08:03", "09:00")))),
                "blocks[0].startTime: must be on a 5-minute boundary");
        assertEquals(2, count("SELECT COUNT(*) FROM doctor_availability WHERE doctor_system_id = 'DOC-00001'"));
        assertEquals(1, auditRows("AVAILABILITY_UPDATE").size());
    }

    @Test
    void template_isDoctorSelfOnly() throws Exception {
        Map<String, Object> body = template(30, List.of(block(1, "08:00", "10:00")));
        for (User other : List.of(patient, caregiver)) {
            MvcResult read = doGet(URL, other);
            assertEquals(403, read.getResponse().getStatus());
            assertEquals(S1, message(read));
            MvcResult write = doPut(URL, other, body);
            assertEquals(403, write.getResponse().getStatus());
            assertEquals(S1, message(write));
        }
        MvcResult adminRead = doGet(URL, admin);
        assertEquals(403, adminRead.getResponse().getStatus());
        assertEquals(S2, message(adminRead));
        MvcResult adminWrite = doPut(URL, admin, body);
        assertEquals(403, adminWrite.getResponse().getStatus());
        assertEquals(S2, message(adminWrite));

        assertEquals(401, mockMvc.perform(get(URL)).andReturn().getResponse().getStatus());
        assertEquals(0, count("SELECT COUNT(*) FROM doctor_availability"));
        assertTrue(auditRows("AVAILABILITY_UPDATE").isEmpty());
    }

    // ==================== TIME OFF ====================

    @Test
    void createTimeOff_countsConflictingAppointments_trimsReason_andIsAudited() throws Exception {
        LocalDateTime start = clinicNow().plusDays(10).toLocalDate().atStartOfDay();
        LocalDateTime end = start.plusDays(3);
        insertAppointment(jdbc, patient.getSystemId(), doctor.getSystemId(), start.plusHours(9), 30, "SCHEDULED");
        insertAppointment(jdbc, patient.getSystemId(), doctor.getSystemId(), start.plusDays(1).plusHours(9), 30, "RESCHEDULED");
        insertAppointment(jdbc, patient.getSystemId(), doctor.getSystemId(), start.plusDays(1).plusHours(11), 30, "CANCELLED");
        insertAppointment(jdbc, patient.getSystemId(), doctor.getSystemId(), end, 30, "SCHEDULED");               // touches the end
        insertAppointment(jdbc, otherPatient.getSystemId(), otherDoctor.getSystemId(), start.plusHours(10), 30, "SCHEDULED");

        MvcResult result = doPost(TIME_OFF_URL, doctor, timeOff(start.toString(), iso(end) , "  Annual leave  "));

        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode body = json(result);
        assertTrue(body.path("timeOffId").asInt() > 0);
        assertEquals("DOC-00001", body.path("doctorSystemId").asText());
        assertEquals(iso(start), body.path("startsAt").asText(), "seconds are always present");
        assertEquals(iso(end), body.path("endsAt").asText());
        assertEquals("Annual leave", body.path("reason").asText());
        assertEquals(2, body.path("conflictingAppointments").asInt());
        assertFalse(body.path("createdAt").asText().isBlank());
        // Time off never touches appointments.
        assertEquals(4, count("SELECT COUNT(*) FROM appointments WHERE status IN ('SCHEDULED','RESCHEDULED')"));

        List<Map<String, Object>> audit = auditRows("TIME_OFF_CREATE");
        assertEquals(1, audit.size());
        assertEquals("DOC-00001", audit.get(0).get("system_id"));
        assertEquals("AVAILABILITY", audit.get(0).get("resource_type"));
        assertEquals("DOC-00001", audit.get(0).get("target_system_id"));

        // A blank reason is stored as null; seconds are dropped; a range may start in the past.
        MvcResult running = doPost(TIME_OFF_URL, doctor,
                timeOff(iso(clinicNow().minusHours(2).withSecond(42)).substring(0, 17) + "42", iso(clinicNow().plusHours(2)), "   "));
        assertEquals(201, running.getResponse().getStatus(), running.getResponse().getContentAsString());
        assertTrue(json(running).path("reason").isNull());
        assertTrue(json(running).path("startsAt").asText().endsWith(":00"));
    }

    @Test
    void createTimeOff_withIdempotencyKey_isCreatedOnce() throws Exception {
        LocalDateTime start = clinicNow().plusDays(5).toLocalDate().atTime(9, 0);
        Map<String, String> headers = Map.of("Idempotency-Key", "time-off-key-0001");

        MvcResult first = doPostWithHeaders(TIME_OFF_URL, doctor, timeOff(iso(start), iso(start.plusHours(4)), "Course"), headers);
        MvcResult replay = doPostWithHeaders(TIME_OFF_URL, doctor,
                timeOff(iso(start.plusDays(1)), iso(start.plusDays(2)), "Different body"), headers);

        assertEquals(201, first.getResponse().getStatus(), first.getResponse().getContentAsString());
        assertEquals(200, replay.getResponse().getStatus(), replay.getResponse().getContentAsString());
        assertEquals(json(first), json(replay), "the stored response is returned unchanged");
        assertEquals(1, count("SELECT COUNT(*) FROM doctor_time_off"));
        assertEquals(1, auditRows("TIME_OFF_CREATE").size());

        MvcResult badKey = doPostWithHeaders(TIME_OFF_URL, doctor, timeOff(iso(start), iso(start.plusHours(1)), null),
                Map.of("Idempotency-Key", "short"));
        assertEquals(400, badKey.getResponse().getStatus());
        assertEquals("Idempotency-Key must be 8 to 64 characters: letters, digits, '_' or '-'", message(badKey));
    }

    @Test
    void createTimeOff_validation() throws Exception {
        LocalDateTime start = clinicNow().plusDays(3).toLocalDate().atTime(9, 0);

        assertValidation(doPost(TIME_OFF_URL, doctor, timeOff(null, iso(start), null)), "startsAt: is required");
        assertValidation(doPost(TIME_OFF_URL, doctor, timeOff(iso(start), null, null)), "endsAt: is required");
        assertValidation(doPost(TIME_OFF_URL, doctor, timeOff(iso(start), iso(start.plusHours(1)), "x".repeat(201))),
                "reason: must be at most 200 characters");
        assertValidation(doPost(TIME_OFF_URL, doctor, timeOff(iso(start), iso(start), null)), "endsAt: must be after startsAt");
        assertValidation(doPost(TIME_OFF_URL, doctor, timeOff(iso(start), iso(start.withSecond(30)).substring(0, 17) + "30", null)),
                "endsAt: must be after startsAt");
        assertValidation(doPost(TIME_OFF_URL, doctor,
                        timeOff(iso(clinicNow().minusDays(2)), iso(clinicNow().minusMinutes(5)), null)),
                "endsAt: must be in the future");
        assertValidation(doPost(TIME_OFF_URL, doctor, timeOff(iso(start), iso(start.plusDays(366).plusMinutes(1)), null)),
                "endsAt: time off can be at most 366 days long");
        assertEquals(201, doPost(TIME_OFF_URL, doctor, timeOff(iso(start), iso(start.plusDays(366)), null))
                .getResponse().getStatus(), "exactly 366 days is allowed");

        assertEquals("Malformed request body",
                message(doPost(TIME_OFF_URL, doctor, timeOff(iso(start) + "Z", iso(start.plusHours(1)), null))));
        assertEquals("Malformed request body",
                message(doPost(TIME_OFF_URL, doctor, timeOff(iso(start) + "+02:00", iso(start.plusHours(1)), null))));
        assertEquals("Malformed request body",
                message(doPost(TIME_OFF_URL, doctor, timeOff("24/12/2026", iso(start.plusHours(1)), null))));
        assertEquals(1, auditRows("TIME_OFF_CREATE").size());
    }

    @Test
    void listTimeOff_isPagedSoonestFirst_andHidesEndedRangesUnlessAsked() throws Exception {
        LocalDateTime now = clinicNow().withSecond(0).withNano(0);
        int past = insertTimeOff(jdbc, doctor.getSystemId(), now.minusDays(5), now.minusDays(4), "Past");
        int upcomingLater = insertTimeOff(jdbc, doctor.getSystemId(), now.plusDays(20), now.plusDays(21), "Later");
        int running = insertTimeOff(jdbc, doctor.getSystemId(), now.minusDays(1), now.plusDays(1), "Running");
        int upcoming = insertTimeOff(jdbc, doctor.getSystemId(), now.plusDays(10), now.plusDays(11), null);
        insertTimeOff(jdbc, otherDoctor.getSystemId(), now.plusDays(2), now.plusDays(3), "Not mine");
        insertAppointment(jdbc, patient.getSystemId(), doctor.getSystemId(), now.plusDays(10).plusHours(1), 30, "SCHEDULED");
        insertAppointment(jdbc, patient.getSystemId(), doctor.getSystemId(), now.plusDays(10).plusHours(3), 30, "SCHEDULED");

        JsonNode current = json(doGet(TIME_OFF_URL, doctor));
        assertEquals(3, current.path("totalElements").asInt());
        assertEquals(List.of(running, upcoming, upcomingLater), ids(current));
        assertEquals(2, current.path("content").get(1).path("conflictingAppointments").asInt());
        assertEquals(0, current.path("content").get(0).path("conflictingAppointments").asInt());
        assertEquals(0, current.path("page").asInt());
        assertEquals(20, current.path("size").asInt());
        assertEquals(1, current.path("totalPages").asInt());

        JsonNode all = json(doGet(TIME_OFF_URL + "?includePast=true", doctor));
        assertEquals(List.of(past, running, upcoming, upcomingLater), ids(all));

        JsonNode secondPage = json(doGet(TIME_OFF_URL + "?includePast=true&page=1&size=3", doctor));
        assertEquals(List.of(upcomingLater), ids(secondPage));
        assertEquals(2, secondPage.path("totalPages").asInt());

        assertEquals(100, json(doGet(TIME_OFF_URL + "?size=500", doctor)).path("size").asInt());
        assertEquals(1, json(doGet(TIME_OFF_URL + "?size=0", doctor)).path("size").asInt());
        assertEquals(0, json(doGet(TIME_OFF_URL + "?page=-3", doctor)).path("page").asInt());

        MvcResult bad = doGet(TIME_OFF_URL + "?includePast=maybe", doctor);
        assertEquals(400, bad.getResponse().getStatus());
        assertEquals("Invalid value for parameter 'includePast'", message(bad));
        assertEquals("Invalid value for parameter 'size'", message(doGet(TIME_OFF_URL + "?size=ten", doctor)));
    }

    private static List<Integer> ids(JsonNode page) {
        List<Integer> ids = new ArrayList<>();
        page.path("content").forEach(row -> ids.add(row.path("timeOffId").asInt()));
        return ids;
    }

    @Test
    void deleteTimeOff_removesOwnRowsOnly() throws Exception {
        LocalDateTime now = clinicNow().withSecond(0).withNano(0);
        int mine = insertTimeOff(jdbc, doctor.getSystemId(), now.plusDays(1), now.plusDays(2), null);
        int pastMine = insertTimeOff(jdbc, doctor.getSystemId(), now.minusDays(3), now.minusDays(2), null);
        int theirs = insertTimeOff(jdbc, otherDoctor.getSystemId(), now.plusDays(1), now.plusDays(2), null);

        MvcResult foreign = doDelete(TIME_OFF_URL + "/" + theirs, doctor);
        assertEquals(404, foreign.getResponse().getStatus());
        assertEquals("Time off not found", message(foreign));

        assertEquals(204, doDelete(TIME_OFF_URL + "/" + mine, doctor).getResponse().getStatus());
        assertEquals(204, doDelete(TIME_OFF_URL + "/" + pastMine, doctor).getResponse().getStatus());
        MvcResult again = doDelete(TIME_OFF_URL + "/" + mine, doctor);
        assertEquals(404, again.getResponse().getStatus());
        assertEquals("Time off not found", message(again));
        assertEquals("Invalid value for parameter 'id'", message(doDelete(TIME_OFF_URL + "/abc", doctor)));

        assertEquals(1, count("SELECT COUNT(*) FROM doctor_time_off"));
        assertEquals(1, count("SELECT COUNT(*) FROM doctor_time_off WHERE time_off_id = ?", theirs));
        List<Map<String, Object>> audit = auditRows("TIME_OFF_DELETE");
        assertEquals(2, audit.size());
        assertEquals("DOC-00001", audit.get(0).get("target_system_id"));
    }

    @Test
    void timeOff_isDoctorSelfOnly() throws Exception {
        LocalDateTime now = clinicNow().withSecond(0).withNano(0);
        int row = insertTimeOff(jdbc, doctor.getSystemId(), now.plusDays(1), now.plusDays(2), "Private");
        Map<String, Object> body = timeOff(iso(now.plusDays(3)), iso(now.plusDays(4)), null);

        for (User other : List.of(patient, caregiver)) {
            assertEquals(S1, message(doGet(TIME_OFF_URL, other)));
            assertEquals(S1, message(doPost(TIME_OFF_URL, other, body)));
            assertEquals(S1, message(doDelete(TIME_OFF_URL + "/" + row, other)));
        }
        MvcResult adminList = doGet(TIME_OFF_URL, admin);
        assertEquals(403, adminList.getResponse().getStatus());
        assertEquals(S2, message(adminList));
        assertEquals(S2, message(doPost(TIME_OFF_URL, admin, body)));
        assertEquals(S2, message(doDelete(TIME_OFF_URL + "/" + row, admin)));

        // The other doctor sees none of these rows.
        assertEquals(0, json(doGet(TIME_OFF_URL + "?includePast=true", otherDoctor)).path("totalElements").asInt());
        assertEquals(1, count("SELECT COUNT(*) FROM doctor_time_off"));
        assertTrue(auditRows("TIME_OFF_CREATE").isEmpty());
        assertTrue(auditRows("TIME_OFF_DELETE").isEmpty());
    }
}
