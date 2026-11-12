package com.carenexus.api.releasegates;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.core.model.Doctor;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.carenexus.api.referrals.ReferralFixtures.BASE;
import static com.carenexus.api.referrals.ReferralFixtures.createBody;
import static com.carenexus.api.referrals.ReferralFixtures.externalSpecialist;
import static com.carenexus.api.referrals.ReferralFixtures.note;
import static com.carenexus.api.referrals.ReferralFixtures.reason;
import static com.carenexus.api.referrals.ReferralFixtures.url;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Release gate of the referrals module (be-referrals): role matrix, workflow and illegal transitions, specialist
 * record access after acceptance, caregiver permission, paging, audit, notifications once per recipient, and the
 * path of a referral without a platform specialist.
 *
 * Care circle: patient PAT-00001 with primary doctor DOC-00001 (the referring doctor), caregiver CGV-00001;
 * DOC-00002 is unrelated to the patient; DOC-00003 is an approved cardiologist with no link to the patient.
 */
class ReferralReleaseGateTest extends CommittedIntegrationTest {

    private static final String NOT_AUTHORIZED = "Your role is not authorized to perform this action";

    private User specialist;

    @BeforeEach
    void createSpecialist() {
        specialist = createAndSaveUser(8, "DOC-00003", "cardio@example.com", "DOCTOR");
        Doctor profile = doctorRepository.findBySystemId(specialist.getSystemId()).orElseThrow();
        profile.setSpecialization("Cardiology");
        doctorRepository.saveAndFlush(profile);
    }

    // ==================== CREATE ====================

    @Test
    void treatingDoctorCreatesAReferralAndOthersCannot() throws Exception {
        MvcResult created = doPost(BASE, doctor, createBody(patient.getSystemId(), specialist.getSystemId(), "urgent"));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = json(created);
        assertThat(body.path("status").asText()).isEqualTo("PENDING");
        assertThat(body.path("priority").asText()).isEqualTo("URGENT");
        assertThat(body.path("patientName").asText()).isEqualTo("PATIENT User1");
        assertThat(body.path("referringDoctorSystemId").asText()).isEqualTo(doctor.getSystemId());
        assertThat(body.path("referringDoctorName").asText()).isEqualTo("Dr. DOCTOR User2");
        assertThat(body.path("specialistType").asText()).isEqualTo("PLATFORM");
        assertThat(body.path("specialistName").asText()).isEqualTo("Dr. DOCTOR User8");
        assertThat(body.path("specialistSpecialization").asText()).isEqualTo("Cardiology");
        assertThat(body.has("externalSpecialistName")).isTrue();
        assertThat(body.path("externalSpecialistName").isNull()).isTrue();
        assertThat(body.path("outcomeNote").isNull()).isTrue();
        assertThat(body.path("createdAt").asText()).isEqualTo(body.path("updatedAt").asText());
        assertThat(body.path("referralDate").asText()).hasSize(10);
        assertThat(texts(body.path("allowedActions"))).containsExactly("CANCEL", "ASSIGN_SPECIALIST");
        assertThat(body.path("outcomeNoteRequired").asBoolean()).isFalse();
        int id = body.path("referralId").asInt();
        assertThat(auditRows("CREATE", id)).isEqualTo(1);

        // Unknown properties never change the author
        Map<String, Object> withDoctor = createBody(patient.getSystemId(), null, null);
        withDoctor.put("referringDoctorSystemId", otherDoctor.getSystemId());
        assertThat(json(doPost(BASE, doctor, withDoctor)).path("referringDoctorSystemId").asText())
                .isEqualTo(doctor.getSystemId());

        for (User nonDoctor : List.of(patient, caregiver, admin)) {
            MvcResult denied = doPost(BASE, nonDoctor, createBody(patient.getSystemId(), null, null));
            assertThat(denied.getResponse().getStatus()).as(nonDoctor.getRole()).isEqualTo(403);
            assertThat(message(denied)).isEqualTo(NOT_AUTHORIZED);
        }
        MvcResult unrelated = doPost(BASE, otherDoctor, createBody(patient.getSystemId(), null, null));
        assertThat(unrelated.getResponse().getStatus()).isEqualTo(403);
        assertThat(message(unrelated)).isEqualTo("You are not part of this patient's care team");

        MvcResult unknownPatient = doPost(BASE, doctor, createBody("PAT-09999", null, null));
        assertThat(unknownPatient.getResponse().getStatus()).isEqualTo(404);
        assertThat(message(unknownPatient)).isEqualTo("Patient not found");
        assertThat(referralCount()).isEqualTo(2);
    }

    @Test
    void createValidatesTheBodyAndTheSpecialist() throws Exception {
        Map<String, Object> noReason = createBody(patient.getSystemId(), null, null);
        noReason.put("reason", "   ");
        MvcResult invalid = doPost(BASE, doctor, noReason);
        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(invalid).path("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(message(invalid)).isEqualTo("reason: is required");

        Map<String, Object> emptySpecialist = createBody(patient.getSystemId(), "", null);
        assertThat(message(doPost(BASE, doctor, emptySpecialist))).isEqualTo("specialistSystemId: must be a valid system id");

        assertBadRequest(doPost(BASE, doctor, createBody(patient.getSystemId(), null, "SOON")),
                "priority: must be one of ROUTINE, URGENT, EMERGENCY");

        Map<String, Object> both = createBody(patient.getSystemId(), specialist.getSystemId(), null);
        both.put("externalSpecialistName", "Dr. Peter Moyo");
        assertBadRequest(doPost(BASE, doctor, both), "Choose a platform specialist or an external specialist, not both");

        Map<String, Object> orgOnly = createBody(patient.getSystemId(), null, null);
        orgOnly.put("externalSpecialistOrganisation", "Harare Heart Centre");
        assertBadRequest(doPost(BASE, doctor, orgOnly),
                "externalSpecialistName: is required when an organisation or contact is given");

        assertBadRequest(doPost(BASE, doctor, createBody(patient.getSystemId(), doctor.getSystemId(), null)),
                "The specialist must be a different doctor from the referring doctor");

        MvcResult missing = doPost(BASE, doctor, createBody(patient.getSystemId(), "DOC-09999", null));
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(message(missing)).isEqualTo("Specialist not found");
        MvcResult notDoctor = doPost(BASE, doctor, createBody(patient.getSystemId(), "PAT-00002", null));
        assertThat(notDoctor.getResponse().getStatus()).isEqualTo(404);

        jdbc.update("UPDATE doctors SET verification_status = 'PENDING' WHERE system_id = ?", specialist.getSystemId());
        assertBadRequest(doPost(BASE, doctor, createBody(patient.getSystemId(), specialist.getSystemId(), null)),
                "This specialist is not available for referrals");
        assertThat(referralCount()).isZero();
    }

    @Test
    void idempotentCreateIsStoredOnceAndNotifiesOnce() throws Exception {
        grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));
        grantAccess(otherCaregiver, patient, a -> a.setCanViewAppointments(true));
        Map<String, String> key = Map.of("Idempotency-Key", "referral-key-0001");
        Map<String, Object> body = createBody(patient.getSystemId(), specialist.getSystemId(), "EMERGENCY");

        MvcResult first = doPostWithHeaders(BASE, doctor, body, key);
        MvcResult replay = doPostWithHeaders(BASE, doctor, body, key);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        int id = json(first).path("referralId").asInt();
        assertThat(json(replay).path("referralId").asInt()).isEqualTo(id);
        assertThat(referralCount()).isEqualTo(1);

        String created = "REFERRAL_CREATED:" + id;
        assertThat(notifications(patient, created)).isEqualTo(1);
        assertThat(notifications(caregiver, created)).isEqualTo(1);
        assertThat(notifications(otherCaregiver, created)).isZero();
        assertThat(notifications(doctor, created)).isZero();
        String assigned = "REFERRAL_ASSIGNED:" + id + ":" + specialist.getSystemId();
        assertThat(notifications(specialist, assigned)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT priority FROM notifications WHERE system_id = ? AND dedupe_key = ?",
                String.class, patient.getSystemId(), created)).isEqualTo("HIGH");
        assertThat(jdbc.queryForObject("SELECT title FROM notifications WHERE system_id = ? AND dedupe_key = ?",
                String.class, specialist.getSystemId(), assigned)).isEqualTo("Emergency referral received");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type = 'REFERRAL' "
                + "AND category = 'REFERRAL' AND target = 'REFERRALS' AND resource_type = 'REFERRAL' "
                + "AND resource_id = ?", Integer.class, id)).isEqualTo(3);
    }

    // ==================== READ ====================

    @Test
    void readAccessMatrix() throws Exception {
        int id = create(specialist.getSystemId());

        assertThat(texts(json(doGet(url(id), doctor)).path("allowedActions"))).containsExactly("CANCEL", "ASSIGN_SPECIALIST");
        assertThat(texts(json(doGet(url(id), specialist)).path("allowedActions"))).containsExactly("ACCEPT", "DECLINE");
        assertThat(texts(json(doGet(url(id), patient)).path("allowedActions"))).isEmpty();
        JsonNode asAdmin = json(doGet(url(id), admin));
        assertThat(texts(asAdmin.path("allowedActions"))).containsExactly("ACCEPT", "DECLINE", "ASSIGN_SPECIALIST");
        assertThat(asAdmin.path("outcomeNoteRequired").asBoolean()).isTrue();

        assertForbidden(doGet(url(id), otherPatient), "Patients can only access their own data");
        assertForbidden(doGet(url(id), otherDoctor), "You are not part of this patient's care team");
        assertForbidden(doGet(url(id), caregiver), "You don't have active proxy access to this patient");
        grantAccess(caregiver, patient, a -> a.setCanViewAppointments(true));
        assertForbidden(doGet(url(id), caregiver), "Your proxy access for this patient does not allow viewing referrals");
        grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));
        MvcResult allowed = doGet(url(id), caregiver);
        assertThat(allowed.getResponse().getStatus()).isEqualTo(200);
        assertThat(texts(json(allowed).path("allowedActions"))).isEmpty();
        grantAccess(caregiver, patient, a -> {
            a.setCanViewRecords(true);
            a.setExpiresAt(LocalDateTime.now().minusDays(1));
        });
        assertForbidden(doGet(url(id), caregiver), "You don't have active proxy access to this patient");
        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        assertThat(doGet(url(id), caregiver).getResponse().getStatus()).isEqualTo(200);

        assertThat(auditRows("READ", id)).isGreaterThanOrEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'ACCESS_DENIED' "
                + "AND resource_type = 'REFERRAL' AND resource_id = ?", Integer.class, id)).isGreaterThanOrEqualTo(2);

        MvcResult missing = doGet(url(99999), doctor);
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(message(missing)).isEqualTo("Referral not found");
        MvcResult badId = doGet(BASE + "/abc", doctor);
        assertThat(badId.getResponse().getStatus()).isEqualTo(400);
        assertThat(message(badId)).isEqualTo("Invalid value for parameter 'id'");
    }

    // ==================== WORKFLOW ====================

    @Test
    void specialistGainsRecordAccessAfterAccepting() throws Exception {
        int id = create(specialist.getSystemId());
        String patientList = BASE + "/patient/" + patient.getSystemId();
        assertForbidden(doGet(patientList, specialist), "You are not part of this patient's care team");
        assertThat(doGet("/api/medical-records/patient/" + patient.getSystemId(), specialist).getResponse().getStatus())
                .isEqualTo(403);

        assertForbidden(doPatch(url(id, "accept"), doctor, null),
                "Only the specialist named on this referral can respond to it");
        assertForbidden(doPatch(url(id, "accept"), patient, null), NOT_AUTHORIZED);

        MvcResult accepted = doPatch(url(id, "accept"), specialist, null);
        assertThat(accepted.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(accepted);
        assertThat(body.path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(body.path("respondedAt").isNull()).isFalse();
        assertThat(body.path("outcomeRecordedBySystemId").isNull()).isTrue();
        assertThat(texts(body.path("allowedActions"))).containsExactly("COMPLETE");
        assertThat(specialistCareTeamRows()).isEqualTo(1);

        assertThat(doGet(patientList, specialist).getResponse().getStatus()).isEqualTo(200);
        assertThat(doGet("/api/medical-records/patient/" + patient.getSystemId(), specialist).getResponse().getStatus())
                .isEqualTo(200);

        // Double click: no-op, nothing written again
        String updatedAt = body.path("updatedAt").asText();
        MvcResult again = doPatch(url(id, "accept"), specialist, note("second click"));
        assertThat(again.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(again).path("updatedAt").asText()).isEqualTo(updatedAt);
        assertThat(json(again).path("outcomeNote").isNull()).isTrue();
        assertThat(specialistCareTeamRows()).isEqualTo(1);
        assertThat(auditRows("UPDATE", id)).isEqualTo(1);
        assertThat(notifications(doctor, "REFERRAL_ACCEPTED:" + id)).isEqualTo(1);
        assertThat(notifications(patient, "REFERRAL_ACCEPTED:" + id)).isEqualTo(1);
        assertThat(notifications(specialist, "REFERRAL_ACCEPTED:" + id)).isZero();

        MvcResult completed = doPatch(url(id, "complete"), specialist, note("Stress test normal; back to GP."));
        assertThat(json(completed).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(json(completed).path("outcomeNote").asText()).isEqualTo("Stress test normal; back to GP.");
        assertThat(json(completed).path("completedAt").isNull()).isFalse();
        assertThat(specialistCareTeamRows()).isEqualTo(1);
        assertThat(notifications(patient, "REFERRAL_COMPLETED:" + id)).isEqualTo(1);

        JsonNode incoming = json(doGet(BASE + "/doctor/incoming", specialist));
        assertThat(incoming.path("totalElements").asInt()).isEqualTo(1);
        assertThat(incoming.path("content").get(0).path("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void acceptingWhenTheSpecialistIsAlreadyOnTheCareTeamAddsNoSecondRow() throws Exception {
        int id = create(specialist.getSystemId());
        jdbc.update("INSERT INTO care_team (patient_system_id, provider_system_id, role, joined_date, is_primary, "
                + "is_active, assigned_at, created_at) VALUES (?, ?, 'DOCTOR', CURRENT_DATE, FALSE, TRUE, now(), now())",
                patient.getSystemId(), specialist.getSystemId());
        assertThat(doPatch(url(id, "accept"), specialist, null).getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM care_team WHERE patient_system_id = ? "
                + "AND provider_system_id = ? AND is_active", Integer.class,
                patient.getSystemId(), specialist.getSystemId())).isEqualTo(1);
    }

    @Test
    void illegalTransitionsAreConflicts() throws Exception {
        int id = create(specialist.getSystemId());
        assertConflict(doPatch(url(id, "complete"), specialist, null), "This referral is pending and cannot be completed");

        MvcResult declineInvalid = doPatch(url(id, "decline"), specialist, reason(""));
        assertThat(declineInvalid.getResponse().getStatus()).isEqualTo(400);
        assertThat(message(declineInvalid)).isEqualTo("reason: is required");

        MvcResult declined = doPatch(url(id, "decline"), specialist, reason("No capacity before January"));
        assertThat(json(declined).path("status").asText()).isEqualTo("DECLINED");
        assertThat(json(declined).path("declineReason").asText()).isEqualTo("No capacity before January");
        assertThat(json(declined).path("outcomeNote").isNull()).isTrue();
        assertThat(notifications(doctor, "REFERRAL_DECLINED:" + id)).isEqualTo(1);

        assertConflict(doPatch(url(id, "accept"), specialist, null), "This referral is declined and cannot be accepted");
        assertConflict(doPatch(url(id, "complete"), specialist, null), "This referral is declined and cannot be completed");
        assertConflict(doPatch(url(id, "cancel"), doctor, null), "This referral is declined and cannot be cancelled");
        assertConflict(doPatch(url(id, "specialist"), doctor, Map.of("specialistSystemId", otherDoctor.getSystemId())),
                "The specialist can only be changed while the referral is pending");
        MvcResult declinedAgain = doPatch(url(id, "decline"), specialist, reason("Another reason"));
        assertThat(declinedAgain.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(declinedAgain).path("declineReason").asText()).isEqualTo("No capacity before January");
        assertThat(specialistCareTeamRows()).isZero();

        int toCancel = create(specialist.getSystemId());
        assertForbidden(doPatch(url(toCancel, "cancel"), specialist, null), "Only the referring doctor can cancel this referral");
        assertForbidden(doPatch(url(toCancel, "cancel"), admin, null), NOT_AUTHORIZED);
        assertForbidden(doPatch(url(toCancel, "cancel"), patient, null), NOT_AUTHORIZED);
        MvcResult cancelled = doPatch(url(toCancel, "cancel"), doctor, null);
        assertThat(json(cancelled).path("status").asText()).isEqualTo("CANCELLED");
        assertThat(doPatch(url(toCancel, "cancel"), doctor, null).getResponse().getStatus()).isEqualTo(200);
        assertConflict(doPatch(url(toCancel, "accept"), specialist, null), "This referral is cancelled and cannot be accepted");
        assertThat(notifications(patient, "REFERRAL_CANCELLED:" + toCancel)).isEqualTo(1);
        assertThat(notifications(specialist, "REFERRAL_CANCELLED:" + toCancel)).isEqualTo(1);
        assertThat(notifications(doctor, "REFERRAL_CANCELLED:" + toCancel)).isZero();

        MvcResult missing = doPatch(url(99999, "accept"), specialist, null);
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void adminRecordsAnOutcomeOnTheSpecialistsBehalf() throws Exception {
        int id = create(specialist.getSystemId());
        assertBadRequest(doPatch(url(id, "accept"), admin, null),
                "A note is required when you record the outcome on the specialist's behalf");
        MvcResult accepted = doPatch(url(id, "accept"), admin, note("Accepted by phone with the specialist"));
        JsonNode body = json(accepted);
        assertThat(body.path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(body.path("outcomeRecordedBySystemId").asText()).isEqualTo(admin.getSystemId());
        assertThat(body.path("outcomeRecordedByName").asText()).isEqualTo("ADMIN User7");
        assertThat(specialistCareTeamRows()).isEqualTo(1);
        assertThat(notifications(doctor, "REFERRAL_ACCEPTED:" + id)).isEqualTo(1);
        assertThat(notifications(patient, "REFERRAL_ACCEPTED:" + id)).isEqualTo(1);
    }

    // ==================== NO PLATFORM SPECIALIST ====================

    @Test
    void referralWithoutAPlatformSpecialistIsDecidedByTheReferringDoctor() throws Exception {
        int id = create(null);
        JsonNode created = json(doGet(url(id), doctor));
        assertThat(created.path("specialistType").asText()).isEqualTo("UNASSIGNED");
        assertThat(texts(created.path("allowedActions"))).containsExactly("ACCEPT", "DECLINE", "CANCEL", "ASSIGN_SPECIALIST");
        assertThat(created.path("outcomeNoteRequired").asBoolean()).isTrue();

        assertBadRequest(doPatch(url(id, "specialist"), doctor, Map.of()),
                "Choose a platform specialist or enter an external specialist's name");
        Map<String, Object> both = externalSpecialist("Dr. Peter Moyo", null, null);
        both.put("specialistSystemId", specialist.getSystemId());
        assertBadRequest(doPatch(url(id, "specialist"), doctor, both),
                "Choose a platform specialist or an external specialist, not both");
        assertForbidden(doPatch(url(id, "specialist"), otherDoctor, externalSpecialist("Dr. Peter Moyo", null, null)),
                "Only the referring doctor can change the specialist");

        MvcResult assigned = doPatch(url(id, "specialist"), doctor,
                externalSpecialist(" Dr. Peter Moyo ", "Harare Heart Centre", "+263 242 700 100"));
        JsonNode external = json(assigned);
        assertThat(external.path("specialistType").asText()).isEqualTo("EXTERNAL");
        assertThat(external.path("externalSpecialistName").asText()).isEqualTo("Dr. Peter Moyo");
        assertThat(external.path("externalSpecialistOrganisation").asText()).isEqualTo("Harare Heart Centre");
        assertThat(external.path("specialistName").isNull()).isTrue();
        assertThat(external.path("status").asText()).isEqualTo("PENDING");

        assertForbidden(doPatch(url(id, "accept"), otherDoctor, note("x")),
                "Only the referring doctor can record the outcome of this referral");
        assertForbidden(doPatch(url(id, "decline"), specialist, reason("x")),
                "Only the referring doctor can record the outcome of this referral");
        assertBadRequest(doPatch(url(id, "accept"), doctor, note("  ")),
                "A note is required when you record the outcome on the specialist's behalf");

        MvcResult accepted = doPatch(url(id, "accept"), doctor, note("Seen by Dr. Moyo on Monday"));
        JsonNode body = json(accepted);
        assertThat(body.path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(body.path("outcomeNote").asText()).isEqualTo("Seen by Dr. Moyo on Monday");
        assertThat(body.path("outcomeRecordedBySystemId").asText()).isEqualTo(doctor.getSystemId());
        assertThat(body.path("outcomeRecordedByName").asText()).isEqualTo("Dr. DOCTOR User2");
        assertThat(specialistCareTeamRows()).isZero();
        assertThat(notifications(patient, "REFERRAL_ACCEPTED:" + id)).isEqualTo(1);
        assertThat(notifications(doctor, "REFERRAL_ACCEPTED:" + id)).isZero();

        assertBadRequest(doPatch(url(id, "complete"), doctor, null),
                "A note is required when you record the outcome on the specialist's behalf");
        MvcResult completed = doPatch(url(id, "complete"), doctor, note("Report received"));
        assertThat(json(completed).path("status").asText()).isEqualTo("COMPLETED");
        assertThat(json(completed).path("outcomeNote").asText()).isEqualTo("Report received");
    }

    @Test
    void platformSpecialistAssignedLaterIsNotifiedAndLosesAccessWhenReplaced() throws Exception {
        int id = create(null);
        MvcResult assigned = doPatch(url(id, "specialist"), doctor, Map.of("specialistSystemId", specialist.getSystemId()));
        assertThat(json(assigned).path("specialistType").asText()).isEqualTo("PLATFORM");
        String dedupe = "REFERRAL_ASSIGNED:" + id + ":" + specialist.getSystemId();
        assertThat(notifications(specialist, dedupe)).isEqualTo(1);
        assertThat(doGet(url(id), specialist).getResponse().getStatus()).isEqualTo(200);
        assertThat(json(doGet(BASE + "/doctor/incoming", specialist)).path("totalElements").asInt()).isEqualTo(1);

        // Same specialist again: no-op
        String updatedAt = json(assigned).path("updatedAt").asText();
        MvcResult same = doPatch(url(id, "specialist"), doctor, Map.of("specialistSystemId", specialist.getSystemId()));
        assertThat(json(same).path("updatedAt").asText()).isEqualTo(updatedAt);
        assertThat(notifications(specialist, dedupe)).isEqualTo(1);

        // The referring doctor no longer decides once a platform specialist is named
        assertForbidden(doPatch(url(id, "accept"), doctor, note("x")),
                "Only the specialist named on this referral can respond to it");

        MvcResult replaced = doPatch(url(id, "specialist"), admin, externalSpecialist("Dr. Peter Moyo", null, null));
        assertThat(replaced.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(replaced).path("specialistSystemId").isNull()).isTrue();
        assertForbidden(doGet(url(id), specialist), "You are not part of this patient's care team");
        assertThat(json(doGet(BASE + "/doctor/incoming", specialist)).path("totalElements").asInt()).isZero();

        assertBadRequest(doPatch(url(id, "specialist"), doctor, Map.of("specialistSystemId", doctor.getSystemId())),
                "The specialist must be a different doctor from the referring doctor");
        assertForbidden(doPatch(url(id, "specialist"), patient, Map.of("specialistSystemId", specialist.getSystemId())),
                NOT_AUTHORIZED);
    }

    // ==================== LISTS ====================

    @Test
    void listsArePagedFilteredAndRoleChecked() throws Exception {
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(create(specialist.getSystemId()));
        }
        doPatch(url(ids.get(0), "cancel"), doctor, null);

        JsonNode firstPage = json(doGet(BASE + "/doctor/outgoing?size=2", doctor));
        assertThat(firstPage.path("totalElements").asInt()).isEqualTo(3);
        assertThat(firstPage.path("totalPages").asInt()).isEqualTo(2);
        assertThat(firstPage.path("size").asInt()).isEqualTo(2);
        assertThat(firstPage.path("content").size()).isEqualTo(2);
        assertThat(firstPage.path("content").get(0).path("referralId").asInt()).isEqualTo(ids.get(2));
        JsonNode secondPage = json(doGet(BASE + "/doctor/outgoing?size=2&page=1", doctor));
        assertThat(secondPage.path("content").get(0).path("referralId").asInt()).isEqualTo(ids.get(0));

        assertThat(json(doGet(BASE + "/doctor/outgoing?size=1000&page=-3", doctor)).path("size").asInt()).isEqualTo(100);
        assertThat(json(doGet(BASE + "/doctor/outgoing?size=1000&page=-3", doctor)).path("page").asInt()).isZero();
        assertThat(json(doGet(BASE + "/doctor/outgoing?status=cancelled", doctor)).path("totalElements").asInt()).isEqualTo(1);
        assertThat(json(doGet(BASE + "/doctor/outgoing?status=", doctor)).path("totalElements").asInt()).isEqualTo(3);
        assertThat(json(doGet(BASE + "/doctor/incoming?status=PENDING", specialist)).path("totalElements").asInt()).isEqualTo(2);
        assertThat(json(doGet(BASE + "/doctor/outgoing?patientSystemId=" + patient.getSystemId(), doctor))
                .path("totalElements").asInt()).isEqualTo(3);
        assertThat(json(doGet(BASE + "/doctor/outgoing?patientSystemId=PAT-00002", doctor))
                .path("totalElements").asInt()).isZero();
        assertThat(json(doGet(BASE + "/doctor/outgoing", otherDoctor)).path("totalElements").asInt()).isZero();

        assertBadRequest(doGet(BASE + "/doctor/outgoing?status=OPEN", doctor),
                "status: must be one of PENDING, ACCEPTED, DECLINED, COMPLETED, CANCELLED");
        assertBadRequest(doGet(BASE + "/doctor/incoming?patientSystemId=nope", specialist),
                "patientSystemId: must be a valid system id");
        assertBadRequest(doGet(BASE + "/doctor/outgoing?page=abc", doctor), "Invalid value for parameter 'page'");
        assertForbidden(doGet(BASE + "/doctor/outgoing", admin), NOT_AUTHORIZED);
        assertForbidden(doGet(BASE + "/doctor/incoming", patient), NOT_AUTHORIZED);

        String patientList = BASE + "/patient/" + patient.getSystemId();
        JsonNode own = json(doGet(patientList + "?size=2", patient));
        assertThat(own.path("totalElements").asInt()).isEqualTo(3);
        assertThat(own.path("content").size()).isEqualTo(2);
        assertThat(texts(own.path("content").get(0).path("allowedActions"))).isEmpty();
        assertThat(json(doGet(patientList, admin)).path("totalElements").asInt()).isEqualTo(3);
        assertThat(json(doGet(BASE + "/patient/PAT-09999", admin)).path("totalElements").asInt()).isZero();
        assertForbidden(doGet(patientList, otherPatient), "Patients can only access their own data");
        assertForbidden(doGet(patientList, otherDoctor), "You are not part of this patient's care team");
        assertForbidden(doGet(patientList, caregiver), "You don't have active proxy access to this patient");
        grantAccess(caregiver, patient, a -> a.setCanViewMedications(true));
        assertForbidden(doGet(patientList, caregiver), "Your proxy access for this patient does not allow viewing referrals");
        grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));
        assertThat(json(doGet(patientList, caregiver)).path("totalElements").asInt()).isEqualTo(3);

        MvcResult malformed = doGet(BASE + "/patient/xyz", patient);
        assertThat(malformed.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(malformed).path("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(message(malformed)).isEqualTo("patientSystemId: must be a valid system id");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'READ' AND resource_type = 'REFERRAL' "
                + "AND resource_id IS NULL AND patient_system_id = ?", Integer.class, patient.getSystemId()))
                .isGreaterThanOrEqualTo(5);
    }

    // ==================== HELPERS ====================

    private int create(String specialistSystemId) throws Exception {
        MvcResult result = doPost(BASE, doctor, createBody(patient.getSystemId(), specialistSystemId, null));
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return json(result).path("referralId").asInt();
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private void assertForbidden(MvcResult result, String expectedMessage) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(403);
        assertThat(message(result)).isEqualTo(expectedMessage);
    }

    private void assertBadRequest(MvcResult result, String expectedMessage) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(400);
        assertThat(message(result)).isEqualTo(expectedMessage);
    }

    private void assertConflict(MvcResult result, String expectedMessage) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(409);
        assertThat(message(result)).isEqualTo(expectedMessage);
    }

    private int referralCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM referrals", Integer.class);
    }

    private int specialistCareTeamRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM care_team WHERE patient_system_id = ? AND provider_system_id = ? "
                + "AND role = 'SPECIALIST' AND is_active AND NOT is_primary", Integer.class,
                patient.getSystemId(), specialist.getSystemId());
    }

    private int notifications(User recipient, String dedupeKey) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE system_id = ? AND dedupe_key = ?",
                Integer.class, recipient.getSystemId(), dedupeKey);
    }

    private int auditRows(String action, int referralId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ? AND resource_type = 'REFERRAL' "
                + "AND resource_id = ?", Integer.class, action, referralId);
    }
}
