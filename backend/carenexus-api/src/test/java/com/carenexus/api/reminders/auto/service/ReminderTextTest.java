package com.carenexus.api.reminders.auto.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Text, time phrases and keys of the automatic reminders (the examples of the be-reminder-jobs contract). */
class ReminderTextTest {

    private static final ZoneId HARARE = ZoneId.of("Africa/Harare");      // UTC+2, the clinic
    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");    // UTC+3
    private static final ZoneId MAPUTO = ZoneId.of("Africa/Maputo");      // UTC+2, same offset
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York"); // UTC-5 in November

    private static final LocalDateTime THU_0900 = LocalDateTime.of(2026, 11, 12, 9, 0);

    @Test
    void appointmentText_clinicTimeOnly_whenRecipientHasNoZone() {
        assertEquals("You have an appointment with Dr. Amara Osei on Thu 12 Nov 2026 at 09:00 clinic time.",
                ReminderText.appointmentForPatient("Amara Osei", THU_0900, HARARE, HARARE));
    }

    @Test
    void appointmentText_addsLocalTime_onTheSameDate() {
        assertEquals("You have an appointment with Dr. Amara Osei on Thu 12 Nov 2026 at 09:00 clinic time "
                        + "(10:00 your time).",
                ReminderText.appointmentForPatient("Amara Osei", THU_0900, HARARE, NAIROBI));
    }

    @Test
    void appointmentText_addsLocalDateAndTime_onAnotherDate() {
        assertEquals("Thomas Dube has an appointment with Dr. Amara Osei on Thu 12 Nov 2026 at 03:00 clinic time "
                        + "(Wed 11 Nov 20:00 your time).",
                ReminderText.appointmentForCaregiver("Thomas Dube", "Amara Osei",
                        LocalDateTime.of(2026, 11, 12, 3, 0), HARARE, NEW_YORK));
    }

    @Test
    void appointmentText_noLocalPart_forAZoneWithTheSameOffset() {
        assertEquals("You have an appointment with Dr. Amara Osei on Thu 12 Nov 2026 at 09:00 clinic time.",
                ReminderText.appointmentForPatient("Amara Osei", THU_0900, HARARE, MAPUTO));
    }

    @Test
    void doseText_forPatientAndCaregiver() {
        LocalDateTime due = LocalDateTime.of(2026, 11, 12, 8, 0);
        assertEquals("It is time to take Amlodipine. Due at 08:00 clinic time (09:00 your time).",
                ReminderText.doseForPatient("Amlodipine", due, HARARE, NAIROBI));
        assertEquals("Thomas Dube has a dose of Amlodipine due at 08:00 clinic time.",
                ReminderText.doseForCaregiver("Thomas Dube", "Amlodipine", due, HARARE, HARARE));
    }

    @Test
    void needsOutcomeText_forDoctor() {
        assertEquals("Your appointment with Thomas Dube on Tue 10 Nov 2026 at 14:30 clinic time still has no outcome. "
                        + "Mark it completed, no-show or cancelled.",
                ReminderText.needsOutcomeForDoctor("Thomas Dube", LocalDateTime.of(2026, 11, 10, 14, 30),
                        HARARE, HARARE));
    }

    @Test
    void fallbacks_whenNamesAreMissing() {
        LocalDateTime due = LocalDateTime.of(2026, 11, 12, 8, 0);
        assertEquals("You have an appointment with the doctor on Thu 12 Nov 2026 at 09:00 clinic time.",
                ReminderText.appointmentForPatient(null, THU_0900, HARARE, HARARE));
        assertEquals("The patient has an appointment with the doctor on Thu 12 Nov 2026 at 09:00 clinic time.",
                ReminderText.appointmentForCaregiver(" ", "", THU_0900, HARARE, HARARE));
        assertEquals("It is time to take your medication. Due at 08:00 clinic time.",
                ReminderText.doseForPatient(null, due, HARARE, HARARE));
        assertEquals("The patient has a dose of a medication due at 08:00 clinic time.",
                ReminderText.doseForCaregiver(null, "  ", due, HARARE, HARARE));
        assertEquals("Your appointment with the patient on Thu 12 Nov 2026 at 09:00 clinic time still has no outcome. "
                        + "Mark it completed, no-show or cancelled.",
                ReminderText.needsOutcomeForDoctor(null, THU_0900, HARARE, HARARE));
    }

    @Test
    void doctorTitle_isNotDoubled() {
        assertTrue(ReminderText.appointmentForPatient("Dr. Michael Banda", THU_0900, HARARE, HARARE)
                .startsWith("You have an appointment with Dr. Michael Banda on "));
    }

    @Test
    void medicationName_isTrimmedAndCut() {
        String longName = "  " + "A".repeat(150) + "  ";
        String text = ReminderText.doseForPatient(longName, THU_0900, HARARE, HARARE);
        assertEquals("It is time to take " + "A".repeat(120) + ". Due at 09:00 clinic time.", text);
    }

    @Test
    void recipientZone_fallsBackToTheClinic_forMissingBlankOrInvalidValues() {
        assertEquals(HARARE, ReminderText.recipientZone(null, HARARE));
        assertEquals(HARARE, ReminderText.recipientZone("  ", HARARE));
        assertEquals(HARARE, ReminderText.recipientZone("Mars/Olympus_Mons", HARARE));
        assertEquals(NAIROBI, ReminderText.recipientZone(" Africa/Nairobi ", HARARE));
    }

    @Test
    void keys_useTheClinicStartToTheMinute() {
        assertEquals("APPT_REMINDER_24H:412:2026-11-12T09:00",
                ReminderText.appointmentKey(ReminderText.FIRST_REMINDER_KEY, 412, THU_0900));
        assertEquals("APPT_REMINDER_1H:412:2026-11-12T09:00",
                ReminderText.appointmentKey(ReminderText.SECOND_REMINDER_KEY, 412, THU_0900));
        assertEquals("APPT_NEEDS_OUTCOME:412:2026-11-12T09:00",
                ReminderText.appointmentKey(ReminderText.NEEDS_OUTCOME_KEY, 412, THU_0900));
        assertEquals("DOSE_REMINDER:77", ReminderText.doseKey(77));
    }

    @Test
    void titlesAndMessages_stayWithinTheNotificationLimits() {
        for (String title : new String[]{ReminderText.FIRST_REMINDER_TITLE, ReminderText.SECOND_REMINDER_TITLE,
                ReminderText.DOSE_REMINDER_TITLE, ReminderText.NEEDS_OUTCOME_TITLE}) {
            assertTrue(title.length() < 80, title);
        }
        String longest = ReminderText.doseForCaregiver("X".repeat(201), "M".repeat(200), THU_0900, HARARE, NEW_YORK);
        assertTrue(longest.length() < 500, String.valueOf(longest.length()));
    }
}
