package com.carenexus.api.releasegates;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.core.doctor.DoctorPatientsTestSupport;
import com.carenexus.api.core.model.Appointment;
import com.carenexus.api.core.model.Doctor;
import com.carenexus.api.core.service.RelationshipService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Release gates for be-doctor-directory-patients: who a doctor's patients are (care team, non-cancelled
 * appointment, consultation; never a cancelled-only booking), the role matrix of every endpoint, the health-status
 * change with its audit rows and notifications, the doctor directory (never pending, rejected, suspended or
 * deactivated doctors), the public profile views, and the profile update rules (no email or licence change by the
 * doctor). Runs with committed transactions.
 */
class DoctorDirectoryPatientsReleaseGateTest extends DoctorPatientsTestSupport {

    private static final String PATIENTS = "/api/doctors/patients";
    private static final String ROLE_DENIED = "Your role is not authorized to perform this action";
    private static final String NOT_CARE_TEAM = "You are not part of this patient's care team";
    private static final String FILTER_DENIED = "You don't have permission to access this resource";

    @Autowired
    private RelationshipService relationshipService;

    // ==================== RELATED SET ====================

    @Test
    void patientSetIsCareTeamNonCancelledAppointmentsAndConsultationsOnly() throws Exception {
        User booked = newPatient("PAT-00101", "Brian", "Banda", "MALE", "STABLE");
        User consulted = newPatient("PAT-00102", "Chipo", "Cele", "FEMALE", "MONITOR");
        User cancelledOnly = newPatient("PAT-00103", "Dan", "Dube", "MALE", "STABLE");
        User inactiveCareTeam = newPatient("PAT-00104", "Eve", "Eze", "FEMALE", "STABLE");
        appointment(booked, doctor, clinicNow().minusDays(2), "COMPLETED");
        Appointment withOther = appointment(consulted, otherDoctor, clinicNow().minusDays(5), "COMPLETED");
        consultation(consulted, doctor, withOther);
        appointment(cancelledOnly, doctor, clinicNow().plusDays(1), "CANCELLED");
        careTeam(inactiveCareTeam, doctor, "DOCTOR", false);

        MvcResult legacy = doGet(PATIENTS, doctor);
        assertEquals(200, legacy.getResponse().getStatus());
        JsonNode array = json(legacy);
        assertTrue(array.isArray(), "the root keeps returning a bare array");
        assertEquals(List.of("PAT-00101", "PAT-00102", "PAT-00001"), ids(array, "patientSystemId"));
        JsonNode consultationOnly = array.get(1);
        assertEquals(0, consultationOnly.path("totalAppointments").asInt());
        assertEquals(0, consultationOnly.path("recentAppointments").size());
        assertTrue(consultationOnly.path("lastVisitAt").isNull());

        // The same set as RelationshipService, and the profile counter agrees with the search summary.
        List<String> related = relationshipService.getPatientsForDoctor(doctor.getSystemId());
        assertEquals(3, related.size());
        assertTrue(related.containsAll(List.of("PAT-00101", "PAT-00102", "PAT-00001")));
        JsonNode search = json(doGet(PATIENTS + "/search?size=1", doctor));
        assertEquals(3, search.path("totalElements").asLong());
        assertEquals(3, search.path("summary").path("total").asLong());
        assertEquals(3, json(doGet("/api/doctors/profile", doctor)).path("totalPatients").asInt());

        // Detail: care-team-only and consultation-only patients are readable, a cancelled-only one is not.
        assertEquals(200, doGet(PATIENTS + "/PAT-00001/detail", doctor).getResponse().getStatus());
        assertEquals(200, doGet(PATIENTS + "/PAT-00102/detail", doctor).getResponse().getStatus());
        MvcResult cancelled = doGet(PATIENTS + "/PAT-00103/detail", doctor);
        assertEquals(403, cancelled.getResponse().getStatus());
        assertEquals(NOT_CARE_TEAM, message(cancelled));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'ACCESS_DENIED' AND system_id = ? "
                + "AND patient_system_id = 'PAT-00103'", doctor.getSystemId()));
        assertEquals(403, doGet(PATIENTS + "/PAT-00104/detail", doctor).getResponse().getStatus());
        MvcResult unknown = doGet(PATIENTS + "/PAT-99999/detail", doctor);
        assertEquals(403, unknown.getResponse().getStatus());
        assertEquals(NOT_CARE_TEAM, message(unknown));
    }

    @Test
    void roleMatrixOfThePatientEndpoints() throws Exception {
        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));

        // Another doctor cannot read this doctor's patient.
        MvcResult other = doGet(PATIENTS + "/PAT-00001/detail", otherDoctor);
        assertEquals(403, other.getResponse().getStatus());
        assertEquals(NOT_CARE_TEAM, message(other));
        assertFalse(ids(json(doGet(PATIENTS, otherDoctor)), "patientSystemId").contains("PAT-00001"));

        // ADMIN has no patient list and no detail.
        for (String url : List.of(PATIENTS, PATIENTS + "/search", PATIENTS + "/options", PATIENTS + "/PAT-00001/detail")) {
            MvcResult asAdmin = doGet(url, admin);
            assertEquals(403, asAdmin.getResponse().getStatus(), url);
            assertEquals(ROLE_DENIED, message(asAdmin), url);
        }
        // PATIENT and CAREGIVER (whatever grant) are stopped by the route rule.
        for (User user : List.of(patient, caregiver)) {
            for (String url : List.of(PATIENTS, PATIENTS + "/search", PATIENTS + "/options", PATIENTS + "/PAT-00001/detail")) {
                MvcResult result = doGet(url, user);
                assertEquals(403, result.getResponse().getStatus(), url);
                assertEquals(FILTER_DENIED, message(result), url);
            }
        }
    }

    @Test
    void detailCarriesAppointmentsAndIsAudited() throws Exception {
        Appointment next = appointment(patient, doctor, clinicNow().plusDays(1), "SCHEDULED", "VIDEO");
        appointment(patient, doctor, clinicNow().minusDays(7), "COMPLETED");

        JsonNode detail = json(doGet(PATIENTS + "/PAT-00001/detail", doctor));
        assertEquals("PAT-00001", detail.path("patientSystemId").asText());
        assertEquals(next.getAppointmentId().intValue(), detail.path("nextAppointmentId").asInt());
        assertEquals("VIDEO", detail.path("nextAppointmentType").asText());
        assertEquals(2, detail.path("recentAppointments").size());
        assertEquals(1, detail.path("completedVisits").asInt());
        assertTrue(detail.has("healthStatusNote") && detail.path("healthStatusNote").isNull());
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'READ' AND resource_type = 'PROFILE' "
                + "AND patient_system_id = 'PAT-00001' AND system_id = ?", doctor.getSystemId()));
    }

    @Test
    void removedRoutesAnswerNotFound() throws Exception {
        for (String url : List.of(PATIENTS + "/high-risk", PATIENTS + "/gender/male", PATIENTS + "/health-status/critical",
                PATIENTS + "/scheduled", PATIENTS + "/filter", PATIENTS + "/DOC-00001/scheduled",
                "/api/doctors/profile/search/name?query=a", "/api/doctors/profile/filter/active?isActive=true")) {
            assertEquals(404, doGet(url, doctor).getResponse().getStatus(), url);
        }
        assertEquals(404, doPatch("/api/doctors/profile/DOC-00002/status/activate", admin, null).getResponse().getStatus());
        assertEquals(404, doPatch("/api/doctors/profile/DOC-00002/status/toggle", admin, null).getResponse().getStatus());
        assertEquals(405, doPatch("/api/doctors/profile/bio", doctor, Map.of("bio", "x")).getResponse().getStatus());
        // The old single-segment directory paths now fall into the by-id read.
        MvcResult available = doGet("/api/doctors/profile/available", doctor);
        assertEquals(403, available.getResponse().getStatus());
        assertEquals("You can only access your own doctor data", message(available));
    }

    // ==================== HEALTH STATUS ====================

    @Test
    void healthStatusMatrixAuditAndCriticalNotifications() throws Exception {
        grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));
        grantAccess(otherCaregiver, patient, a -> a.setCanViewAppointments(true));
        String url = PATIENTS + "/PAT-00001/health-status";
        Map<String, Object> critical = Map.of("healthStatus", "critical", "note", "  Readmitted overnight, daily review  ");

        // Patient, caregiver (even FULL_ACCESS) and an unrelated doctor cannot set it.
        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        assertEquals(403, doPatch(url, patient, critical).getResponse().getStatus());
        assertEquals(403, doPatch(url, caregiver, critical).getResponse().getStatus());
        MvcResult unrelated = doPatch(url, otherDoctor, critical);
        assertEquals(403, unrelated.getResponse().getStatus());
        assertEquals(NOT_CARE_TEAM, message(unrelated));
        assertEquals("STABLE", jdbc.queryForObject("SELECT health_status FROM patients WHERE system_id = 'PAT-00001'", String.class));
        grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));

        // The related doctor changes it into CRITICAL.
        MvcResult changed = doPatch(url, doctor, critical);
        assertEquals(200, changed.getResponse().getStatus(), changed.getResponse().getContentAsString());
        JsonNode body = json(changed);
        assertEquals("CRITICAL", body.path("healthStatus").asText());
        assertEquals("STABLE", body.path("previousHealthStatus").asText());
        assertTrue(body.path("changed").asBoolean());
        assertEquals("Readmitted overnight, daily review", body.path("healthStatusNote").asText());
        assertEquals(doctor.getSystemId(), body.path("healthStatusUpdatedBySystemId").asText());
        assertEquals("Dr. DOCTOR User2", body.path("healthStatusUpdatedByName").asText());
        assertFalse(body.path("healthStatusUpdatedAt").isNull());
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'UPDATE' AND resource_type = 'PROFILE' "
                + "AND patient_system_id = 'PAT-00001'"));

        // Patient and the caregiver holding RECORDS are notified, generically; the other caregiver is not.
        Map<String, Object> patientNote = notificationOf(patient);
        assertEquals("CRITICAL", patientNote.get("category"));
        assertEquals("HIGH", patientNote.get("priority"));
        assertEquals("SYSTEM", patientNote.get("type"));
        assertEquals("Health status update", patientNote.get("title"));
        assertEquals("Your care team updated your health status. Please contact your care team.", patientNote.get("message"));
        assertEquals("/dashboard/patient?section=care-team", patientNote.get("action_url"));
        assertTrue(((String) patientNote.get("dedupe_key")).startsWith("HEALTH_STATUS_CRITICAL:PAT-00001:"));
        Map<String, Object> caregiverNote = notificationOf(caregiver);
        assertEquals("The care team updated the health status of PATIENT User1. Please contact the care team.",
                caregiverNote.get("message"));
        assertEquals("/dashboard/proxy?section=patient-profile&patient=PAT-00001", caregiverNote.get("action_url"));
        assertEquals(0, count("SELECT COUNT(*) FROM notifications WHERE system_id = ?", otherCaregiver.getSystemId()));
        assertEquals(0, count("SELECT COUNT(*) FROM notifications WHERE system_id = ?", doctor.getSystemId()));
        assertEquals(0, count("SELECT COUNT(*) FROM notifications WHERE message ILIKE '%critical%' OR message ILIKE '%readmitted%'"));

        // The detail now shows the note.
        JsonNode detail = json(doGet(PATIENTS + "/PAT-00001/detail", doctor));
        assertEquals("CRITICAL", detail.path("healthStatus").asText());
        assertEquals("Readmitted overnight, daily review", detail.path("healthStatusNote").asText());
        assertEquals("Dr. DOCTOR User2", detail.path("healthStatusUpdatedByName").asText());

        // Same status, no note: nothing stored, no audit row.
        JsonNode noop = json(doPatch(url, doctor, Map.of("healthStatus", "CRITICAL")));
        assertFalse(noop.path("changed").asBoolean());
        assertEquals("CRITICAL", noop.path("previousHealthStatus").asText());
        assertEquals("Readmitted overnight, daily review", noop.path("healthStatusNote").asText());
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'UPDATE' AND resource_type = 'PROFILE'"));

        // Same status with a note: note replaced and audited, nobody notified again.
        JsonNode noteOnly = json(doPatch(url, doctor, Map.of("healthStatus", "CRITICAL", "note", "Stable overnight")));
        assertFalse(noteOnly.path("changed").asBoolean());
        assertEquals("Stable overnight", noteOnly.path("healthStatusNote").asText());
        assertEquals(2, count("SELECT COUNT(*) FROM audit_log WHERE action = 'UPDATE' AND resource_type = 'PROFILE'"));
        assertEquals(2, count("SELECT COUNT(*) FROM notifications"));

        // Out of CRITICAL (admin, no note): stored, note cleared, nobody notified.
        JsonNode down = json(doPatch(url, admin, Map.of("healthStatus", "MONITOR")));
        assertTrue(down.path("changed").asBoolean());
        assertTrue(down.path("healthStatusNote").isNull());
        assertEquals("ADMIN User7", down.path("healthStatusUpdatedByName").asText());
        assertEquals(2, count("SELECT COUNT(*) FROM notifications"));
        assertEquals("MONITOR", jdbc.queryForObject("SELECT health_status FROM patients WHERE system_id = 'PAT-00001'", String.class));
    }

    @Test
    void healthStatusValidationAndAdminNotFound() throws Exception {
        String url = PATIENTS + "/PAT-00001/health-status";

        MvcResult missing = doPatch(url, doctor, Map.of("note", "x"));
        assertEquals(400, missing.getResponse().getStatus());
        assertEquals("VALIDATION_FAILED", json(missing).path("code").asText());
        assertEquals("healthStatus: Health status is required", message(missing));

        MvcResult blank = doPatch(url, doctor, Map.of("healthStatus", "  "));
        assertEquals("healthStatus: Health status is required", message(blank));

        MvcResult unknown = doPatch(url, doctor, Map.of("healthStatus", "GOOD"));
        assertEquals(400, unknown.getResponse().getStatus());
        assertEquals("healthStatus: Health status must be STABLE, MONITOR or CRITICAL", message(unknown));

        MvcResult longNote = doPatch(url, doctor, Map.of("healthStatus", "MONITOR", "note", "n".repeat(501)));
        assertEquals(400, longNote.getResponse().getStatus());
        assertEquals("note: Note must be at most 500 characters", message(longNote));
        assertEquals("STABLE", jdbc.queryForObject("SELECT health_status FROM patients WHERE system_id = 'PAT-00001'", String.class));

        MvcResult adminUnknown = doPatch(PATIENTS + "/PAT-99999/health-status", admin, Map.of("healthStatus", "STABLE"));
        assertEquals(404, adminUnknown.getResponse().getStatus());
        assertEquals("Patient not found", message(adminUnknown));
        MvcResult doctorUnknown = doPatch(PATIENTS + "/PAT-99999/health-status", doctor, Map.of("healthStatus", "STABLE"));
        assertEquals(403, doctorUnknown.getResponse().getStatus());

        // An admin may set any patient's status; MONITOR does not notify.
        assertEquals(200, doPatch(PATIENTS + "/PAT-00002/health-status", admin, Map.of("healthStatus", "MONITOR"))
                .getResponse().getStatus());
        assertEquals(0, count("SELECT COUNT(*) FROM notifications"));
    }

    // ==================== DIRECTORY AND PUBLIC PROFILE ====================

    @Test
    void directoryListsOnlyApprovedActiveDoctors() throws Exception {
        newDoctor("DOC-00101", "Grace", "Adams", "Cardiology", Doctor.VERIFICATION_PENDING, false, true);
        newDoctor("DOC-00102", "Henry", "Brown", "Cardiology", Doctor.VERIFICATION_REJECTED, false, true);
        newDoctor("DOC-00103", "Ivy", "Chiwa", "Cardiology", Doctor.VERIFICATION_SUSPENDED, false, false);
        newDoctor("DOC-00104", "Jack", "Dlamini", "Cardiology", Doctor.VERIFICATION_APPROVED, false, true);
        newDoctor("DOC-00105", "Kuda", "Evans", "Cardiology", Doctor.VERIFICATION_APPROVED, true, false);
        newDoctor("DOC-00106", "Lerato", "Fani", "Paediatric Cardiology", Doctor.VERIFICATION_APPROVED, true, true);

        for (User viewer : List.of(patient, caregiver, doctor, admin)) {
            JsonNode page = json(doGet("/api/doctors/profile/directory", viewer));
            assertEquals(List.of("DOC-00106", "DOC-00001", "DOC-00002"), ids(page.path("content"), "doctorSystemId"),
                    viewer.getRole());
            assertEquals(3, page.path("totalElements").asLong());
        }
        JsonNode row = json(doGet("/api/doctors/profile/directory", patient)).path("content").get(0);
        assertTrue(row.path("isActive").asBoolean());
        assertEquals("Paediatric Cardiology", row.path("specialization").asText());
        for (String hidden : List.of("email", "phone", "licenseNo", "verificationStatus", "totalPatients", "role")) {
            assertTrue(row.has(hidden) && row.path(hidden).isNull(), hidden);
        }

        JsonNode legacy = json(doGet("/api/doctors/profile/list/active", patient));
        assertTrue(legacy.isArray());
        assertEquals(List.of("DOC-00106", "DOC-00001", "DOC-00002"), ids(legacy, "doctorSystemId"));

        assertEquals(List.of("DOC-00106"), ids(json(getWithParams("/api/doctors/profile/directory", patient,
                "query", "cardio")).path("content"), "doctorSystemId"));
        assertEquals(List.of("DOC-00106"), ids(json(getWithParams("/api/doctors/profile/directory", patient,
                "query", "lerato fa")).path("content"), "doctorSystemId"));
        assertEquals(List.of("DOC-00001", "DOC-00002"), ids(json(getWithParams("/api/doctors/profile/directory", patient,
                "specialization", "general")).path("content"), "doctorSystemId"));
        JsonNode paged = json(getWithParams("/api/doctors/profile/directory", patient, "size", "1", "page", "2"));
        assertEquals(List.of("DOC-00002"), ids(paged.path("content"), "doctorSystemId"));
        assertEquals(3, paged.path("totalPages").asInt());
        MvcResult tooLong = getWithParams("/api/doctors/profile/directory", patient, "specialization", "s".repeat(151));
        assertEquals(400, tooLong.getResponse().getStatus());
        assertEquals("specialization: Specialization must be at most 150 characters", message(tooLong));

        // Public profile: listed doctor in full public view, others by name only.
        JsonNode listed = json(doGet("/api/doctors/profile/public/DOC-00106", patient));
        assertTrue(listed.path("isActive").asBoolean());
        assertEquals("Paediatric Cardiology", listed.path("specialization").asText());
        for (String id : List.of("DOC-00101", "DOC-00102", "DOC-00103", "DOC-00104", "DOC-00105")) {
            MvcResult result = doGet("/api/doctors/profile/public/" + id, patient);
            assertEquals(200, result.getResponse().getStatus(), id);
            JsonNode nameOnly = json(result);
            assertFalse(nameOnly.path("isActive").asBoolean(), id);
            assertFalse(nameOnly.path("firstName").asText().isBlank(), id);
            assertTrue(nameOnly.path("specialization").isNull(), id);
            assertTrue(nameOnly.path("userId").isNull(), id);
        }
        assertEquals(404, doGet("/api/doctors/profile/public/DOC-99999", patient).getResponse().getStatus());
    }

    // ==================== PROFILE UPDATE ====================

    @Test
    void selfUpdateCannotChangeEmailLicenceOrActivation() throws Exception {
        jdbc.update("UPDATE doctors SET license_no = 'MDPCZ-1' WHERE system_id = ?", doctor.getSystemId());

        MvcResult licence = doPut("/api/doctors/profile", doctor, Map.of("licenseNo", "MDPCZ-2", "bio", "Changed"));
        assertEquals(400, licence.getResponse().getStatus());
        assertEquals("License number changes must be made by an administrator", message(licence));
        MvcResult email = doPut("/api/doctors/profile", doctor, Map.of("email", "amara@example.com"));
        assertEquals(400, email.getResponse().getStatus());
        assertEquals("Email cannot be changed here. Use Change email in your security settings", message(email));
        assertTrue(jdbc.queryForObject("SELECT bio IS NULL FROM doctors WHERE system_id = ?", Boolean.class,
                doctor.getSystemId()), "a refused request changes nothing");

        Map<String, Object> body = new HashMap<>();
        body.put("licenseNo", "MDPCZ-1");
        body.put("isActive", false);
        body.put("email", " ");
        body.put("phone", "+263 77 100 0222");
        body.put("specialization", "  Cardiology ");
        body.put("bio", "Consultant cardiologist.");
        body.put("firstName", "");
        MvcResult updated = doPut("/api/doctors/profile", doctor, body);
        assertEquals(200, updated.getResponse().getStatus(), updated.getResponse().getContentAsString());
        JsonNode profile = json(updated);
        assertEquals("MDPCZ-1", profile.path("licenseNo").asText());
        assertTrue(profile.path("isActive").asBoolean());
        assertEquals("Cardiology", profile.path("specialization").asText());
        assertEquals("+263 77 100 0222", profile.path("phone").asText());
        assertEquals("DOCTOR", profile.path("firstName").asText());
        assertEquals("amara@example.com", profile.path("email").asText());
        assertEquals("APPROVED", profile.path("verificationStatus").asText());
        assertEquals(1, count("SELECT COUNT(*) FROM audit_log WHERE action = 'PROFILE_UPDATE' "
                + "AND resource_type = 'DOCTOR_PROFILE' AND target_system_id = ?", doctor.getSystemId()));

        // Blank bio clears it; validation and duplicate phone.
        assertTrue(json(doPut("/api/doctors/profile", doctor, Map.of("bio", "  "))).path("bio").isNull());
        MvcResult longBio = doPut("/api/doctors/profile", doctor, Map.of("bio", "b".repeat(2001)));
        assertEquals(400, longBio.getResponse().getStatus());
        assertEquals("bio: Bio must be at most 2000 characters", message(longBio));
        MvcResult badPhone = doPut("/api/doctors/profile", doctor, Map.of("phone", "12ab"));
        assertEquals("phone: Enter a valid phone number", message(badPhone));
        MvcResult takenPhone = doPut("/api/doctors/profile", doctor, Map.of("phone", otherDoctor.getPhone()));
        assertEquals(409, takenPhone.getResponse().getStatus());
        assertEquals("Phone number already registered", message(takenPhone));

        // Only an administrator changes the licence, through the by-id route.
        MvcResult doctorById = doPut("/api/doctors/profile/" + doctor.getSystemId(), doctor, Map.of("licenseNo", "MDPCZ-9"));
        assertEquals(403, doctorById.getResponse().getStatus());
        assertEquals("Admin role required", message(doctorById));
        JsonNode byAdmin = json(doPut("/api/doctors/profile/" + doctor.getSystemId(), admin, Map.of("licenseNo", "MDPCZ-9")));
        assertEquals("MDPCZ-9", byAdmin.path("licenseNo").asText());
        jdbc.update("UPDATE doctors SET license_no = 'TAKEN-1' WHERE system_id = ?", otherDoctor.getSystemId());
        MvcResult duplicate = doPut("/api/doctors/profile/" + doctor.getSystemId(), admin, Map.of("licenseNo", "taken-1"));
        assertEquals(409, duplicate.getResponse().getStatus());
        assertEquals("License number is already registered to another doctor", message(duplicate));
    }

    @Test
    void profileReadsAndCounters() throws Exception {
        Appointment completed = appointment(patient, doctor, clinicNow().minusDays(1), "COMPLETED");
        appointment(patient, doctor, clinicNow().plusDays(1), "SCHEDULED");
        consultation(patient, doctor, completed);

        JsonNode own = json(doGet("/api/doctors/profile", doctor));
        assertEquals(2, own.path("totalAppointments").asInt());
        assertEquals(1, own.path("totalConsultations").asInt());
        assertEquals(1, own.path("completedConsultations").asInt());
        assertEquals(1, own.path("totalPatients").asInt());
        assertEquals(0, own.path("totalPrescriptions").asInt());
        assertEquals("DOCTOR", own.path("role").asText());

        assertEquals(403, doGet("/api/doctors/profile", admin).getResponse().getStatus());
        MvcResult otherDoctorsProfile = doGet("/api/doctors/profile/" + doctor.getSystemId(), otherDoctor);
        assertEquals(403, otherDoctorsProfile.getResponse().getStatus());
        assertEquals("You can only access your own doctor data", message(otherDoctorsProfile));
        assertEquals(200, doGet("/api/doctors/profile/" + doctor.getSystemId(), admin).getResponse().getStatus());
        MvcResult adminUnknown = doGet("/api/doctors/profile/DOC-99999", admin);
        assertEquals(404, adminUnknown.getResponse().getStatus());
        assertEquals("Doctor not found", message(adminUnknown));
        assertEquals(403, doGet("/api/doctors/profile", patient).getResponse().getStatus());
    }

    private Map<String, Object> notificationOf(User recipient) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT type, category, priority, title, message, action_url, "
                + "dedupe_key FROM notifications WHERE system_id = ?", recipient.getSystemId());
        assertEquals(1, rows.size(), "notifications of " + recipient.getSystemId());
        return rows.get(0);
    }
}
