package com.carenexus.api.core.doctor;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.core.model.Appointment;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The doctor patient lists: the paged search (filters, sorts, paging, summary), the appointment-derived values
 * (next appointment, last visit, counters), the legacy array and the options list.
 */
class DoctorPatientListTest extends DoctorPatientsTestSupport {

    private static final String SEARCH = "/api/doctors/patients/search";

    private User moyo;
    private User ncube;
    private User zhou;
    private Appointment moyoNext;
    private LocalDateTime now;

    /**
     * Doctor DOC-00001 has four patients:
     * - PAT-00001 "PATIENT User1" (fixture care team), no gender, STABLE, no appointment;
     * - PAT-00101 "Anna Moyo", FEMALE, CRITICAL, six appointments with the doctor;
     * - PAT-00102 "Ben Ncube", MALE, STABLE, a future and a completed appointment;
     * - PAT-00103 "Carl Zhou", MALE, MONITOR, care team only.
     * PAT-00104 has appointments with another doctor only and is never listed.
     */
    private void givenPatients() {
        now = clinicNow();
        moyo = newPatient("PAT-00101", "Anna", "Moyo", "FEMALE", "CRITICAL");
        ncube = newPatient("PAT-00102", "Ben", "Ncube", "MALE", "STABLE");
        zhou = newPatient("PAT-00103", "Carl", "Zhou", "MALE", "MONITOR");
        User stranger = newPatient("PAT-00104", "Dora", "Aardvark", "FEMALE", "STABLE");

        appointment(moyo, doctor, now.plusDays(2), "SCHEDULED", "VIDEO");
        moyoNext = appointment(moyo, doctor, now.plusDays(1), "RESCHEDULED", "PHONE");
        appointment(moyo, doctor, now.minusDays(10), "COMPLETED");
        appointment(moyo, doctor, now.minusDays(3), "COMPLETED");
        appointment(moyo, doctor, now.plusHours(5), "CANCELLED");
        appointment(moyo, doctor, now.minusDays(1), "NO_SHOW");
        // Another doctor's appointments never count for this doctor.
        appointment(moyo, otherDoctor, now.plusHours(2), "SCHEDULED");

        appointment(ncube, doctor, now.plusDays(3), "SCHEDULED");
        appointment(ncube, doctor, now.minusDays(20), "COMPLETED");

        careTeam(zhou, doctor, "SPECIALIST", true);
        appointment(stranger, otherDoctor, now.plusDays(4), "SCHEDULED");
    }

    @Test
    void searchReturnsPageEnvelopeSummaryAndNameOrder() throws Exception {
        givenPatients();

        JsonNode page = json(ok(doGet(SEARCH, doctor)));
        assertEquals(List.of("PAT-00101", "PAT-00102", "PAT-00001", "PAT-00103"), ids(page.path("content"), "patientSystemId"));
        assertEquals(0, page.path("page").asInt());
        assertEquals(20, page.path("size").asInt());
        assertEquals(4, page.path("totalElements").asLong());
        assertEquals(1, page.path("totalPages").asInt());
        JsonNode summary = page.path("summary");
        assertEquals(4, summary.path("total").asLong());
        assertEquals(2, summary.path("stable").asLong());
        assertEquals(1, summary.path("monitor").asLong());
        assertEquals(1, summary.path("critical").asLong());

        JsonNode row = page.path("content").get(0);
        assertTrue(row.has("recentAppointments") && row.path("recentAppointments").isNull(),
                "search rows carry recentAppointments null");
        assertTrue(row.path("healthStatusNote").isNull());
        assertEquals("Anna", row.path("firstName").asText());
        assertEquals("PAT-00101", row.path("userSystemId").asText());
    }

    @Test
    void nextAppointmentLastVisitAndCountersComeFromThisDoctorsAppointments() throws Exception {
        givenPatients();

        JsonNode moyoRow = json(ok(getWithParams(SEARCH, doctor, "query", "moyo"))).path("content").get(0);
        assertEquals(moyoNext.getAppointmentId().intValue(), moyoRow.path("nextAppointmentId").asInt());
        assertEquals(moyoNext.getScheduledAt(), LocalDateTime.parse(moyoRow.path("nextAppointmentAt").asText()));
        assertEquals("PHONE", moyoRow.path("nextAppointmentType").asText());
        assertEquals(now.minusDays(3), LocalDateTime.parse(moyoRow.path("lastVisitAt").asText()));
        assertEquals(moyoRow.path("lastVisitAt").asText(), moyoRow.path("lastAppointmentDate").asText());
        assertEquals(6, moyoRow.path("totalAppointments").asInt());
        assertEquals(2, moyoRow.path("upcomingAppointmentCount").asInt());
        assertEquals(2, moyoRow.path("completedAppointmentCount").asInt());
        assertEquals(2, moyoRow.path("completedVisits").asInt());
        assertEquals(1, moyoRow.path("cancelledAppointmentCount").asInt());
        assertEquals(1, moyoRow.path("noShowCount").asInt());
        assertEquals(1, moyoRow.path("rescheduledCount").asInt());

        JsonNode zhouRow = json(ok(getWithParams(SEARCH, doctor, "query", "zhou"))).path("content").get(0);
        assertEquals(0, zhouRow.path("totalAppointments").asInt());
        assertTrue(zhouRow.path("nextAppointmentAt").isNull());
        assertTrue(zhouRow.path("nextAppointmentId").isNull());
        assertTrue(zhouRow.path("nextAppointmentType").isNull());
        assertTrue(zhouRow.path("lastVisitAt").isNull());
    }

    @Test
    void sortsOrderByNextAppointmentHealthStatusAndLastVisit() throws Exception {
        givenPatients();

        assertEquals(List.of("PAT-00101", "PAT-00102", "PAT-00001", "PAT-00103"),
                ids(json(ok(doGet(SEARCH + "?sort=nextAppointment", doctor))).path("content"), "patientSystemId"));
        assertEquals(List.of("PAT-00101", "PAT-00103", "PAT-00102", "PAT-00001"),
                ids(json(ok(doGet(SEARCH + "?sort=healthStatus", doctor))).path("content"), "patientSystemId"));
        assertEquals(List.of("PAT-00101", "PAT-00102", "PAT-00001", "PAT-00103"),
                ids(json(ok(doGet(SEARCH + "?sort=lastVisit", doctor))).path("content"), "patientSystemId"));
        assertEquals(List.of("PAT-00101", "PAT-00102", "PAT-00001", "PAT-00103"),
                ids(json(ok(doGet(SEARCH + "?sort=name", doctor))).path("content"), "patientSystemId"));

        MvcResult badSort = doGet(SEARCH + "?sort=Name", doctor);
        assertEquals(400, badSort.getResponse().getStatus());
        assertEquals("Invalid sort. Must be name, nextAppointment, healthStatus or lastVisit", message(badSort));
    }

    @Test
    void filtersCombineAndNeverChangeTheSummary() throws Exception {
        givenPatients();

        JsonNode critical = json(ok(doGet(SEARCH + "?healthStatus=critical", doctor)));
        assertEquals(List.of("PAT-00101"), ids(critical.path("content"), "patientSystemId"));
        assertEquals(1, critical.path("totalElements").asLong());
        assertEquals(4, critical.path("summary").path("total").asLong());

        assertEquals(List.of("PAT-00102", "PAT-00103"),
                ids(json(ok(doGet(SEARCH + "?gender=MALE", doctor))).path("content"), "patientSystemId"));
        assertEquals(4, json(ok(doGet(SEARCH + "?gender=ALL&healthStatus=ALL", doctor))).path("totalElements").asLong());
        assertEquals(List.of("PAT-00102"),
                ids(json(ok(doGet(SEARCH + "?gender=male&healthStatus=STABLE", doctor))).path("content"), "patientSystemId"));

        // Name, full name, email, id and phone search; % and _ are literal.
        assertEquals(List.of("PAT-00101"), ids(json(ok(getWithParams(SEARCH, doctor, "query", "ANNA MOY"))).path("content"), "patientSystemId"));
        assertEquals(List.of("PAT-00102"), ids(json(ok(getWithParams(SEARCH, doctor, "query", "pat-00102"))).path("content"), "patientSystemId"));
        assertEquals(List.of("PAT-00103"), ids(json(ok(getWithParams(SEARCH, doctor, "query", "pat-00103@example"))).path("content"), "patientSystemId"));
        assertEquals(List.of("PAT-00101"), ids(json(ok(getWithParams(SEARCH, doctor, "query", moyo.getPhone())))
                .path("content"), "patientSystemId"));
        assertEquals(0, json(ok(getWithParams(SEARCH, doctor, "query", "%"))).path("totalElements").asLong());
        assertEquals(0, json(ok(getWithParams(SEARCH, doctor, "query", "Aardvark"))).path("totalElements").asLong());

        MvcResult badStatus = doGet(SEARCH + "?healthStatus=GOOD", doctor);
        assertEquals(400, badStatus.getResponse().getStatus());
        assertEquals("Invalid health status. Must be STABLE, MONITOR or CRITICAL", message(badStatus));
        MvcResult badGender = doGet(SEARCH + "?gender=X", doctor);
        assertEquals(400, badGender.getResponse().getStatus());
        assertEquals("Invalid gender. Must be MALE, FEMALE or OTHER", message(badGender));
        MvcResult longQuery = getWithParams(SEARCH, doctor, "query", "a".repeat(101));
        assertEquals(400, longQuery.getResponse().getStatus());
        assertEquals("VALIDATION_FAILED", json(longQuery).path("code").asText());
        assertEquals("query: Search text must be at most 100 characters", message(longQuery));
    }

    @Test
    void pagingClampsSizeAndPagesNeverOverlap() throws Exception {
        givenPatients();

        JsonNode second = json(ok(doGet(SEARCH + "?size=2&page=1", doctor)));
        assertEquals(List.of("PAT-00001", "PAT-00103"), ids(second.path("content"), "patientSystemId"));
        assertEquals(2, second.path("totalPages").asInt());
        assertEquals(4, second.path("totalElements").asLong());

        JsonNode clampedLow = json(ok(doGet(SEARCH + "?size=0&page=-3", doctor)));
        assertEquals(1, clampedLow.path("size").asInt());
        assertEquals(0, clampedLow.path("page").asInt());
        assertEquals(1, clampedLow.path("content").size());
        assertEquals(100, json(ok(doGet(SEARCH + "?size=500", doctor))).path("size").asInt());

        JsonNode beyond = json(ok(doGet(SEARCH + "?page=9", doctor)));
        assertEquals(0, beyond.path("content").size());
        assertEquals(4, beyond.path("totalElements").asLong());

        MvcResult notANumber = doGet(SEARCH + "?page=abc", doctor);
        assertEquals(400, notANumber.getResponse().getStatus());
        assertEquals("Invalid value for parameter 'page'", message(notANumber));
    }

    @Test
    void doctorWithoutPatientsGetsEmptyPageAndZeroSummary() throws Exception {
        careTeamRepository.deleteAll();

        JsonNode page = json(ok(doGet(SEARCH, doctor)));
        assertEquals(0, page.path("content").size());
        assertEquals(0, page.path("totalElements").asLong());
        assertEquals(0, page.path("totalPages").asInt());
        assertEquals(0, page.path("summary").path("total").asLong());
        assertEquals(0, page.path("summary").path("critical").asLong());

        JsonNode legacy = json(ok(doGet("/api/doctors/patients", doctor)));
        assertTrue(legacy.isArray());
        assertEquals(0, legacy.size());
        assertEquals(0, count("SELECT COUNT(*) FROM audit_log WHERE action = 'READ' AND resource_type = 'PROFILE'"));
    }

    @Test
    void legacyArrayIsOrderedByNameWithRecentAppointments() throws Exception {
        givenPatients();

        JsonNode array = json(ok(doGet("/api/doctors/patients", doctor)));
        assertTrue(array.isArray());
        assertEquals(List.of("PAT-00101", "PAT-00102", "PAT-00001", "PAT-00103"), ids(array, "patientSystemId"));

        JsonNode moyoRecent = array.get(0).path("recentAppointments");
        assertEquals(5, moyoRecent.size(), "five most recent appointments with this doctor");
        assertEquals(now.plusDays(2), LocalDateTime.parse(moyoRecent.get(0).path("scheduledAt").asText()));
        assertEquals("Anna Moyo", moyoRecent.get(0).path("patientName").asText());
        for (JsonNode appointment : moyoRecent) {
            assertEquals(doctor.getSystemId(), appointment.path("doctorSystemId").asText());
        }
        assertEquals(0, array.get(3).path("recentAppointments").size());
        assertTrue(array.get(3).path("recentAppointments").isArray());
        // One READ / PROFILE audit row per patient returned.
        assertEquals(4, count("SELECT COUNT(*) FROM audit_log WHERE action = 'READ' AND resource_type = 'PROFILE' "
                + "AND system_id = ?", doctor.getSystemId()));
    }

    @Test
    void optionsListIdsAndNamesWithQueryAndAreNotAudited() throws Exception {
        givenPatients();

        JsonNode options = json(ok(doGet("/api/doctors/patients/options", doctor)));
        assertTrue(options.isArray());
        assertEquals(List.of("PAT-00101", "PAT-00102", "PAT-00001", "PAT-00103"), ids(options, "patientSystemId"));
        assertEquals("Anna Moyo", options.get(0).path("fullName").asText());
        assertEquals("Anna", options.get(0).path("firstName").asText());
        assertEquals("Moyo", options.get(0).path("lastName").asText());
        assertEquals(4, options.get(0).size(), "id and name fields only");

        assertEquals(List.of("PAT-00103"),
                ids(json(ok(getWithParams("/api/doctors/patients/options", doctor, "query", "PAT-00103"))), "patientSystemId"));
        assertEquals(List.of("PAT-00102"),
                ids(json(ok(getWithParams("/api/doctors/patients/options", doctor, "query", "ben nc"))), "patientSystemId"));
        assertEquals(0, count("SELECT COUNT(*) FROM audit_log WHERE action = 'READ' AND resource_type = 'PROFILE'"));

        MvcResult asAdmin = doGet("/api/doctors/patients/options", admin);
        assertEquals(403, asAdmin.getResponse().getStatus());
        assertEquals("Your role is not authorized to perform this action", message(asAdmin));
    }

    private static MvcResult ok(MvcResult result) throws Exception {
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return result;
    }
}
