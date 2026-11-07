package com.carenexus.api.releasegates;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.calls.model.CallJob;
import com.carenexus.api.calls.model.CallParticipant;
import com.carenexus.api.calls.model.CallSession;
import com.carenexus.api.core.model.DependentAccess;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Release gates for managed video calls (CONTRACT.md section 8), with the fake LiveKit adapter. */
class CallReleaseGateTest extends CommittedIntegrationTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private String callUrl(int appointmentId) {
        return "/api/appointments/" + appointmentId + "/call";
    }

    /** Doctor books a VIDEO appointment; returns its id. */
    private int bookVideo(LocalDateTime at) throws Exception {
        MvcResult booked = doPost("/api/appointments", doctor, Map.of(
                "patientSystemId", patient.getSystemId(),
                "doctorSystemId", doctor.getSystemId(),
                "scheduledAt", at.format(ISO),
                "durationMinutes", 30,
                "type", "VIDEO",
                "reasonForVisit", "Follow-up",
                "videoConsultationLink", "https://meet.jit.si/legacy-should-be-ignored"));
        assertEquals(201, booked.getResponse().getStatus(), booked.getResponse().getContentAsString());
        JsonNode body = json(booked);
        assertTrue(body.path("videoConsultationLink").isNull(), "legacy link must not be returned: " + body);
        return body.path("appointmentId").asInt();
    }

    private CallSession session(int appointmentId) {
        return jdbc.query("SELECT call_session_id, status, room_name FROM call_sessions WHERE appointment_id = ?",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    CallSession s = new CallSession();
                    s.setCallSessionId(rs.getLong(1));
                    s.setStatus(rs.getString(2));
                    s.setRoomName(rs.getString(3));
                    return s;
                }, appointmentId);
    }

    private List<Map<String, Object>> jobs(long sessionId, String type) {
        return jdbc.queryForList("SELECT * FROM call_jobs WHERE call_session_id = ? AND job_type = ? ORDER BY call_job_id",
                sessionId, type);
    }

    private String appointmentStatus(int appointmentId) {
        return jdbc.queryForObject("SELECT status FROM appointments WHERE appointment_id = ?", String.class, appointmentId);
    }

    /** Book a call that opens now, prepare the room through the worker and start it as the doctor. */
    private int liveCall() throws Exception {
        int appointmentId = bookVideo(slotInMinutes(10));
        assertEquals(CallSession.PREPARING, session(appointmentId).getStatus());
        callJobService.runOnce();
        assertEquals(CallSession.READY, session(appointmentId).getStatus());
        MvcResult started = doPost(callUrl(appointmentId) + "/start", doctor, null);
        assertEquals(200, started.getResponse().getStatus(), started.getResponse().getContentAsString());
        assertEquals("LIVE", json(started).path("callStatus").asText());
        return appointmentId;
    }

    private long requestAndAdmit(int appointmentId, User who) throws Exception {
        MvcResult requested = doPost(callUrl(appointmentId) + "/join-request", who, Map.of("actingForPatient", false));
        assertEquals(200, requested.getResponse().getStatus(), requested.getResponse().getContentAsString());
        long participantId = json(requested).path("myParticipant").path("participantId").asLong();
        assertEquals("REQUESTED", json(requested).path("myParticipant").path("status").asText());
        // Not admitted yet: no token.
        MvcResult early = doPost(callUrl(appointmentId) + "/token", who, null);
        assertEquals(409, early.getResponse().getStatus());
        assertEquals("You have not been admitted to this call yet", message(early));

        MvcResult admitted = doPost(callUrl(appointmentId) + "/admissions/" + participantId + "/admit", doctor, null);
        assertEquals(200, admitted.getResponse().getStatus(), admitted.getResponse().getContentAsString());
        return participantId;
    }

    @Test
    void videoBookingCreatesCallSessionAndFullJoinFlowIssuesToken() throws Exception {
        grantApprovedAccess(caregiver, patient);
        int appointmentId = bookVideo(slotInMinutes(10));

        CallSession created = session(appointmentId);
        assertEquals(CallSession.PREPARING, created.getStatus());
        assertTrue(created.getRoomName().matches("cn_[0-9a-f]{32}"), "opaque room name");
        assertEquals(1, jobs(created.getCallSessionId(), CallJob.CREATE_ROOM).size());

        JsonNode appointment = json(doGet("/api/appointments/" + appointmentId, patient));
        assertEquals("PREPARING", appointment.path("callStatus").asText(), appointment.toString());
        assertEquals("/dashboard/call/" + appointmentId, appointment.path("callJoinRoute").asText());

        callJobService.runOnce();
        assertEquals(List.of(created.getRoomName()), fakeCallProvider.createdRooms);
        assertEquals(CallSession.READY, session(appointmentId).getStatus());

        assertEquals(200, doPost(callUrl(appointmentId) + "/start", doctor, null).getResponse().getStatus());
        long patientParticipant = requestAndAdmit(appointmentId, patient);
        long caregiverParticipant = requestAndAdmit(appointmentId, caregiver);
        assertNotEquals(patientParticipant, caregiverParticipant);

        for (User who : List.of(doctor, patient, caregiver)) {
            MvcResult token = doPost(callUrl(appointmentId) + "/token", who, null);
            assertEquals(200, token.getResponse().getStatus(), who.getSystemId() + ": " + token.getResponse().getContentAsString());
            assertEquals("no-store", token.getResponse().getHeader("Cache-Control"));
            JsonNode body = json(token);
            assertTrue(body.path("token").asText().startsWith("fake-token-"));
            assertTrue(body.path("identity").asText().matches("p_[0-9a-f]{32}"));
            assertEquals("ws://fake-livekit.test:7880", body.path("serverUrl").asText());
        }
        assertEquals(3, fakeCallProvider.issuedTokens.size());
        // No system ids or emails in what goes to the provider.
        fakeCallProvider.issuedTokens.forEach(t -> {
            assertFalse(t.identity().contains("PAT-") || t.identity().contains("CGV-") || t.identity().contains("DOC-"));
            assertFalse(t.metadataJson() != null && t.metadataJson().contains("@"));
        });
    }

    @Test
    void unrelatedUsersAndGuessedIdsAreDeniedOnCallEndpoints() throws Exception {
        int appointmentId = liveCall();

        for (User outsider : List.of(otherPatient, otherDoctor, otherCaregiver, caregiver)) {
            MvcResult status = doGet(callUrl(appointmentId), outsider);
            assertEquals(403, status.getResponse().getStatus(), outsider.getSystemId());
            assertEquals(403, doPost(callUrl(appointmentId) + "/join-request", outsider, Map.of())
                    .getResponse().getStatus(), outsider.getSystemId());
            assertEquals(403, doPost(callUrl(appointmentId) + "/token", outsider, null).getResponse().getStatus());
            assertEquals(403, doPost(callUrl(appointmentId) + "/end", outsider, null).getResponse().getStatus());
            assertEquals(403, doPost(callUrl(appointmentId) + "/start", outsider, null).getResponse().getStatus());
        }
        MvcResult notDoctor = doPost(callUrl(appointmentId) + "/start", otherDoctor, null);
        assertEquals("Only the appointment's doctor can manage this call", message(notDoctor));
        // The patient cannot manage the call or admit themselves.
        long participantId = json(doPost(callUrl(appointmentId) + "/join-request", patient, Map.of()))
                .path("myParticipant").path("participantId").asLong();
        assertEquals(403, doPost(callUrl(appointmentId) + "/admissions/" + participantId + "/admit", patient, null)
                .getResponse().getStatus());
        // Admin: read only.
        assertEquals(200, doGet(callUrl(appointmentId), admin).getResponse().getStatus());
        assertEquals(403, doPost(callUrl(appointmentId) + "/join-request", admin, Map.of()).getResponse().getStatus());
        assertEquals(403, doPost(callUrl(appointmentId) + "/token", admin, null).getResponse().getStatus());

        MvcResult missing = doGet(callUrl(999999), patient);
        assertEquals(404, missing.getResponse().getStatus());
        assertEquals("Appointment not found", message(missing));
        assertEquals(404, doPost(callUrl(999999) + "/token", patient, null).getResponse().getStatus());
        assertEquals(404, doPost(callUrl(appointmentId) + "/admissions/999999/admit", doctor, null)
                .getResponse().getStatus());
    }

    @Test
    void revokedCaregiverIsRemovedFromLiveCallAndCleanupJobIsEnqueued() throws Exception {
        DependentAccess access = grantApprovedAccess(caregiver, patient);
        int appointmentId = liveCall();
        long participantId = requestAndAdmit(appointmentId, caregiver);
        assertEquals(200, doPost(callUrl(appointmentId) + "/token", caregiver, null).getResponse().getStatus());

        assertEquals(204, doDelete("/api/patients/me/caregiver-access/" + access.getAccessId(), patient)
                .getResponse().getStatus());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, removal_reason, removal_pending, revoked_at, provider_identity FROM call_participants WHERE call_participant_id = ?",
                participantId);
        assertEquals(CallParticipant.REMOVED, row.get("status"));
        assertEquals(CallParticipant.REASON_ACCESS_REVOKED, row.get("removal_reason"));
        assertEquals(Boolean.TRUE, row.get("removal_pending"));
        assertTrue(row.get("revoked_at") != null);

        long sessionId = session(appointmentId).getCallSessionId();
        List<Map<String, Object>> removals = jobs(sessionId, CallJob.REMOVE_PARTICIPANT);
        assertEquals(1, removals.size());
        assertEquals(CallJob.PENDING, removals.get(0).get("status"));

        // No new token, no re-join.
        MvcResult token = doPost(callUrl(appointmentId) + "/token", caregiver, null);
        assertEquals(409, token.getResponse().getStatus());
        assertEquals("Your access to this call has been removed", message(token));
        assertEquals(403, doPost(callUrl(appointmentId) + "/join-request", caregiver, Map.of()).getResponse().getStatus());

        // Doctor sees the pending removal until the provider confirms.
        assertTrue(json(doGet(callUrl(appointmentId), doctor)).path("removalPending").asBoolean());
        callJobService.runOnce();
        assertEquals(List.of(row.get("provider_identity")), fakeCallProvider.removedIdentities);
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
                "SELECT removal_pending FROM call_participants WHERE call_participant_id = ?", Boolean.class, participantId));
        assertFalse(json(doGet(callUrl(appointmentId), doctor)).path("removalPending").asBoolean());
    }

    @Test
    void expiredCaregiverGrantCannotJoinOrGetToken() throws Exception {
        DependentAccess access = grantApprovedAccess(caregiver, patient);
        int appointmentId = liveCall();
        requestAndAdmit(appointmentId, caregiver);

        access.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        dependentAccessRepository.saveAndFlush(access);

        assertEquals(403, doPost(callUrl(appointmentId) + "/token", caregiver, null).getResponse().getStatus());
        assertEquals(403, doGet(callUrl(appointmentId), caregiver).getResponse().getStatus());
    }

    @Test
    void cancellingTheAppointmentCancelsTheCall() throws Exception {
        grantApprovedAccess(caregiver, patient);
        int appointmentId = liveCall();
        long participantId = requestAndAdmit(appointmentId, caregiver);
        String room = session(appointmentId).getRoomName();

        assertEquals(204, doDelete("/api/appointments/" + appointmentId, doctor).getResponse().getStatus());

        CallSession after = session(appointmentId);
        assertEquals(CallSession.CANCELLED, after.getStatus());
        assertEquals(CallParticipant.REMOVED, jdbc.queryForObject(
                "SELECT status FROM call_participants WHERE call_participant_id = ?", String.class, participantId));
        List<Map<String, Object>> deletes = jobs(after.getCallSessionId(), CallJob.DELETE_ROOM);
        assertEquals(1, deletes.size());
        assertEquals(room, deletes.get(0).get("room_name"));

        JsonNode status = json(doGet(callUrl(appointmentId), doctor));
        assertEquals("CANCELLED", status.path("callStatus").asText());
        assertFalse(status.path("canStart").asBoolean());
        MvcResult start = doPost(callUrl(appointmentId) + "/start", doctor, null);
        assertEquals(409, start.getResponse().getStatus());
        assertEquals(409, doPost(callUrl(appointmentId) + "/token", caregiver, null).getResponse().getStatus());
        JsonNode appointment = json(doGet("/api/appointments/" + appointmentId, doctor));
        assertEquals("CANCELLED", appointment.path("callStatus").asText());
    }

    @Test
    void reschedulingResetsTheCallToANewRoomAndWindow() throws Exception {
        grantApprovedAccess(caregiver, patient);
        int appointmentId = liveCall();
        long participantId = requestAndAdmit(appointmentId, caregiver);
        CallSession before = session(appointmentId);

        LocalDateTime newTime = LocalDateTime.now().plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        MvcResult moved = doPatch("/api/appointments/" + appointmentId + "/reschedule", doctor,
                Map.of("scheduledAt", newTime.format(ISO)));
        assertEquals(200, moved.getResponse().getStatus(), moved.getResponse().getContentAsString());
        assertEquals("PREPARING", json(moved).path("callStatus").asText(), json(moved).toString());

        CallSession after = session(appointmentId);
        assertEquals(CallSession.PREPARING, after.getStatus());
        assertNotEquals(before.getRoomName(), after.getRoomName());
        Map<String, Object> participant = jdbc.queryForMap(
                "SELECT status, removal_reason FROM call_participants WHERE call_participant_id = ?", participantId);
        assertEquals(CallParticipant.REMOVED, participant.get("status"));
        assertEquals(CallParticipant.REASON_RESCHEDULED, participant.get("removal_reason"));
        List<Map<String, Object>> deletes = jobs(after.getCallSessionId(), CallJob.DELETE_ROOM);
        assertEquals(1, deletes.size());
        assertEquals(before.getRoomName(), deletes.get(0).get("room_name"));
        List<Map<String, Object>> creates = jobs(after.getCallSessionId(), CallJob.CREATE_ROOM);
        assertEquals(CallJob.PENDING, creates.get(creates.size() - 1).get("status"));
        assertEquals(after.getRoomName(), creates.get(creates.size() - 1).get("room_name"));

        JsonNode status = json(doGet(callUrl(appointmentId), doctor));
        assertFalse(status.path("joinWindowOpen").asBoolean());
        MvcResult start = doPost(callUrl(appointmentId) + "/start", doctor, null);
        assertEquals(409, start.getResponse().getStatus());
        assertEquals(409, doPost(callUrl(appointmentId) + "/token", caregiver, null).getResponse().getStatus());
        assertEquals("RESCHEDULED", appointmentStatus(appointmentId));
    }

    @Test
    void leavingAndEndingTheCallDoNotCompleteTheAppointment() throws Exception {
        int appointmentId = liveCall();
        requestAndAdmit(appointmentId, patient);
        assertEquals(200, doPost(callUrl(appointmentId) + "/token", patient, null).getResponse().getStatus());

        assertEquals(200, doPost(callUrl(appointmentId) + "/leave", patient, null).getResponse().getStatus());
        assertEquals(200, doPost(callUrl(appointmentId) + "/leave", doctor, null).getResponse().getStatus());
        assertEquals("LIVE", json(doGet(callUrl(appointmentId), doctor)).path("callStatus").asText(),
                "leaving never ends the call");
        assertEquals("SCHEDULED", appointmentStatus(appointmentId));

        // Doctor rejoins, then ends.
        assertEquals(200, doPost(callUrl(appointmentId) + "/start", doctor, null).getResponse().getStatus());
        MvcResult ended = doPost(callUrl(appointmentId) + "/end", doctor, null);
        assertEquals(200, ended.getResponse().getStatus());
        assertEquals("ENDED", json(ended).path("callStatus").asText());
        assertEquals("SCHEDULED", appointmentStatus(appointmentId));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM consultations WHERE appointment_id = ?",
                Integer.class, appointmentId));
        assertEquals(1, jobs(session(appointmentId).getCallSessionId(), CallJob.DELETE_ROOM).size());
        // Ending again is a no-op.
        assertEquals(200, doPost(callUrl(appointmentId) + "/end", doctor, null).getResponse().getStatus());
    }
}
