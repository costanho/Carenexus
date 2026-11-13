package com.carenexus.api.communications.listener.clinical;

import com.carenexus.api.auth.model.User;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The exact strings of api/be-clinical-notifications.md section 3.2, the placeholder formats and fallbacks, and the
 * system cancellation reason rule.
 */
class ClinicalNotificationTextsTest {

    private static final String PATIENT = "Thomas Dube";
    private static final String DOCTOR = "Dr. Amara Osei";
    private static final LocalDateTime WHEN = LocalDateTime.of(2026, 11, 12, 9, 0);

    @Test
    void appointmentTexts_perAudience() {
        assertEquals("An appointment with Dr. Amara Osei was booked for you on 12 Nov 2026 at 09:00",
                ClinicalNotificationTexts.appointmentBooked(Audience.PATIENT, PATIENT, DOCTOR, WHEN));
        assertEquals("An appointment with Dr. Amara Osei was booked for Thomas Dube on 12 Nov 2026 at 09:00",
                ClinicalNotificationTexts.appointmentBooked(Audience.CAREGIVER, PATIENT, DOCTOR, WHEN));
        assertEquals("Thomas Dube has a new appointment with you on 12 Nov 2026 at 09:00",
                ClinicalNotificationTexts.appointmentBooked(Audience.DOCTOR, PATIENT, DOCTOR, WHEN));

        assertEquals("Your appointment with Dr. Amara Osei was moved to 12 Nov 2026 at 09:00",
                ClinicalNotificationTexts.appointmentRescheduled(Audience.PATIENT, PATIENT, DOCTOR, WHEN));
        assertEquals("Thomas Dube's appointment with Dr. Amara Osei was moved to 12 Nov 2026 at 09:00",
                ClinicalNotificationTexts.appointmentRescheduled(Audience.CAREGIVER, PATIENT, DOCTOR, WHEN));
        assertEquals("Your appointment with Thomas Dube was moved to 12 Nov 2026 at 09:00",
                ClinicalNotificationTexts.appointmentRescheduled(Audience.DOCTOR, PATIENT, DOCTOR, WHEN));

        assertEquals("Your appointment with Dr. Amara Osei on 12 Nov 2026 at 09:00 was cancelled",
                ClinicalNotificationTexts.appointmentCancelled(Audience.PATIENT, PATIENT, DOCTOR, WHEN, null));
        assertEquals("Thomas Dube's appointment with Dr. Amara Osei on 12 Nov 2026 at 09:00 was cancelled",
                ClinicalNotificationTexts.appointmentCancelled(Audience.CAREGIVER, PATIENT, DOCTOR, WHEN, null));
        assertEquals("Your appointment with Thomas Dube on 12 Nov 2026 at 09:00 was cancelled",
                ClinicalNotificationTexts.appointmentCancelled(Audience.DOCTOR, PATIENT, DOCTOR, WHEN, null));

        assertEquals("Your appointment with Dr. Amara Osei was marked as missed",
                ClinicalNotificationTexts.appointmentNoShow(Audience.PATIENT, PATIENT, DOCTOR));
        assertEquals("Thomas Dube's appointment with Dr. Amara Osei was marked as missed",
                ClinicalNotificationTexts.appointmentNoShow(Audience.CAREGIVER, PATIENT, DOCTOR));
        assertEquals("Your appointment with Thomas Dube was marked as missed",
                ClinicalNotificationTexts.appointmentNoShow(Audience.DOCTOR, PATIENT, DOCTOR));
    }

    @Test
    void systemCancellationReason_isAppendedTrimmedCollapsedAndCut() {
        assertEquals("Your appointment with Dr. Amara Osei on 12 Nov 2026 at 09:00 was cancelled. "
                        + "Reason: The doctor is no longer available",
                ClinicalNotificationTexts.appointmentCancelled(Audience.PATIENT, PATIENT, DOCTOR, WHEN,
                        "  The doctor   is no\nlonger available "));
        assertEquals("Your appointment with Thomas Dube on 12 Nov 2026 at 09:00 was cancelled",
                ClinicalNotificationTexts.appointmentCancelled(Audience.DOCTOR, PATIENT, DOCTOR, WHEN, "   "));
        String longReason = "a".repeat(250);
        assertEquals("a".repeat(200), ClinicalNotificationTexts.normalizeReason(longReason));
        assertNull(ClinicalNotificationTexts.normalizeReason(null));
    }

    @Test
    void fallbacksOpenTheSentenceWithACapitalLetter_andATimeMayBeMissing() {
        assertEquals("The patient has a new appointment with you",
                ClinicalNotificationTexts.appointmentBooked(Audience.DOCTOR, ClinicalNotificationTexts.FALLBACK_PATIENT,
                        ClinicalNotificationTexts.FALLBACK_DOCTOR, null));
        assertEquals("The doctor completed your consultation. The summary is available.",
                ClinicalNotificationTexts.consultationCompleted(Audience.PATIENT, PATIENT,
                        ClinicalNotificationTexts.FALLBACK_DOCTOR, false, null));
        assertEquals("The patient's appointment with Dr. Amara Osei was marked as missed",
                ClinicalNotificationTexts.appointmentNoShow(Audience.CAREGIVER,
                        ClinicalNotificationTexts.FALLBACK_PATIENT, DOCTOR));
    }

    @Test
    void consultationAndPrescriptionTexts() {
        assertEquals("Dr. Amara Osei completed your consultation. The summary is available.",
                ClinicalNotificationTexts.consultationCompleted(Audience.PATIENT, PATIENT, DOCTOR, false, null));
        assertEquals("Dr. Amara Osei completed a consultation with Thomas Dube. The summary is available. "
                        + "A follow-up visit is recommended.",
                ClinicalNotificationTexts.consultationCompleted(Audience.CAREGIVER, PATIENT, DOCTOR, true, null));
        assertEquals("Dr. Amara Osei completed your consultation. The summary is available. "
                        + "A follow-up visit is recommended by 26 Nov 2026.",
                ClinicalNotificationTexts.consultationCompleted(Audience.PATIENT, PATIENT, DOCTOR, true,
                        LocalDate.of(2026, 11, 26)));

        assertEquals("Dr. Amara Osei issued a new prescription for you",
                ClinicalNotificationTexts.prescriptionIssued(Audience.PATIENT, PATIENT, DOCTOR));
        assertEquals("Dr. Amara Osei issued a new prescription for Thomas Dube",
                ClinicalNotificationTexts.prescriptionIssued(Audience.CAREGIVER, PATIENT, DOCTOR));
        assertEquals("One of your prescriptions has expired. Contact your doctor if you need a renewal.",
                ClinicalNotificationTexts.prescriptionExpired());
        assertEquals("Dr. Amara Osei cancelled one of your prescriptions",
                ClinicalNotificationTexts.prescriptionCancelled(DOCTOR));
    }

    @Test
    void resultDocumentDoseAndVitalsTexts() {
        assertEquals("A new lab result is available in your records",
                ClinicalNotificationTexts.resultAvailable(Audience.PATIENT, false, PATIENT));
        assertEquals("A lab result for Thomas Dube is available",
                ClinicalNotificationTexts.resultAvailable(Audience.CAREGIVER, false, PATIENT));
        assertEquals("A new imaging result is available in your records",
                ClinicalNotificationTexts.resultAvailable(Audience.PATIENT, true, PATIENT));
        assertEquals("An imaging result for Thomas Dube is available",
                ClinicalNotificationTexts.resultAvailable(Audience.CAREGIVER, true, PATIENT));
        assertEquals("A lab result needs your attention. Please contact your care team.",
                ClinicalNotificationTexts.resultCritical(Audience.PATIENT, false, PATIENT));
        assertEquals("A lab result for Thomas Dube needs attention. Please contact the care team.",
                ClinicalNotificationTexts.resultCritical(Audience.CAREGIVER, false, PATIENT));
        assertEquals("An imaging result needs your attention. Please contact your care team.",
                ClinicalNotificationTexts.resultCritical(Audience.PATIENT, true, PATIENT));
        assertEquals("An imaging result for Thomas Dube needs attention. Please contact the care team.",
                ClinicalNotificationTexts.resultCritical(Audience.CAREGIVER, true, PATIENT));
        assertEquals("New lab result", ClinicalNotificationTexts.resultAvailableTitle(false));
        assertEquals("Important imaging result", ClinicalNotificationTexts.resultCriticalTitle(true));

        assertEquals("The clinic added a document to your records",
                ClinicalNotificationTexts.documentUploaded(ClinicalNotificationTexts.FALLBACK_UPLOADER));
        assertEquals("Thomas Dube has missed scheduled medication doses in a row. Please check in with them.",
                ClinicalNotificationTexts.dosesMissed(PATIENT));
        assertEquals("A health reading recorded for Thomas Dube is outside the expected range",
                ClinicalNotificationTexts.vitals(false, PATIENT));
        assertEquals("A health reading recorded for Thomas Dube is far outside the expected range. "
                + "Please contact the care team.", ClinicalNotificationTexts.vitals(true, PATIENT));
        assertEquals("Critical health reading", ClinicalNotificationTexts.vitalsTitle(true));
    }

    @Test
    void titlesAndMessagesStayWithinTheStoredLimits() {
        // Names are cut to MAX_NAME_LENGTH by ClinicalNotifier.Names before they reach the texts
        User longNamed = new User();
        longNamed.setSystemId("DOC-00001");
        longNamed.setRole("DOCTOR");
        longNamed.setFirstName("F".repeat(100));
        longNamed.setLastName("L".repeat(100));
        ClinicalNotifier.Names names = new ClinicalNotifier.Names(Map.of("DOC-00001", longNamed));
        String doctor = names.doctor("DOC-00001");
        assertEquals("Dr. " + "F".repeat(100), doctor);
        String patient = "P".repeat(ClinicalNotifier.Names.MAX_NAME_LENGTH);

        // The longest message: a caregiver's system cancellation with a reason of the maximum length
        String longest = ClinicalNotificationTexts.appointmentCancelled(Audience.CAREGIVER, patient, doctor, WHEN,
                "r".repeat(400));
        assertTrue(longest.length() < 500, String.valueOf(longest.length()));
        assertTrue(longest.endsWith(" Reason: " + "r".repeat(ClinicalNotificationTexts.MAX_REASON_LENGTH)));
        for (String title : new String[] {ClinicalNotificationTexts.TITLE_APPOINTMENT_NO_SHOW,
                ClinicalNotificationTexts.TITLE_CONSULTATION_COMPLETED, ClinicalNotificationTexts.TITLE_VITALS_CRITICAL}) {
            assertTrue(title.length() < 80, title);
        }
    }
}
