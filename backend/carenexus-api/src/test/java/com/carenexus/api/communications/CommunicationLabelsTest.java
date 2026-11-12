package com.carenexus.api.communications;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.calls.service.CallLabels;
import com.carenexus.api.communications.service.CommunicationLabels;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommunicationLabelsTest {

    @Test
    void doctorLabel_addsTheTitleOnce() {
        assertEquals("Dr. James Banda", CommunicationLabels.label("James Banda", "DOCTOR", "Thomas Dube"));
        // Some seed doctors store the title in first_name ("Dr. Michael").
        assertEquals("Dr. Michael Kariuki", CommunicationLabels.label("Dr. Michael Kariuki", "DOCTOR", "Thomas Dube"));
        assertEquals("Dr Michael Kariuki", CommunicationLabels.label("Dr Michael Kariuki", "DOCTOR", "Thomas Dube"));
        // A name that merely starts with the letters "Dr" still gets the title.
        assertEquals("Dr. Drew Moyo", CommunicationLabels.label("Drew Moyo", "DOCTOR", "Thomas Dube"));
    }

    @Test
    void clinicalLabels_nameThePatientForCaregivers() {
        assertEquals("Sarah Dube (Caregiver for Thomas Dube)", CommunicationLabels.label("Sarah Dube", "CAREGIVER", "Thomas Dube"));
        assertEquals("Thomas Dube", CommunicationLabels.label("Thomas Dube", "PATIENT", "Thomas Dube"));
    }

    @Test
    void supportLabels_haveNoPatientAndNeverSayNull() {
        assertEquals("Sarah Dube (Caregiver)", CommunicationLabels.label("Sarah Dube", "CAREGIVER", null));
        assertEquals("Grace Moyo (Support)", CommunicationLabels.label("Grace Moyo", "ADMIN", null));
        assertEquals("Dr. Amara Osei", CommunicationLabels.label("Amara Osei", "DOCTOR", null));
        assertEquals("Thomas Dube", CommunicationLabels.label("Thomas Dube", "PATIENT", null));
    }

    @Test
    void callDoctorLabel_addsTheTitleOnce() {
        User doctor = new User();
        doctor.setFirstName("Dr. Michael");
        doctor.setLastName("Kariuki");
        assertEquals("Dr. Michael Kariuki", CallLabels.doctor(doctor));
        doctor.setFirstName("James");
        doctor.setLastName("Banda");
        assertEquals("Dr. James Banda", CallLabels.doctor(doctor));
    }
}
