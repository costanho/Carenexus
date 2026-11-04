package com.carenexus.api.calls.service;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.communications.service.CommunicationLabels;

/** Display labels shown in the call UI and used as the provider token name. */
public final class CallLabels {

    private CallLabels() {
    }

    public static String fullName(User user) {
        if (user == null) {
            return "Unknown";
        }
        String first = user.getFirstName() == null ? "" : user.getFirstName().trim();
        String last = user.getLastName() == null ? "" : user.getLastName().trim();
        String name = (first + " " + last).trim();
        return name.isEmpty() ? "Unknown" : name;
    }

    public static String doctor(User doctor) {
        return CommunicationLabels.withDoctorTitle(fullName(doctor));
    }

    public static String patient(User patient) {
        return fullName(patient) + " (Patient)";
    }

    public static String caregiver(User caregiver, User patient, boolean actingForPatient) {
        return fullName(caregiver) + (actingForPatient ? " (Caregiver speaking for " : " (Caregiver for ")
                + fullName(patient) + ")";
    }
}
