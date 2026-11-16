package com.carenexus.api.core.prescriptions;

import com.carenexus.api.auth.model.User;
import com.carenexus.api.core.model.Prescription;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/** Test data for the prescription tests. */
public final class PrescriptionFixtures {

    public static final ZoneId CLINIC_ZONE = ZoneId.of("Africa/Harare");

    private PrescriptionFixtures() {
    }

    /** Clinic today, the same day the service computes from the application clock. */
    public static LocalDate clinicToday() {
        return LocalDate.now(CLINIC_ZONE);
    }

    /** A valid create body (mutable, so a test can change or remove fields). */
    public static Map<String, Object> createBody(User forPatient) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("patientSystemId", forPatient.getSystemId());
        body.put("medicationName", "Amlodipine");
        body.put("genericName", "amlodipine besylate");
        body.put("dosage", "5 mg");
        body.put("frequency", "Once daily");
        body.put("route", "ORAL");
        body.put("quantity", "30 tablets");
        body.put("refillsAllowed", 2);
        body.put("specialInstructions", "Take in the morning with water.");
        return body;
    }

    /** An unsaved row, for tests that need a stored state the API cannot produce directly. */
    public static Prescription row(User forPatient, User byDoctor, String medicationName, String status,
                                   LocalDate prescribedDate, LocalDate expiryDate) {
        return Prescription.builder()
                .patientSystemId(forPatient.getSystemId())
                .doctorSystemId(byDoctor.getSystemId())
                .medicationName(medicationName)
                .dosage("10 mg")
                .frequency("Twice daily")
                .route("ORAL")
                .refillsAllowed(0)
                .status(status)
                .prescribedDate(prescribedDate)
                .expiryDate(expiryDate)
                .createdAt(LocalDateTime.now(CLINIC_ZONE))
                .build();
    }
}
