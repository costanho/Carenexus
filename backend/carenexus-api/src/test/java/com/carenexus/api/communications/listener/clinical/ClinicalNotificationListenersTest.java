package com.carenexus.api.communications.listener.clinical;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.common.event.AppointmentBookedEvent;
import com.carenexus.api.common.event.AppointmentCancelledEvent;
import com.carenexus.api.common.event.AppointmentRescheduledEvent;
import com.carenexus.api.common.event.AppointmentStatusChangedEvent;
import com.carenexus.api.common.event.ClinicalResultRecordedEvent;
import com.carenexus.api.common.event.ConsultationCompletedEvent;
import com.carenexus.api.common.event.DocumentUploadedEvent;
import com.carenexus.api.common.event.HealthMetricRecordedEvent;
import com.carenexus.api.common.event.MedicationDoseMissedEvent;
import com.carenexus.api.common.event.PrescriptionIssuedEvent;
import com.carenexus.api.common.event.PrescriptionStatusChangedEvent;
import com.carenexus.api.communications.listener.clinical.StoredNotifications.Row;
import com.carenexus.api.releasegates.CommittedIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The clinical notification listeners against PostgreSQL with real commits: recipients per notification (doctor,
 * patient, caregivers with and without the permission, expired and revoked grants, FULL_ACCESS), the actor rule,
 * exact texts, action links, priorities, categories, toggles and dedupe keys (api/be-clinical-notifications.md).
 *
 * The listener methods are called directly inside a committed transaction, as the publishing service would run
 * them, so the other listeners of these events (schedules, invoices, records) do not need real clinical rows.
 */
class ClinicalNotificationListenersTest extends CommittedIntegrationTest {

    private static final LocalDateTime WHEN = LocalDateTime.of(2026, 11, 12, 9, 0);
    private static final String WHEN_TEXT = "12 Nov 2026 at 09:00";

    @Autowired
    private AppointmentNotificationListener appointments;

    @Autowired
    private ClinicalRecordNotificationListener records;

    @Autowired
    private CareMonitoringNotificationListener monitoring;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    private TransactionTemplate tx;
    private StoredNotifications stored;
    /** A third caregiver, for tests that need caregivers with different flags at once. */
    private User thirdCaregiver;

    private String patientName;
    private String doctorName;
    private String pat;
    private String doc;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        stored = new StoredNotifications(jdbc);
        thirdCaregiver = createAndSaveUser(8, "CGV-00003", "third.cg@example.com", "CAREGIVER");
        patientName = patient.getFirstName() + " " + patient.getLastName();
        doctorName = "Dr. " + doctor.getFirstName() + " " + doctor.getLastName();
        pat = patient.getSystemId();
        doc = doctor.getSystemId();
    }

    private void inTransaction(Runnable body) {
        tx.executeWithoutResult(status -> body.run());
    }

    private Row only(List<Row> rows) {
        assertEquals(1, rows.size(), rows.toString());
        return rows.get(0);
    }

    // ==================== APPOINTMENTS (N1 to N4) ====================

    @Test
    void booked_byPatient_notifiesDoctorAndCaregiversWithTheFlag_notThePatient() {
        grantAccess(caregiver, patient, a -> a.setCanViewAppointments(true));
        grantAccess(otherCaregiver, patient, a -> a.setCanViewLabResults(true));
        AppointmentBookedEvent event = new AppointmentBookedEvent(412, pat, doc, pat, "PATIENT", "IN_PERSON", WHEN, 30);
        inTransaction(() -> appointments.onBooked(event));

        Row toDoctor = only(stored.of(doc));
        assertEquals("APPOINTMENT", toDoctor.type());
        assertEquals("APPOINTMENT_CHANGE", toDoctor.category());
        assertEquals("NORMAL", toDoctor.priority());
        assertEquals("APPOINTMENTS", toDoctor.target());
        assertEquals("APPOINTMENT", toDoctor.resourceType());
        assertEquals(412, toDoctor.resourceId());
        assertEquals("Appointment booked", toDoctor.title());
        assertEquals(patientName + " has a new appointment with you on " + WHEN_TEXT, toDoctor.message());
        assertEquals("/dashboard/doctor?section=appointments&id=412", toDoctor.actionUrl());
        assertEquals("APPT_BOOKED:412", toDoctor.dedupeKey());
        assertEquals(pat, toDoctor.patientSystemId());
        assertEquals(pat, toDoctor.actorSystemId());

        Row toCaregiver = only(stored.of(caregiver.getSystemId()));
        assertEquals("An appointment with " + doctorName + " was booked for " + patientName + " on " + WHEN_TEXT,
                toCaregiver.message());
        assertEquals("/dashboard/proxy?section=appointments&patient=PAT-00001&id=412", toCaregiver.actionUrl());

        assertTrue(stored.of(pat).isEmpty(), "the actor is never notified");
        assertTrue(stored.of(otherCaregiver.getSystemId()).isEmpty(), "no appointments flag");
        assertTrue(stored.of(otherDoctor.getSystemId()).isEmpty());
        assertTrue(stored.of(admin.getSystemId()).isEmpty());

        // The same event again stores nothing
        inTransaction(() -> appointments.onBooked(event));
        assertEquals(2, stored.count());
    }

    @Test
    void booked_byDoctor_notifiesThePatientCircle_withPatientWording() {
        AppointmentBookedEvent event = new AppointmentBookedEvent(7, pat, doc, doc, "DOCTOR", "VIDEO", WHEN, 30);
        inTransaction(() -> appointments.onBooked(event));

        Row toPatient = only(stored.of(pat));
        assertEquals("An appointment with " + doctorName + " was booked for you on " + WHEN_TEXT, toPatient.message());
        assertEquals("/dashboard/patient?section=my-appointments&id=7", toPatient.actionUrl());
        assertTrue(stored.of(doc).isEmpty());
    }

    @Test
    void rescheduled_notifiesOncePerMove_andIgnoresAnUnchangedTime() {
        LocalDateTime later = WHEN.plusDays(1).withHour(14).withMinute(30);
        inTransaction(() -> appointments.onRescheduled(
                new AppointmentRescheduledEvent(9, pat, doc, pat, "PATIENT", WHEN, WHEN)));
        assertEquals(0, stored.count(), "same time: no notification");

        AppointmentRescheduledEvent move = new AppointmentRescheduledEvent(9, pat, doc, pat, "PATIENT", WHEN, later);
        inTransaction(() -> appointments.onRescheduled(move));
        inTransaction(() -> appointments.onRescheduled(move));
        Row toDoctor = only(stored.of(doc));
        assertEquals("Appointment rescheduled", toDoctor.title());
        assertEquals("Your appointment with " + patientName + " was moved to 13 Nov 2026 at 14:30", toDoctor.message());
        assertEquals("APPT_RESCHEDULED:9:2026-11-12T09:00:2026-11-13T14:30", toDoctor.dedupeKey());

        // Moved again by the doctor: the patient is told, the doctor is not
        inTransaction(() -> appointments.onRescheduled(
                new AppointmentRescheduledEvent(9, pat, doc, doc, "DOCTOR", later, WHEN)));
        assertEquals("Your appointment with " + doctorName + " was moved to " + WHEN_TEXT, only(stored.of(pat)).message());
        assertEquals(1, stored.of(doc).size());
    }

    @Test
    void cancelled_byUser_neverShowsTheReason_bySystem_notifiesEveryoneAtHighPriorityWithTheReason() {
        grantAccess(caregiver, patient, a -> a.setCanViewAppointments(true));
        inTransaction(() -> appointments.onCancelled(new AppointmentCancelledEvent(
                11, pat, doc, pat, "PATIENT", WHEN, "Private reason about my symptoms", false)));
        Row toDoctor = only(stored.of(doc));
        assertEquals("Your appointment with " + patientName + " on " + WHEN_TEXT + " was cancelled", toDoctor.message());
        assertEquals("NORMAL", toDoctor.priority());
        assertEquals("APPT_CANCELLED:11", toDoctor.dedupeKey());
        assertEquals(patientName + "'s appointment with " + doctorName + " on " + WHEN_TEXT + " was cancelled",
                only(stored.of(caregiver.getSystemId())).message());
        assertTrue(stored.of(pat).isEmpty());
        assertTrue(stored.all().stream().noneMatch(row -> row.message().contains("symptoms")));

        // System cancellation: the event's actor is ignored, everyone hears it, HIGH, with the reason
        inTransaction(() -> appointments.onCancelled(new AppointmentCancelledEvent(
                12, pat, doc, doc, "DOCTOR", WHEN, "The doctor is no longer available", true)));
        List<Row> system = stored.all().stream().filter(row -> row.resourceId() == 12).toList();
        assertEquals(3, system.size(), system.toString());
        system.forEach(row -> {
            assertEquals("HIGH", row.priority());
            assertTrue(row.message().endsWith(" was cancelled. Reason: The doctor is no longer available"), row.message());
            assertNull(row.actorSystemId());
        });
        assertEquals("Your appointment with " + doctorName + " on " + WHEN_TEXT
                        + " was cancelled. Reason: The doctor is no longer available",
                only(stored.of(pat)).message());
    }

    @Test
    void statusChanged_onlyNoShowNotifies() {
        inTransaction(() -> appointments.onStatusChanged(
                new AppointmentStatusChangedEvent(13, pat, doc, doc, "SCHEDULED", "COMPLETED")));
        assertEquals(0, stored.count(), "COMPLETED is covered by the consultation notification");

        inTransaction(() -> appointments.onStatusChanged(
                new AppointmentStatusChangedEvent(13, pat, doc, doc, "SCHEDULED", "NO_SHOW")));
        Row toPatient = only(stored.of(pat));
        assertEquals("Appointment marked as missed", toPatient.title());
        assertEquals("Your appointment with " + doctorName + " was marked as missed", toPatient.message());
        assertEquals("APPT_NO_SHOW:13", toPatient.dedupeKey());
        assertTrue(stored.of(doc).isEmpty(), "the doctor who marked it is the actor");

        inTransaction(() -> appointments.onStatusChanged(
                new AppointmentStatusChangedEvent(14, pat, doc, doc, "NO_SHOW", "NO_SHOW")));
        assertEquals(1, stored.count());
    }

    @Test
    void appointmentChangeToggleOff_suppressesOnlyThatRecipient() {
        grantAccess(caregiver, patient, a -> a.setCanViewAppointments(true));
        stored.setting(caregiver.getSystemId(), "appointment_change_alerts", false);
        inTransaction(() -> appointments.onBooked(
                new AppointmentBookedEvent(15, pat, doc, doc, "DOCTOR", "IN_PERSON", WHEN, 30)));
        assertTrue(stored.of(caregiver.getSystemId()).isEmpty());
        assertEquals(1, stored.of(pat).size());
    }

    // ==================== CONSULTATIONS AND PRESCRIPTIONS (N5 to N8) ====================

    @Test
    void consultationCompleted_notifiesPatientAndCaregiversWithConsultations_neverTheDoctor() {
        grantAccess(caregiver, patient, a -> a.setCanViewConsultations(true));
        grantAccess(otherCaregiver, patient, a -> a.setCanViewAppointments(true));
        ConsultationCompletedEvent event = new ConsultationCompletedEvent(55, 9, pat, doc, true, LocalDate.of(2026, 11, 26));
        inTransaction(() -> records.onConsultationCompleted(event));
        inTransaction(() -> records.onConsultationCompleted(event));

        Row toPatient = only(stored.of(pat));
        assertEquals("APPOINTMENT", toPatient.type());
        assertEquals("CONSULTATION", toPatient.category());
        assertEquals("Consultation summary available", toPatient.title());
        assertEquals(doctorName + " completed your consultation. The summary is available. "
                + "A follow-up visit is recommended by 26 Nov 2026.", toPatient.message());
        assertEquals("/dashboard/patient?section=consultation-history&id=55", toPatient.actionUrl());
        assertEquals("CONSULT_COMPLETED:55", toPatient.dedupeKey());

        Row toCaregiver = only(stored.of(caregiver.getSystemId()));
        assertEquals("/dashboard/proxy?section=documents-results&patient=PAT-00001&id=55&tab=consultations",
                toCaregiver.actionUrl());
        assertTrue(toCaregiver.message().startsWith(doctorName + " completed a consultation with " + patientName));
        assertTrue(stored.of(otherCaregiver.getSystemId()).isEmpty());
        assertTrue(stored.of(doc).isEmpty());
    }

    @Test
    void prescriptionIssued_hasNoMedicationName_andReachesCaregiversWithMedications() {
        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        grantAccess(otherCaregiver, patient, a -> a.setCanViewRecords(true));
        inTransaction(() -> records.onPrescriptionIssued(new PrescriptionIssuedEvent(31, 55, pat, doc, "Warfarin 5mg",
                "ONCE_DAILY", LocalDate.of(2026, 11, 10), null, List.of("08:00"))));

        Row toPatient = only(stored.of(pat));
        assertEquals("MEDICATION", toPatient.type());
        assertEquals("PRESCRIPTION", toPatient.category());
        assertEquals("New prescription", toPatient.title());
        assertEquals(doctorName + " issued a new prescription for you", toPatient.message());
        assertEquals("/dashboard/patient?section=prescriptions&id=31", toPatient.actionUrl());
        Row toCaregiver = only(stored.of(caregiver.getSystemId()));
        assertEquals(doctorName + " issued a new prescription for " + patientName, toCaregiver.message());
        assertEquals("/dashboard/proxy?section=medications&patient=PAT-00001&id=31", toCaregiver.actionUrl());
        assertTrue(stored.of(otherCaregiver.getSystemId()).isEmpty());
        assertTrue(stored.all().stream().noneMatch(row -> row.message().contains("Warfarin")));
    }

    @Test
    void prescriptionStatus_expiredAndCancelledNotifyThePatientOnly() {
        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        inTransaction(() -> records.onPrescriptionStatusChanged(
                new PrescriptionStatusChangedEvent(31, pat, doc, "ACTIVE", "COMPLETED")));
        inTransaction(() -> records.onPrescriptionStatusChanged(
                new PrescriptionStatusChangedEvent(31, pat, doc, "ACTIVE", "ON_HOLD")));
        inTransaction(() -> records.onPrescriptionStatusChanged(
                new PrescriptionStatusChangedEvent(31, pat, doc, "CANCELLED", "CANCELLED")));
        assertEquals(0, stored.count());

        inTransaction(() -> records.onPrescriptionStatusChanged(
                new PrescriptionStatusChangedEvent(32, pat, doc, "ACTIVE", "EXPIRED")));
        inTransaction(() -> records.onPrescriptionStatusChanged(
                new PrescriptionStatusChangedEvent(33, pat, doc, "ON_HOLD", "CANCELLED")));
        List<Row> toPatient = stored.of(pat);
        assertEquals(2, toPatient.size());
        assertEquals("Prescription expired", toPatient.get(0).title());
        assertEquals("One of your prescriptions has expired. Contact your doctor if you need a renewal.",
                toPatient.get(0).message());
        assertEquals("RX_EXPIRED:32", toPatient.get(0).dedupeKey());
        assertNull(toPatient.get(0).actorSystemId());
        assertEquals("Prescription cancelled", toPatient.get(1).title());
        assertEquals(doctorName + " cancelled one of your prescriptions", toPatient.get(1).message());
        assertEquals("RX_CANCELLED:33", toPatient.get(1).dedupeKey());
        assertEquals("/dashboard/patient?section=prescriptions&id=33", toPatient.get(1).actionUrl());
        assertTrue(stored.of(caregiver.getSystemId()).isEmpty());
        assertTrue(stored.of(doc).isEmpty());
    }

    // ==================== RESULTS (N9, N10) ====================

    private ClinicalResultRecordedEvent lab(int id, String status, boolean critical, boolean newlyCritical) {
        return new ClinicalResultRecordedEvent(ClinicalResultRecordedEvent.LAB, id, pat, doc, doc, status, critical,
                newlyCritical, false);
    }

    @Test
    void labResult_completedOnce_toThePatientAndCaregiversWithTheLabFlag_neverADoctor() {
        grantAccess(caregiver, patient, a -> a.setCanViewLabResults(true));
        grantAccess(otherCaregiver, patient, a -> a.setCanViewImaging(true));
        grantAccess(thirdCaregiver, patient, a -> {
            a.setCanViewLabResults(true);
            a.setExpiresAt(LocalDateTime.now().minusDays(1));
        });

        inTransaction(() -> records.onResultRecorded(lab(88, "PENDING", false, false)));
        inTransaction(() -> records.onResultRecorded(lab(88, "REQUIRES_REVIEW", false, false)));
        assertEquals(0, stored.count(), "pending and review-required results tell nobody");

        inTransaction(() -> records.onResultRecorded(lab(88, "COMPLETED", false, false)));
        inTransaction(() -> records.onResultRecorded(lab(88, "COMPLETED", false, false)));
        Row toPatient = only(stored.of(pat));
        assertEquals("LAB_RESULT", toPatient.type());
        assertEquals("LAB_RESULT", toPatient.category());
        assertEquals("NORMAL", toPatient.priority());
        assertEquals("New lab result", toPatient.title());
        assertEquals("A new lab result is available in your records", toPatient.message());
        assertEquals("/dashboard/patient?section=lab-results&id=88", toPatient.actionUrl());
        assertEquals("RESULT_AVAILABLE:LAB:88", toPatient.dedupeKey());
        Row toCaregiver = only(stored.of(caregiver.getSystemId()));
        assertEquals("A lab result for " + patientName + " is available", toCaregiver.message());
        assertEquals("/dashboard/proxy?section=documents-results&patient=PAT-00001&id=88&tab=labs",
                toCaregiver.actionUrl());
        assertTrue(stored.of(otherCaregiver.getSystemId()).isEmpty(), "imaging flag only");
        assertTrue(stored.of(thirdCaregiver.getSystemId()).isEmpty(), "expired grant");
        assertTrue(stored.of(doc).isEmpty());

        // Later it becomes critical: the HIGH notification follows, once
        inTransaction(() -> records.onResultRecorded(lab(88, "CRITICAL", true, true)));
        inTransaction(() -> records.onResultRecorded(lab(88, "CRITICAL", true, false)));
        inTransaction(() -> records.onResultRecorded(lab(88, "CRITICAL", true, true)));
        Row critical = only(stored.titled(pat, "Important lab result"));
        assertEquals("HIGH", critical.priority());
        assertEquals("A lab result needs your attention. Please contact your care team.", critical.message());
        assertEquals("RESULT_CRITICAL:LAB:88", critical.dedupeKey());
        assertEquals("A lab result for " + patientName + " needs attention. Please contact the care team.",
                only(stored.titled(caregiver.getSystemId(), "Important lab result")).message());
        assertTrue(stored.of(doc).isEmpty(), "doctors are reached only through the AI alert");
    }

    @Test
    void criticalResult_givesNoPlainNotification_cancelledGivesNothing_revokedGrantGivesNothing() {
        grantAccess(caregiver, patient, a -> a.setCanViewLabResults(true));
        grantAccess(otherCaregiver, patient, a -> {
            a.setCanViewLabResults(true);
            a.setIsActive(false);
            a.setRevokedAt(LocalDateTime.now().minusHours(1));
        });
        inTransaction(() -> records.onResultRecorded(lab(90, "COMPLETED", true, true)));
        assertEquals(1, stored.of(pat).size());
        assertEquals("Important lab result", only(stored.of(pat)).title());
        assertEquals(1, stored.of(caregiver.getSystemId()).size());
        assertTrue(stored.of(otherCaregiver.getSystemId()).isEmpty(), "revoked grant");

        inTransaction(() -> records.onResultRecorded(lab(91, "CANCELLED", false, false)));
        assertEquals(2, stored.count());
    }

    @Test
    void imagingResult_usesTheImagingFlagCategoryAndLinks() {
        grantAccess(caregiver, patient, a -> a.setCanViewImaging(true));
        grantAccess(otherCaregiver, patient, a -> a.setCanViewLabResults(true));
        inTransaction(() -> records.onResultRecorded(new ClinicalResultRecordedEvent(
                ClinicalResultRecordedEvent.IMAGING, 21, pat, doc, doc, "COMPLETED", false, false, true)));
        Row toPatient = only(stored.of(pat));
        assertEquals("LAB_RESULT", toPatient.type());
        assertEquals("IMAGING_RESULT", toPatient.category());
        assertEquals("IMAGING_RESULTS", toPatient.target());
        assertEquals("IMAGING_RESULT", toPatient.resourceType());
        assertEquals("New imaging result", toPatient.title());
        assertEquals("/dashboard/patient?section=imaging-results&id=21", toPatient.actionUrl());
        assertEquals("RESULT_AVAILABLE:IMAGING:21", toPatient.dedupeKey());
        assertEquals("/dashboard/proxy?section=documents-results&patient=PAT-00001&id=21&tab=imaging",
                only(stored.of(caregiver.getSystemId())).actionUrl());
        assertTrue(stored.of(otherCaregiver.getSystemId()).isEmpty());

        stored.setting(pat, "imaging_result_alerts", false);
        inTransaction(() -> records.onResultRecorded(new ClinicalResultRecordedEvent(
                ClinicalResultRecordedEvent.IMAGING, 22, pat, doc, doc, "CRITICAL", true, true, false)));
        assertEquals(1, stored.of(pat).size(), "the imaging toggle also switches critical imaging off");
        assertEquals("Important imaging result", stored.of(caregiver.getSystemId()).get(1).title());
    }

    // ==================== DOCUMENTS (N11) ====================

    @Test
    void documentUploaded_notifiesThePatientWhenSomeoneElseUploaded() {
        grantAccess(caregiver, patient, a -> a.setAccessLevel("FULL_ACCESS"));
        inTransaction(() -> records.onDocumentUploaded(new DocumentUploadedEvent(70, pat, pat, "LAB_REPORT")));
        assertEquals(0, stored.count(), "the patient's own upload");

        inTransaction(() -> records.onDocumentUploaded(new DocumentUploadedEvent(71, pat, doc, "LAB_REPORT")));
        inTransaction(() -> records.onDocumentUploaded(new DocumentUploadedEvent(71, pat, doc, "LAB_REPORT")));
        inTransaction(() -> records.onDocumentUploaded(
                new DocumentUploadedEvent(72, pat, caregiver.getSystemId(), "INSURANCE")));
        inTransaction(() -> records.onDocumentUploaded(new DocumentUploadedEvent(73, pat, admin.getSystemId(), "OTHER")));
        inTransaction(() -> records.onDocumentUploaded(new DocumentUploadedEvent(74, pat, null, "OTHER")));

        List<Row> toPatient = stored.of(pat);
        assertEquals(4, toPatient.size(), toPatient.toString());
        assertEquals("SYSTEM", toPatient.get(0).type());
        assertEquals("SYSTEM", toPatient.get(0).category());
        assertEquals("New document", toPatient.get(0).title());
        assertEquals(doctorName + " added a document to your records", toPatient.get(0).message());
        assertEquals("/dashboard/patient?section=documents&id=71", toPatient.get(0).actionUrl());
        assertEquals("DOC_UPLOADED:71", toPatient.get(0).dedupeKey());
        assertEquals(caregiver.getFirstName() + " " + caregiver.getLastName() + " added a document to your records",
                toPatient.get(1).message());
        assertEquals("The clinic added a document to your records", toPatient.get(2).message());
        assertEquals("The clinic added a document to your records", toPatient.get(3).message());
        assertTrue(stored.of(caregiver.getSystemId()).isEmpty());
        assertTrue(stored.of(doc).isEmpty());
    }

    // ==================== MISSED DOSES (N12) ====================

    @Test
    void missedDoses_twoInARowNotifyCaregiversWithMedicationsOncePerPrescriptionAndDay() {
        grantAccess(caregiver, patient, a -> a.setCanViewMedications(true));
        grantAccess(otherCaregiver, patient, a -> a.setCanViewRecords(true));
        LocalDate day = LocalDate.of(2026, 11, 10);
        inTransaction(() -> monitoring.onDoseMissed(
                new MedicationDoseMissedEvent(500, 31, pat, day, java.time.LocalTime.of(8, 0), 1)));
        assertEquals(0, stored.count(), "one miss is not enough");

        inTransaction(() -> monitoring.onDoseMissed(
                new MedicationDoseMissedEvent(501, 31, pat, day, java.time.LocalTime.of(14, 0), 2)));
        inTransaction(() -> monitoring.onDoseMissed(
                new MedicationDoseMissedEvent(502, 31, pat, day, java.time.LocalTime.of(20, 0), 3)));
        Row toCaregiver = only(stored.of(caregiver.getSystemId()));
        assertEquals("MEDICATION", toCaregiver.type());
        assertEquals("MEDICATION_REMINDER", toCaregiver.category());
        assertEquals("HIGH", toCaregiver.priority());
        assertEquals("Missed medication doses", toCaregiver.title());
        assertEquals(patientName + " has missed scheduled medication doses in a row. Please check in with them.",
                toCaregiver.message());
        assertEquals("/dashboard/proxy?section=medications&patient=PAT-00001&id=501&tab=schedule&date=2026-11-10",
                toCaregiver.actionUrl());
        assertEquals("DOSE_MISSED:31:2026-11-10", toCaregiver.dedupeKey());
        assertEquals("MEDICATION_SCHEDULE", toCaregiver.resourceType());
        assertTrue(stored.of(pat).isEmpty(), "the patient is not notified");
        assertTrue(stored.of(doc).isEmpty());
        assertTrue(stored.of(otherCaregiver.getSystemId()).isEmpty());

        inTransaction(() -> monitoring.onDoseMissed(
                new MedicationDoseMissedEvent(503, 31, pat, day.plusDays(1), java.time.LocalTime.of(8, 0), 4)));
        assertEquals(2, stored.of(caregiver.getSystemId()).size(), "a new day notifies again");
    }

    // ==================== VITALS (N13) ====================

    @Test
    void abnormalVitals_oneNotificationPerFamilyDayAndSeverity_toCaregiversWithRecordsOnly() {
        grantAccess(caregiver, patient, a -> a.setCanViewRecords(true));
        grantAccess(otherCaregiver, patient, a -> {
            a.setCanViewRecords(true);
            a.setCanManageCarePlan(true);
        });
        grantAccess(thirdCaregiver, patient, a -> a.setCanViewMedications(true));

        inTransaction(() -> monitoring.onHealthMetricRecorded(new HealthMetricRecordedEvent(
                600, pat, pat, "HEART_RATE", false, "NORMAL", "MANUAL")));
        assertEquals(0, stored.count());

        // A blood-pressure pair entered by the second caregiver: one notification, not to that caregiver
        String entered = otherCaregiver.getSystemId();
        inTransaction(() -> monitoring.onHealthMetricRecorded(new HealthMetricRecordedEvent(
                601, pat, entered, "BP_SYSTOLIC", true, "ABNORMAL", "MANUAL")));
        inTransaction(() -> monitoring.onHealthMetricRecorded(new HealthMetricRecordedEvent(
                602, pat, entered, "BP_DIASTOLIC", true, "ABNORMAL", "MANUAL")));
        Row abnormal = only(stored.of(caregiver.getSystemId()));
        assertEquals("SYSTEM", abnormal.type());
        assertEquals("VITALS", abnormal.category());
        assertEquals("NORMAL", abnormal.priority());
        assertEquals("HEALTH_TRACKING", abnormal.target());
        assertEquals("HEALTH_METRIC", abnormal.resourceType());
        assertEquals(601, abnormal.resourceId());
        assertEquals("Abnormal health reading", abnormal.title());
        assertEquals("A health reading recorded for " + patientName + " is outside the expected range",
                abnormal.message());
        assertEquals("/dashboard/proxy?section=health-summary&patient=PAT-00001&id=601", abnormal.actionUrl());
        assertEquals("VITALS_ABNORMAL:PAT-00001:BP:" + LocalDate.now(clock) + ":ABNORMAL", abnormal.dedupeKey());
        assertTrue(stored.of(entered).isEmpty(), "the caregiver who entered the reading is the actor");
        assertTrue(stored.of(thirdCaregiver.getSystemId()).isEmpty());
        assertTrue(stored.of(pat).isEmpty());
        assertTrue(stored.of(doc).isEmpty());

        // A critical reading of the same family the same day: one more, HIGH; another family: one more
        inTransaction(() -> monitoring.onHealthMetricRecorded(new HealthMetricRecordedEvent(
                603, pat, pat, "BP_SYSTOLIC", true, "CRITICAL", "MANUAL")));
        inTransaction(() -> monitoring.onHealthMetricRecorded(new HealthMetricRecordedEvent(
                604, pat, pat, "BP_DIASTOLIC", true, "CRITICAL", "MANUAL")));
        inTransaction(() -> monitoring.onHealthMetricRecorded(new HealthMetricRecordedEvent(
                605, pat, pat, "BLOOD_GLUCOSE", true, "ABNORMAL", "MANUAL")));
        List<Row> rows = stored.of(caregiver.getSystemId());
        assertEquals(3, rows.size(), rows.toString());
        assertEquals("HIGH", rows.get(1).priority());
        assertEquals("Critical health reading", rows.get(1).title());
        assertEquals("A health reading recorded for " + patientName
                + " is far outside the expected range. Please contact the care team.", rows.get(1).message());
        assertEquals(2, stored.of(entered).size(), "readings the caregiver did not enter reach them");
        assertFalse(stored.all().stream().anyMatch(row -> row.message().contains("BP")
                || row.message().contains("GLUCOSE")), "no metric type in the text");

        // Switched off
        stored.setting(caregiver.getSystemId(), "vitals_alerts", false);
        inTransaction(() -> monitoring.onHealthMetricRecorded(new HealthMetricRecordedEvent(
                606, pat, pat, "TEMPERATURE", true, "ABNORMAL", "MANUAL")));
        assertEquals(3, stored.of(caregiver.getSystemId()).size());
    }

    @Test
    void familyKey_mergesBloodPressureHalves() {
        assertEquals("BP", CareMonitoringNotificationListener.family("BP_SYSTOLIC"));
        assertEquals("BP", CareMonitoringNotificationListener.family(" bp_diastolic "));
        assertEquals("HEART_RATE", CareMonitoringNotificationListener.family("HEART_RATE"));
        assertEquals("UNKNOWN", CareMonitoringNotificationListener.family(null));
    }
}
